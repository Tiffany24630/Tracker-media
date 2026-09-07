from datetime import UTC, datetime
from uuid import UUID

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.core.exceptions import NotFoundError
from app.models.enums import ProgressUnit, TrackingStatus
from app.models.library_entry import LibraryEntry, UserMedia
from app.models.media import Media
from app.models.tracking import UserProgress, UserRating
from app.models.user import User
from app.schemas.library import LibraryEntryUpsert

LIBRARY_OPTIONS = (
    selectinload(UserMedia.media).selectinload(Media.titles),
    selectinload(UserMedia.media).selectinload(Media.external_ids),
    selectinload(UserMedia.progress_entries),
    selectinload(UserMedia.media_rating),
)


async def list_library(
    session: AsyncSession,
    *,
    user: User,
    entry_status: TrackingStatus | None,
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
    return list((await session.scalars(statement)).all())


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
    if entry is None:
        entry = UserMedia(
            user_id=user.id,
            media_id=media_id,
            status=payload.status,
            notes=payload.notes,
        )
        session.add(entry)
        await session.flush()
    else:
        entry.status = payload.status
        entry.notes = payload.notes
    session.add(
        UserProgress(
            user_media_id=entry.id,
            value=payload.progress,
            unit=ProgressUnit.ITEM,
            occurred_at=datetime.now(UTC),
        )
    )
    rating = await session.scalar(
        select(UserRating).where(UserRating.user_id == user.id, UserRating.media_id == media_id)
    )
    if payload.rating is None and rating is not None:
        await session.delete(rating)
    elif payload.rating is not None:
        if rating is None:
            session.add(UserRating(user_id=user.id, media_id=media_id, score=payload.rating))
        else:
            rating.score = payload.rating
    await session.commit()
    statement = (
        select(LibraryEntry)
        .options(*LIBRARY_OPTIONS)
        .where(LibraryEntry.user_id == user.id, LibraryEntry.media_id == media_id)
    )
    saved_entry = await session.scalar(statement)
    if saved_entry is None:
        raise RuntimeError("Library entry disappeared after commit")
    return saved_entry


async def delete_library_entry(session: AsyncSession, *, user: User, media_id: UUID) -> None:
    entry = await session.scalar(
        select(LibraryEntry).where(
            LibraryEntry.user_id == user.id, LibraryEntry.media_id == media_id
        )
    )
    if entry is None:
        raise NotFoundError("Library entry not found")
    await session.delete(entry)
    await session.commit()
