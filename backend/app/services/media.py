from uuid import UUID

from sqlalchemy import or_, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.core.exceptions import ConflictError, NotFoundError
from app.models.enums import MediaTitleType, MediaType
from app.models.media import Media
from app.models.media_details import (
    MediaExternalId,
    MediaGenre,
    MediaRelation,
    MediaTitle,
    MediaUnit,
)
from app.schemas.media import (
    ExternalIdCreate,
    MediaCreate,
    MediaRelationCreate,
    MediaTitleCreate,
    MediaUnitCreate,
    MediaUpdate,
)
from app.utils.matching import build_media_match_key, normalize_title

DETAIL_OPTIONS = (
    selectinload(Media.titles),
    selectinload(Media.genres),
    selectinload(Media.external_ids),
    selectinload(Media.units),
    selectinload(Media.outgoing_relations),
)


async def list_media(
    session: AsyncSession,
    *,
    query: str | None,
    media_type: MediaType | None,
    limit: int,
    offset: int,
) -> list[Media]:
    statement = (
        select(Media).options(*DETAIL_OPTIONS).order_by(Media.title).limit(limit).offset(offset)
    )
    if query:
        normalized = normalize_title(query)
        statement = statement.where(
            or_(
                Media.title.ilike(f"%{query}%"),
                Media.titles.any(MediaTitle.normalized_title.contains(normalized)),
            )
        )
    if media_type:
        statement = statement.where(Media.media_type == media_type)
    return list((await session.scalars(statement)).unique().all())


async def create_media(
    session: AsyncSession, payload: MediaCreate, *, allow_duplicate_match_key: bool = False
) -> Media:
    release_year = payload.release_year or (
        payload.release_date.year if payload.release_date else None
    )
    match_key = build_media_match_key(payload.media_type, payload.title, release_year)
    if not allow_duplicate_match_key and await session.scalar(
        select(Media.id).where(Media.match_key == match_key).limit(1)
    ):
        raise ConflictError("Media already exists")
    item = Media(
        media_type=payload.media_type,
        title=payload.title.strip(),
        match_key=match_key,
        description=payload.description,
        release_year=release_year,
        release_date=payload.release_date,
        status=payload.status,
        original_language=payload.original_language,
        languages=sorted(set(payload.languages)),
        cover_url=str(payload.cover_url) if payload.cover_url else None,
        metadata_=payload.metadata,
    )
    item.titles.append(
        MediaTitle(
            title=item.title,
            normalized_title=normalize_title(item.title),
            language_code=payload.original_language,
            title_type=MediaTitleType.PRIMARY,
            is_preferred=True,
        )
    )
    if payload.original_title and normalize_title(payload.original_title) != normalize_title(
        item.title
    ):
        item.titles.append(
            MediaTitle(
                title=payload.original_title,
                normalized_title=normalize_title(payload.original_title),
                language_code=payload.original_language,
                title_type=MediaTitleType.ORIGINAL,
                is_preferred=False,
            )
        )
    for alias in payload.aliases:
        item.titles.append(title_from_payload(alias))
    external_ids = list(payload.external_ids)
    if payload.provider and payload.provider_id:
        external_ids.append(
            ExternalIdCreate(provider=payload.provider, external_id=payload.provider_id)
        )
    for external_id in external_ids:
        item.external_ids.append(external_id_from_payload(external_id))
    for genre in sorted(set(payload.genres)):
        item.genres.append(
            MediaGenre(name=genre.strip(), slug=normalize_title(genre).replace(" ", "-"))
        )
    session.add(item)
    try:
        await session.commit()
    except IntegrityError:
        await session.rollback()
        raise ConflictError("Media already exists or contains a duplicated external ID") from None
    return await get_media(session, item.id)


async def get_media(session: AsyncSession, media_id: UUID) -> Media:
    item = await session.scalar(select(Media).options(*DETAIL_OPTIONS).where(Media.id == media_id))
    if item is None:
        raise NotFoundError("Media not found")
    return item


async def update_media(session: AsyncSession, item: Media, payload: MediaUpdate) -> Media:
    changes = payload.model_dump(exclude_unset=True)
    metadata = changes.pop("metadata", None)
    if "cover_url" in changes and changes["cover_url"] is not None:
        changes["cover_url"] = str(changes["cover_url"])
    for field, value in changes.items():
        setattr(item, field, value)
    if "title" in changes:
        primary_title = next(
            (title for title in item.titles if title.title_type == MediaTitleType.PRIMARY), None
        )
        if primary_title is not None:
            primary_title.title = item.title
            primary_title.normalized_title = normalize_title(item.title)
    if metadata is not None:
        item.metadata_ = metadata
    if payload.release_date is not None:
        item.release_year = payload.release_date.year
    item.match_key = build_media_match_key(item.media_type, item.title, item.release_year)
    try:
        await session.commit()
    except IntegrityError:
        await session.rollback()
        raise ConflictError("The update would duplicate another media work") from None
    return await get_media(session, item.id)


async def delete_media(session: AsyncSession, item: Media) -> None:
    await session.delete(item)
    await session.commit()


def title_from_payload(payload: MediaTitleCreate) -> MediaTitle:
    return MediaTitle(
        title=payload.title.strip(),
        normalized_title=normalize_title(payload.title),
        language_code=payload.language_code,
        title_type=payload.title_type,
        is_preferred=payload.is_preferred,
    )


def external_id_from_payload(payload: ExternalIdCreate) -> MediaExternalId:
    return MediaExternalId(
        provider=payload.provider.strip().lower(),
        external_id=payload.external_id.strip(),
        url=str(payload.url) if payload.url else None,
    )


async def add_title(session: AsyncSession, item: Media, payload: MediaTitleCreate) -> MediaTitle:
    title = title_from_payload(payload)
    item.titles.append(title)
    await commit_subresource(session, "Title already exists for this media")
    await session.refresh(title)
    return title


async def add_external_id(
    session: AsyncSession, item: Media, payload: ExternalIdCreate
) -> MediaExternalId:
    external_id = external_id_from_payload(payload)
    item.external_ids.append(external_id)
    await commit_subresource(session, "External ID is already assigned")
    await session.refresh(external_id)
    return external_id


async def add_unit(session: AsyncSession, item: Media, payload: MediaUnitCreate) -> MediaUnit:
    if payload.parent_id:
        parent = await session.get(MediaUnit, payload.parent_id)
        if parent is None or parent.media_id != item.id:
            raise NotFoundError("Parent unit does not belong to this media")
    unit = MediaUnit(
        media_id=item.id,
        **payload.model_dump(exclude={"metadata"}),
        metadata_=payload.metadata,
    )
    session.add(unit)
    await commit_subresource(session, "Media unit already exists")
    await session.refresh(unit)
    return unit


async def add_relation(
    session: AsyncSession, item: Media, payload: MediaRelationCreate
) -> MediaRelation:
    if payload.target_media_id == item.id:
        raise ConflictError("A media work cannot relate to itself")
    if await session.get(Media, payload.target_media_id) is None:
        raise NotFoundError("Target media not found")
    relation = MediaRelation(
        source_media_id=item.id,
        target_media_id=payload.target_media_id,
        relation_type=payload.relation_type,
        metadata_=payload.metadata,
    )
    session.add(relation)
    await commit_subresource(session, "Media relation already exists")
    await session.refresh(relation)
    return relation


async def commit_subresource(session: AsyncSession, conflict_message: str) -> None:
    try:
        await session.commit()
    except IntegrityError:
        await session.rollback()
        raise ConflictError(conflict_message) from None
