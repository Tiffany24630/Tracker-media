import secrets
from datetime import UTC, datetime
from typing import Any
from uuid import UUID

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.exceptions import ConflictError, NotFoundError
from app.core.security import (
    create_oauth_state,
    decode_oauth_state,
    decrypt_credentials,
    encrypt_credentials,
)
from app.integrations.base import (
    NormalizedSyncItem,
)
from app.integrations.registry import get_integration_provider
from app.models.enums import IntegrationStatus, SyncJobStatus
from app.models.library_entry import LibraryEntry
from app.models.media import Media
from app.models.media_details import MediaExternalId
from app.models.tracking import SyncJob, UserIntegration
from app.models.user import User
from app.schemas.library import (
    LibraryEntryUpsert,
    ProgressUpdate,
    RatingUpsert,
)
from app.schemas.media import ExternalIdCreate, MediaCreate
from app.services import library as library_service
from app.services import media as media_service
from app.utils.matching import build_media_match_key


def utc_now() -> datetime:
    return datetime.now(UTC)


async def get_authorization_url(
    provider_name: str, redirect_uri: str | None = None, state: str | None = None
) -> dict[str, Any]:
    provider = get_integration_provider(provider_name)
    signed_state = create_oauth_state(
        {
            "provider": provider.name,
            "nonce": state or secrets.token_urlsafe(16),
            "issued_at": utc_now().timestamp(),
        }
    )
    return await provider.authenticate(redirect_uri=redirect_uri, state=signed_state)


async def connect_oauth_integration(
    session: AsyncSession,
    *,
    user: User,
    provider_name: str,
    code: str,
    redirect_uri: str | None = None,
    state: str | None = None,
) -> UserIntegration:
    provider = get_integration_provider(provider_name)
    if not state:
        raise ConflictError("OAuth state is required")
    try:
        state_data = decode_oauth_state(state)
        issued_at = float(state_data["issued_at"])
    except (KeyError, TypeError, ValueError) as exc:
        raise ConflictError("Invalid OAuth state") from exc
    if state_data.get("provider") != provider.name or utc_now().timestamp() - issued_at > 600:
        raise ConflictError("Expired or mismatched OAuth state")
    token_data = await provider.oauth_callback(code=code, redirect_uri=redirect_uri)
    encrypted = encrypt_credentials(token_data.model_dump())

    integration = await session.scalar(
        select(UserIntegration).where(
            UserIntegration.user_id == user.id,
            UserIntegration.provider == provider.name,
        )
    )

    if integration is None:
        integration = UserIntegration(
            user_id=user.id,
            provider=provider.name,
            external_user_id=token_data.external_user_id,
            status=IntegrationStatus.ACTIVE,
            scopes=token_data.scopes,
            credentials_encrypted=encrypted,
        )
        session.add(integration)
    else:
        integration.external_user_id = token_data.external_user_id
        integration.status = IntegrationStatus.ACTIVE
        integration.scopes = token_data.scopes
        integration.credentials_encrypted = encrypted

    await session.commit()
    await session.refresh(integration)
    return integration


async def disconnect_integration(
    session: AsyncSession, *, user: User, provider_name: str
) -> UserIntegration:
    integration = await get_user_integration(session, user=user, provider_name=provider_name)
    if integration.status == IntegrationStatus.DISCONNECTED:
        return integration

    provider = get_integration_provider(provider_name)
    if integration.credentials_encrypted:
        try:
            creds = decrypt_credentials(integration.credentials_encrypted)
            await provider.disconnect(creds)
        except Exception:
            pass

    integration.status = IntegrationStatus.DISCONNECTED
    integration.credentials_encrypted = None
    await session.commit()
    await session.refresh(integration)
    return integration


async def get_user_integration(
    session: AsyncSession, *, user: User, provider_name: str
) -> UserIntegration:
    integration = await session.scalar(
        select(UserIntegration).where(
            UserIntegration.user_id == user.id,
            UserIntegration.provider == provider_name.lower(),
        )
    )
    if integration is None:
        raise NotFoundError(f"Integration '{provider_name}' not found for this user")
    return integration


async def list_user_integrations(session: AsyncSession, *, user: User) -> list[UserIntegration]:
    return list(
        (
            await session.scalars(
                select(UserIntegration)
                .where(UserIntegration.user_id == user.id)
                .order_by(UserIntegration.created_at)
            )
        ).all()
    )


async def list_sync_jobs(
    session: AsyncSession,
    *,
    user: User,
    provider_name: str | None = None,
    limit: int = 50,
    offset: int = 0,
) -> list[SyncJob]:
    statement = (
        select(SyncJob)
        .where(SyncJob.user_id == user.id)
        .order_by(SyncJob.created_at.desc())
        .limit(limit)
        .offset(offset)
    )
    if provider_name:
        integration = await session.scalar(
            select(UserIntegration.id).where(
                UserIntegration.user_id == user.id,
                UserIntegration.provider == provider_name.lower(),
            )
        )
        if integration:
            statement = statement.where(SyncJob.user_integration_id == integration)
    return list((await session.scalars(statement)).all())


async def get_sync_job(session: AsyncSession, *, user: User, job_id: UUID) -> SyncJob:
    job = await session.scalar(
        select(SyncJob).where(SyncJob.id == job_id, SyncJob.user_id == user.id)
    )
    if job is None:
        raise NotFoundError("Sync job not found")
    return job


async def get_or_create_user_integration(
    session: AsyncSession, *, user: User, provider_name: str
) -> UserIntegration:
    provider = get_integration_provider(provider_name)
    integration = await session.scalar(
        select(UserIntegration).where(
            UserIntegration.user_id == user.id,
            UserIntegration.provider == provider.name,
        )
    )
    if integration is None:
        integration = UserIntegration(
            user_id=user.id,
            provider=provider.name,
            external_user_id=f"{user.id}_{provider.name}",
            status=IntegrationStatus.ACTIVE,
            scopes=[],
            credentials_encrypted=None,
        )
        session.add(integration)
        await session.commit()
        await session.refresh(integration)
    return integration


async def sync_integration(
    session: AsyncSession,
    *,
    integration_id: UUID,
    is_manual: bool = True,
    imported_items: list[dict[str, Any]] | None = None,
) -> SyncJob:
    integration = await session.scalar(
        select(UserIntegration).where(UserIntegration.id == integration_id).with_for_update()
    )
    if not integration:
        raise NotFoundError("User integration not found")

    user = await session.get(User, integration.user_id)
    if not user:
        raise NotFoundError("User not found")

    running_job = await session.scalar(
        select(SyncJob.id).where(
            SyncJob.user_integration_id == integration.id,
            SyncJob.status == SyncJobStatus.RUNNING,
        )
    )
    if running_job is not None:
        raise ConflictError("An integration sync is already running")

    sync_job = SyncJob(
        user_id=integration.user_id,
        user_integration_id=integration.id,
        job_type=f"sync_{integration.provider}",
        status=SyncJobStatus.RUNNING,
        payload={
            "manual": is_manual,
            "imported_count": len(imported_items) if imported_items else 0,
        },
        started_at=utc_now(),
        attempts=1,
    )
    session.add(sync_job)
    await session.commit()
    await session.refresh(sync_job)

    provider = get_integration_provider(integration.provider)

    try:
        credentials: dict[str, Any] = {}
        if integration.credentials_encrypted:
            credentials = decrypt_credentials(integration.credentials_encrypted)
            refreshed = await provider.refresh_credentials(credentials)
            if refreshed != credentials:
                integration.credentials_encrypted = encrypt_credentials(refreshed)
                credentials = refreshed

        if imported_items is not None:
            credentials["imported_items"] = imported_items

        normalized_items = await provider.sync(credentials)

        items_added = 0
        items_updated = 0
        items_conflicted = 0
        items_skipped = 0
        reports: list[dict[str, Any]] = []

        for item in normalized_items:
            media_id = await _resolve_or_create_media(session, item)
            if not media_id:
                items_skipped += 1
                reports.append(
                    {
                        "title": item.media.title,
                        "action": "skipped",
                        "reason": "could_not_resolve_media",
                    }
                )
                continue

            entry = await session.scalar(
                select(LibraryEntry).where(
                    LibraryEntry.user_id == user.id,
                    LibraryEntry.media_id == media_id,
                )
            )

            if entry is None:
                await library_service.upsert_library_entry(
                    session,
                    user=user,
                    media_id=media_id,
                    payload=LibraryEntryUpsert(
                        status=item.status,
                        source=item.source,
                        source_updated_at=item.source_updated_at,
                        episode=item.episode,
                        chapter=item.chapter,
                        volume=item.volume,
                        track=item.track,
                        page=item.page,
                        percentage=item.percentage,
                        rating=item.rating,
                        notes=item.notes,
                        started_at=item.started_at,
                        completed_at=item.completed_at,
                    ),
                )
                items_added += 1
                reports.append(
                    {"title": item.media.title, "action": "added", "status": item.status}
                )
            else:
                prog_payload = ProgressUpdate(
                    episode=item.episode,
                    chapter=item.chapter,
                    volume=item.volume,
                    track=item.track,
                    page=item.page,
                    percentage=item.percentage,
                    status=item.status,
                    source=item.source,
                    source_updated_at=item.source_updated_at,
                )
                conflict_reason = library_service._progress_conflict(
                    entry,
                    prog_payload,
                    item.source,
                    item.source_updated_at,
                )
                if conflict_reason:
                    # Log event with applied=False to preserve traceability
                    await library_service.update_progress(
                        session,
                        user=user,
                        media_id=media_id,
                        payload=prog_payload,
                    )
                    items_conflicted += 1
                    reports.append(
                        {
                            "title": item.media.title,
                            "action": "conflicted",
                            "conflict_reason": conflict_reason,
                        }
                    )
                else:
                    await library_service.update_progress(
                        session,
                        user=user,
                        media_id=media_id,
                        payload=prog_payload,
                    )
                    if item.rating is not None:
                        try:
                            await library_service.upsert_rating(
                                session,
                                user=user,
                                media_id=media_id,
                                payload=RatingUpsert(
                                    score=item.rating,
                                    source=item.source,
                                    source_updated_at=item.source_updated_at,
                                ),
                            )
                        except ConflictError:
                            pass
                    items_updated += 1
                    reports.append(
                        {"title": item.media.title, "action": "updated", "status": item.status}
                    )

        sync_job.status = SyncJobStatus.SUCCEEDED
        sync_job.finished_at = utc_now()
        sync_job.result = {
            "total_fetched": len(normalized_items),
            "items_added": items_added,
            "items_updated": items_updated,
            "items_conflicted": items_conflicted,
            "items_skipped": items_skipped,
            "reports": reports[:100],  # Keep reasonable size in result JSON
        }
        integration.last_synced_at = utc_now()
        integration.status = IntegrationStatus.ACTIVE
        await session.commit()
        await session.refresh(sync_job)
        return sync_job

    except Exception as exc:
        sync_job.status = SyncJobStatus.FAILED
        sync_job.finished_at = utc_now()
        sync_job.error = str(exc)
        integration.status = IntegrationStatus.ERROR
        await session.commit()
        await session.refresh(sync_job)
        return sync_job


async def _resolve_or_create_media(session: AsyncSession, item: NormalizedSyncItem) -> UUID | None:
    # 1. Match by external IDs
    for prov, ext_id in item.media.external_ids.items():
        media_id = await session.scalar(
            select(MediaExternalId.media_id)
            .where(
                MediaExternalId.provider == prov,
                MediaExternalId.external_id == ext_id,
            )
            .limit(1)
        )
        if media_id:
            return media_id

    # 2. Match by title match_key
    match_key = build_media_match_key(
        item.media.media_type, item.media.title, item.media.release_year
    )
    media_id = await session.scalar(select(Media.id).where(Media.match_key == match_key).limit(1))
    if media_id:
        # Link new external IDs
        for prov, ext_id in item.media.external_ids.items():
            existing = await session.scalar(
                select(MediaExternalId.id).where(
                    MediaExternalId.provider == prov,
                    MediaExternalId.external_id == ext_id,
                )
            )
            if not existing:
                session.add(MediaExternalId(media_id=media_id, provider=prov, external_id=ext_id))
        return media_id

    # 3. Create media if not existing
    ext_creates = [
        ExternalIdCreate(provider=p, external_id=eid) for p, eid in item.media.external_ids.items()
    ]
    created = await media_service.create_media(
        session,
        MediaCreate(
            title=item.media.title,
            media_type=item.media.media_type,
            release_year=item.media.release_year,
            external_ids=ext_creates,
        ),
        allow_duplicate_match_key=True,
    )
    return created.id
