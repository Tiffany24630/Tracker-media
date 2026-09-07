from __future__ import annotations

from datetime import UTC, datetime
from typing import Any
from uuid import UUID

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import get_settings
from app.core.exceptions import AppError, ConflictError, NotFoundError
from app.imports.adapters import NormalizedImportItem, get_import_adapter, registry
from app.models.enums import TrackingSource
from app.models.imports import ImportBatch
from app.models.user import User
from app.providers.base import ProviderContent
from app.schemas.imports import ImportDecision
from app.schemas.library import LibraryEntryUpsert
from app.schemas.media import MediaCreate
from app.services import library as library_service
from app.services import matching as matching_service
from app.services import media as media_service

MAX_UPLOAD_BYTES = 10 * 1024 * 1024


def list_adapters() -> list[Any]:
    return registry.all()


async def create_import_batch(
    session: AsyncSession,
    *,
    user: User,
    adapter_name: str,
    filename: str,
    content: bytes,
) -> ImportBatch:
    if len(content) > MAX_UPLOAD_BYTES:
        raise AppError("Import file is too large", code="import_file_too_large", status_code=413)
    adapter = get_import_adapter(adapter_name)
    extension = filename.rsplit(".", 1)[-1].casefold() if "." in filename else ""
    if f".{extension}" not in adapter.extensions:
        raise AppError(
            f"File extension '.{extension}' is not valid for adapter '{adapter.name}'",
            code="invalid_import_file",
            status_code=422,
        )
    items = adapter.parse(content, filename=filename)
    preview = []
    settings = get_settings()
    for item in items:
        content_model = _to_provider_content(item, adapter.name)
        suggestions = await matching_service.suggest_matches(session, content_model)
        top = suggestions[0] if suggestions else None
        requires_review = bool(
            len(suggestions) > 1
            and top
            and top.confidence_level in {"high", "medium"}
            and abs(top.confidence - suggestions[1].confidence) < 0.1
        )
        proposed_action = "create"
        if (
            top
            and top.confidence_level == "high"
            and top.auto_link_eligible
            and not requires_review
        ):
            proposed_action = "link"
        elif top and top.confidence >= settings.matching_suggestion_threshold:
            proposed_action = "review"
        preview.append(
            {
                "row": item.source_details.get("row", len(preview) + 1),
                "title": item.title,
                "media_type": item.media_type.value,
                "status": item.status.value,
                "rating": item.rating,
                "source_updated_at": item.source_updated_at.isoformat(),
                "matches": [value.model_dump(mode="json") for value in suggestions[:5]],
                "requires_review": requires_review,
                "proposed_action": proposed_action,
                "source_details": item.source_details,
            }
        )
    batch = ImportBatch(
        user_id=user.id,
        adapter=adapter.name,
        filename=filename,
        status="preview",
        row_count=len(items),
        normalized_items=[item.model_dump(mode="json") for item in items],
        preview=preview,
        source_details={"adapter": adapter.name, "filename": filename},
    )
    session.add(batch)
    await session.commit()
    await session.refresh(batch)
    return batch


async def get_import_batch(
    session: AsyncSession, *, user: User, batch_id: UUID
) -> ImportBatch:
    batch = await session.scalar(
        select(ImportBatch).where(ImportBatch.id == batch_id, ImportBatch.user_id == user.id)
    )
    if batch is None:
        raise NotFoundError("Import batch not found")
    return batch


async def confirm_import_batch(
    session: AsyncSession,
    *,
    user: User,
    batch_id: UUID,
    decisions: list[ImportDecision],
) -> ImportBatch:
    batch = await get_import_batch(session, user=user, batch_id=batch_id)
    if batch.status != "preview":
        raise ConflictError("Import batch is no longer awaiting confirmation")
    decision_map = {decision.row: decision for decision in decisions}
    imported = 0
    skipped = 0
    errors: list[dict[str, Any]] = []
    for raw_item in batch.normalized_items:
        item = NormalizedImportItem.model_validate(raw_item)
        row = int(item.source_details.get("row", 0))
        decision = decision_map.get(row)
        content_model = _to_provider_content(item, batch.adapter)
        suggestions = await matching_service.suggest_matches(session, content_model)
        top = suggestions[0] if suggestions else None
        if decision and decision.skip:
            skipped += 1
            continue
        if decision is None and top and (
            top.confidence_level not in {"high"} or not top.auto_link_eligible
        ):
            errors.append({"row": row, "reason": "explicit_match_decision_required"})
            continue
        media_id = decision.media_id if decision else (top.media_id if top else None)
        try:
            if media_id:
                media = await media_service.get_media(session, media_id)
            else:
                media = await media_service.create_media(
                    session,
                    MediaCreate(
                        media_type=item.media_type,
                        title=item.title,
                        release_year=item.release_year,
                        metadata={"import": item.source_details},
                    ),
                )
            await library_service.upsert_library_entry(
                session,
                user=user,
                media_id=media.id,
                payload=LibraryEntryUpsert(
                    status=item.status,
                    source=TrackingSource.IMPORT,
                    source_updated_at=item.source_updated_at,
                    started_at=item.started_at,
                    completed_at=item.completed_at,
                    episode=item.episode,
                    chapter=item.chapter,
                    volume=item.volume,
                    track=item.track,
                    page=item.page,
                    percentage=item.percentage,
                    rating=item.rating,
                    notes=item.notes,
                ),
            )
            imported += 1
        except Exception as exc:
            errors.append({"row": row, "reason": str(exc)})

    if errors and imported == 0:
        batch.status = "failed"
    else:
        batch.status = "confirmed"
    batch.confirmed_at = datetime.now(UTC)
    batch.result = {"imported": imported, "skipped": skipped, "errors": errors}
    await session.commit()
    await session.refresh(batch)
    return batch


def _to_provider_content(item: NormalizedImportItem, adapter: str) -> ProviderContent:
    return ProviderContent(
        source=f"import:{adapter}",
        external_id=str(item.source_details.get("row", item.title)),
        media_type=item.media_type,
        title=item.title,
        release_year=item.release_year,
        identifiers=item.external_ids,
        metadata={"import": item.source_details},
    )
