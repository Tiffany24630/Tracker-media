from uuid import UUID

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.exceptions import NotFoundError
from app.models.enums import MediaType
from app.models.media import MediaItem
from app.schemas.media import MediaCreate


async def list_media(
    session: AsyncSession,
    *,
    query: str | None,
    media_type: MediaType | None,
    limit: int,
    offset: int,
) -> list[MediaItem]:
    statement = select(MediaItem).order_by(MediaItem.title).limit(limit).offset(offset)
    if query:
        statement = statement.where(MediaItem.title.ilike(f"%{query}%"))
    if media_type:
        statement = statement.where(MediaItem.media_type == media_type)
    return list((await session.scalars(statement)).all())


async def create_media(session: AsyncSession, payload: MediaCreate) -> MediaItem:
    data = payload.model_dump(mode="json", exclude={"metadata"})
    item = MediaItem(**data, metadata_=payload.metadata)
    session.add(item)
    await session.commit()
    await session.refresh(item)
    return item


async def get_media(session: AsyncSession, media_id: UUID) -> MediaItem:
    item = await session.get(MediaItem, media_id)
    if item is None:
        raise NotFoundError("Media item not found")
    return item
