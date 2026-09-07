from uuid import UUID

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.core.exceptions import NotFoundError
from app.models.enums import TrackingStatus
from app.models.library_entry import LibraryEntry
from app.models.media import MediaItem
from app.models.user import User
from app.schemas.library import LibraryEntryUpsert


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
        .options(selectinload(LibraryEntry.media))
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
    if await session.get(MediaItem, media_id) is None:
        raise NotFoundError("Media item not found")
    entry = await session.scalar(
        select(LibraryEntry).where(
            LibraryEntry.user_id == user.id, LibraryEntry.media_id == media_id
        )
    )
    if entry is None:
        entry = LibraryEntry(user_id=user.id, media_id=media_id, **payload.model_dump())
        session.add(entry)
    else:
        for field, value in payload.model_dump().items():
            setattr(entry, field, value)
    await session.commit()
    statement = (
        select(LibraryEntry)
        .options(selectinload(LibraryEntry.media))
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
