from datetime import UTC, datetime
from uuid import UUID

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.core.exceptions import ConflictError, NotFoundError
from app.models.enums import ProgressUnit, TrackingSource, TrackingStatus
from app.models.library_entry import LibraryEntry, UserMedia
from app.models.media import Media
from app.models.tracking import UserList, UserListItem, UserProgress, UserRating, UserReview
from app.models.user import User
from app.schemas.library import (
    LibraryEntryPatch,
    LibraryEntryUpsert,
    ProgressUpdate,
    RatingUpsert,
    ReviewUpsert,
    UserListCreate,
    UserListItemCreate,
    UserListItemUpdate,
    UserListUpdate,
)

LIBRARY_OPTIONS = (
    selectinload(UserMedia.media).selectinload(Media.titles),
    selectinload(UserMedia.media).selectinload(Media.external_ids),
    selectinload(UserMedia.progress_entries),
    selectinload(UserMedia.media_rating),
)
LIST_OPTIONS = (
    selectinload(UserList.items).selectinload(UserListItem.media).selectinload(Media.titles),
    selectinload(UserList.items)
    .selectinload(UserListItem.media)
    .selectinload(Media.external_ids),
)


def utc_now() -> datetime:
    return datetime.now(UTC)


def as_utc(value: datetime) -> datetime:
    return value.replace(tzinfo=UTC) if value.tzinfo is None else value.astimezone(UTC)


def effective_source_time(source: TrackingSource, value: datetime | None) -> datetime:
    if source == TrackingSource.MANUAL:
        return utc_now()
    if value is None:
        raise ConflictError("External updates require source_updated_at")
    return as_utc(value)


async def list_library(
    session: AsyncSession,
    *,
    user: User,
    entry_status: TrackingStatus | None,
    favorite: bool | None,
    limit: int,
    offset: int,
) -> list[LibraryEntry]:
    statement = (
        select(LibraryEntry)
        .options(*LIBRARY_OPTIONS)
        .where(LibraryEntry.user_id == user.id)
        .order_by(LibraryEntry.updated_at.desc())
        .limit(limit)
        .offset(offset)
    )
    if entry_status:
        statement = statement.where(LibraryEntry.status == entry_status)
    if favorite is not None:
        statement = statement.where(LibraryEntry.is_favorite == favorite)
    return list((await session.scalars(statement)).unique().all())


async def get_library_entry(
    session: AsyncSession, *, user: User, media_id: UUID
) -> LibraryEntry:
    entry = await session.scalar(
        select(LibraryEntry)
        .options(*LIBRARY_OPTIONS)
        .where(LibraryEntry.user_id == user.id, LibraryEntry.media_id == media_id)
    )
    if entry is None:
        raise NotFoundError("Library entry not found")
    return entry


async def upsert_library_entry(
    session: AsyncSession,
    *,
    user: User,
    media_id: UUID,
    payload: LibraryEntryUpsert,
) -> LibraryEntry:
    if await session.get(Media, media_id) is None:
        raise NotFoundError("Media item not found")
    entry = await session.scalar(
        select(LibraryEntry).where(
            LibraryEntry.user_id == user.id, LibraryEntry.media_id == media_id
        )
    )
    source_time = effective_source_time(payload.source, payload.source_updated_at)
    if entry is None:
        entry = UserMedia(
            user_id=user.id,
            media_id=media_id,
            status=payload.status,
            is_favorite=payload.is_favorite,
            notes=payload.notes,
            source=payload.source,
            source_updated_at=source_time,
            progress_source=TrackingSource.MANUAL,
        )
        session.add(entry)
        await session.flush()
    else:
        _ensure_update_wins(entry.source, entry.source_updated_at, payload.source, source_time)
        entry.status = payload.status
        entry.is_favorite = payload.is_favorite
        entry.notes = payload.notes
        entry.source = payload.source
        entry.source_updated_at = source_time
    _apply_status_dates(entry, payload.status, source_time)
    await session.commit()
    if payload.progress is not None:
        await update_progress(
            session,
            user=user,
            media_id=media_id,
            payload=ProgressUpdate(
                page=payload.progress,
                source=payload.source,
                source_updated_at=payload.source_updated_at,
            ),
        )
    if payload.rating is not None:
        await upsert_rating(
            session,
            user=user,
            media_id=media_id,
            payload=RatingUpsert(
                score=payload.rating,
                source=payload.source,
                source_updated_at=payload.source_updated_at,
            ),
        )
    return await get_library_entry(session, user=user, media_id=media_id)


async def patch_library_entry(
    session: AsyncSession,
    *,
    user: User,
    media_id: UUID,
    payload: LibraryEntryPatch,
) -> LibraryEntry:
    entry = await get_library_entry(session, user=user, media_id=media_id)
    source_time = effective_source_time(payload.source, payload.source_updated_at)
    _ensure_update_wins(entry.source, entry.source_updated_at, payload.source, source_time)
    if payload.status is not None:
        entry.status = payload.status
        _apply_status_dates(entry, payload.status, source_time)
    if payload.is_favorite is not None:
        entry.is_favorite = payload.is_favorite
    if "notes" in payload.model_fields_set:
        entry.notes = payload.notes
    entry.source = payload.source
    entry.source_updated_at = source_time
    await session.commit()
    return await get_library_entry(session, user=user, media_id=media_id)


async def update_progress(
    session: AsyncSession,
    *,
    user: User,
    media_id: UUID,
    payload: ProgressUpdate,
) -> tuple[LibraryEntry, UserProgress]:
    entry = await get_library_entry(session, user=user, media_id=media_id)
    source_time = effective_source_time(payload.source, payload.source_updated_at)
    conflict_reason = _progress_conflict(entry, payload.source, source_time)
    value, unit, total = _legacy_progress_value(entry, payload)
    event = UserProgress(
        user_media_id=entry.id,
        value=value,
        total=total,
        unit=unit,
        note=payload.note,
        occurred_at=utc_now(),
        source=payload.source,
        source_updated_at=source_time,
        applied=conflict_reason is None,
        conflict_reason=conflict_reason,
        episode=payload.episode,
        chapter=payload.chapter,
        volume=payload.volume,
        track=payload.track,
        page=payload.page,
        percentage=payload.percentage,
    )
    session.add(event)
    if conflict_reason is None:
        for field in ("episode", "chapter", "volume", "track", "page", "percentage"):
            value = getattr(payload, field)
            if value is not None:
                setattr(entry, f"current_{field}" if field != "percentage" else field, value)
        entry.progress_source = payload.source
        entry.progress_updated_at = source_time
        if payload.status is not None:
            entry.status = payload.status
            entry.source = payload.source
            entry.source_updated_at = source_time
            _apply_status_dates(entry, payload.status, source_time)
    await session.commit()
    await session.refresh(event)
    return await get_library_entry(session, user=user, media_id=media_id), event


async def list_progress_history(
    session: AsyncSession, *, user: User, media_id: UUID, limit: int, offset: int
) -> list[UserProgress]:
    entry = await get_library_entry(session, user=user, media_id=media_id)
    return list(
        await session.scalars(
            select(UserProgress)
            .where(UserProgress.user_media_id == entry.id)
            .order_by(UserProgress.occurred_at.desc())
            .limit(limit)
            .offset(offset)
        )
    )


async def upsert_rating(
    session: AsyncSession, *, user: User, media_id: UUID, payload: RatingUpsert
) -> UserRating:
    await _require_media(session, media_id)
    source_time = effective_source_time(payload.source, payload.source_updated_at)
    rating = await session.scalar(
        select(UserRating).where(UserRating.user_id == user.id, UserRating.media_id == media_id)
    )
    if rating is None:
        rating = UserRating(
            user_id=user.id,
            media_id=media_id,
            score=payload.score,
            source=payload.source,
            source_updated_at=source_time,
        )
        session.add(rating)
    else:
        _ensure_update_wins(rating.source, rating.source_updated_at, payload.source, source_time)
        rating.score = payload.score
        rating.source = payload.source
        rating.source_updated_at = source_time
    await session.commit()
    await session.refresh(rating)
    return rating


async def get_rating(session: AsyncSession, *, user: User, media_id: UUID) -> UserRating:
    rating = await session.scalar(
        select(UserRating).where(UserRating.user_id == user.id, UserRating.media_id == media_id)
    )
    if rating is None:
        raise NotFoundError("Rating not found")
    return rating


async def delete_rating(session: AsyncSession, *, user: User, media_id: UUID) -> None:
    rating = await session.scalar(
        select(UserRating).where(UserRating.user_id == user.id, UserRating.media_id == media_id)
    )
    if rating is None:
        raise NotFoundError("Rating not found")
    await session.delete(rating)
    await session.commit()


async def get_review(session: AsyncSession, *, user: User, media_id: UUID) -> UserReview:
    review = await session.scalar(
        select(UserReview).where(UserReview.user_id == user.id, UserReview.media_id == media_id)
    )
    if review is None:
        raise NotFoundError("Review not found")
    return review


async def upsert_review(
    session: AsyncSession, *, user: User, media_id: UUID, payload: ReviewUpsert
) -> UserReview:
    await _require_media(session, media_id)
    source_time = effective_source_time(payload.source, payload.source_updated_at)
    review = await session.scalar(
        select(UserReview).where(UserReview.user_id == user.id, UserReview.media_id == media_id)
    )
    if review is None:
        review = UserReview(user_id=user.id, media_id=media_id)
        session.add(review)
    else:
        _ensure_update_wins(review.source, review.source_updated_at, payload.source, source_time)
    review.title = payload.title
    review.body = payload.body
    review.contains_spoilers = payload.contains_spoilers
    review.is_public = payload.is_public
    review.published_at = source_time if payload.is_public else None
    review.source = payload.source
    review.source_updated_at = source_time
    await session.commit()
    await session.refresh(review)
    return review


async def delete_review(session: AsyncSession, *, user: User, media_id: UUID) -> None:
    review = await get_review(session, user=user, media_id=media_id)
    await session.delete(review)
    await session.commit()


async def list_user_lists(session: AsyncSession, *, user: User) -> list[UserList]:
    return list(
        (await session.scalars(select(UserList).options(*LIST_OPTIONS).where(UserList.user_id == user.id).order_by(UserList.name))).unique().all()
    )


async def create_user_list(
    session: AsyncSession, *, user: User, payload: UserListCreate
) -> UserList:
    value = UserList(user_id=user.id, **payload.model_dump())
    session.add(value)
    await _commit_integrity(session, "A list with this name already exists")
    return await get_user_list(session, user=user, list_id=value.id)


async def get_user_list(session: AsyncSession, *, user: User, list_id: UUID) -> UserList:
    value = await session.scalar(
        select(UserList).options(*LIST_OPTIONS).where(UserList.id == list_id, UserList.user_id == user.id)
    )
    if value is None:
        raise NotFoundError("List not found")
    return value


async def update_user_list(
    session: AsyncSession, *, user: User, list_id: UUID, payload: UserListUpdate
) -> UserList:
    value = await get_user_list(session, user=user, list_id=list_id)
    for field, field_value in payload.model_dump(exclude_unset=True).items():
        setattr(value, field, field_value)
    await _commit_integrity(session, "A list with this name already exists")
    return await get_user_list(session, user=user, list_id=list_id)


async def delete_user_list(session: AsyncSession, *, user: User, list_id: UUID) -> None:
    value = await get_user_list(session, user=user, list_id=list_id)
    await session.delete(value)
    await session.commit()


async def add_list_item(
    session: AsyncSession, *, user: User, list_id: UUID, payload: UserListItemCreate
) -> UserList:
    value = await get_user_list(session, user=user, list_id=list_id)
    await _require_media(session, payload.media_id)
    value.items.append(UserListItem(**payload.model_dump()))
    await _commit_integrity(session, "Media is already in this list")
    return await get_user_list(session, user=user, list_id=list_id)


async def update_list_item(
    session: AsyncSession,
    *,
    user: User,
    list_id: UUID,
    media_id: UUID,
    payload: UserListItemUpdate,
) -> UserList:
    value = await get_user_list(session, user=user, list_id=list_id)
    item = next((item for item in value.items if item.media_id == media_id), None)
    if item is None:
        raise NotFoundError("List item not found")
    for field, field_value in payload.model_dump(exclude_unset=True).items():
        setattr(item, field, field_value)
    await session.commit()
    return await get_user_list(session, user=user, list_id=list_id)


async def delete_list_item(
    session: AsyncSession, *, user: User, list_id: UUID, media_id: UUID
) -> None:
    value = await get_user_list(session, user=user, list_id=list_id)
    item = next((item for item in value.items if item.media_id == media_id), None)
    if item is None:
        raise NotFoundError("List item not found")
    await session.delete(item)
    await session.commit()


async def delete_library_entry(session: AsyncSession, *, user: User, media_id: UUID) -> None:
    entry = await get_library_entry(session, user=user, media_id=media_id)
    await session.delete(entry)
    await session.commit()


def _ensure_update_wins(
    current_source: TrackingSource,
    current_time: datetime,
    incoming_source: TrackingSource,
    incoming_time: datetime,
) -> None:
    current_time = as_utc(current_time)
    if incoming_time < current_time or (
        incoming_time == current_time
        and current_source == TrackingSource.MANUAL
        and incoming_source != TrackingSource.MANUAL
    ):
        raise ConflictError("A newer manual or external value already exists")


def _progress_conflict(
    entry: UserMedia, incoming_source: TrackingSource, incoming_time: datetime
) -> str | None:
    if entry.progress_updated_at is None:
        return None
    current_time = as_utc(entry.progress_updated_at)
    if incoming_time < current_time:
        return "older_than_current_progress"
    if (
        incoming_time == current_time
        and entry.progress_source == TrackingSource.MANUAL
        and incoming_source != TrackingSource.MANUAL
    ):
        return "manual_progress_has_priority"
    return None


def _apply_status_dates(entry: UserMedia, status: TrackingStatus, changed_at: datetime) -> None:
    if status in {TrackingStatus.IN_PROGRESS, TrackingStatus.REWATCHING}:
        entry.started_at = entry.started_at or changed_at
        entry.completed_at = None
    elif status == TrackingStatus.COMPLETED:
        entry.started_at = entry.started_at or changed_at
        entry.completed_at = changed_at
    elif entry.status != TrackingStatus.COMPLETED:
        entry.completed_at = None


def _legacy_progress_value(
    entry: UserMedia, payload: ProgressUpdate
) -> tuple[float, ProgressUnit, float | None]:
    dimensions = (
        (payload.episode, ProgressUnit.EPISODE, None),
        (payload.chapter, ProgressUnit.CHAPTER, None),
        (payload.volume, ProgressUnit.VOLUME, None),
        (payload.track, ProgressUnit.TRACK, None),
        (payload.page, ProgressUnit.PAGE, None),
        (payload.percentage, ProgressUnit.PERCENT, 100.0),
    )
    return next(
        ((float(value), unit, total) for value, unit, total in dimensions if value is not None),
        (float(entry.progress), ProgressUnit.ITEM, None),
    )


async def _require_media(session: AsyncSession, media_id: UUID) -> None:
    if await session.get(Media, media_id) is None:
        raise NotFoundError("Media item not found")


async def _commit_integrity(session: AsyncSession, message: str) -> None:
    try:
        await session.commit()
    except IntegrityError:
        await session.rollback()
        raise ConflictError(message) from None
