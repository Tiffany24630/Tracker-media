from datetime import date, datetime
from typing import Any, Self
from uuid import UUID

from pydantic import Field, HttpUrl, model_validator

from app.models.enums import MediaStatus, MediaTitleType, MediaType, MediaUnitType
from app.models.media import Media
from app.schemas.common import ApiModel


class ExternalIdCreate(ApiModel):
    provider: str = Field(min_length=1, max_length=80)
    external_id: str = Field(min_length=1, max_length=255)
    url: HttpUrl | None = None


class ExternalIdRead(ApiModel):
    id: UUID
    provider: str
    external_id: str
    url: str | None


class MediaTitleCreate(ApiModel):
    title: str = Field(min_length=1, max_length=500)
    language_code: str | None = Field(default=None, max_length=16)
    title_type: MediaTitleType = MediaTitleType.ALTERNATIVE
    is_preferred: bool = False


class MediaTitleRead(MediaTitleCreate):
    id: UUID
    normalized_title: str


class MediaUnitCreate(ApiModel):
    unit_type: MediaUnitType
    number: float = Field(ge=0)
    parent_id: UUID | None = None
    title: str | None = Field(default=None, max_length=500)
    description: str | None = None
    release_date: date | None = None
    duration_seconds: int | None = Field(default=None, ge=0)
    metadata: dict[str, Any] = Field(default_factory=dict)


class MediaUnitRead(MediaUnitCreate):
    id: UUID
    media_id: UUID
    metadata: dict[str, Any] = Field(default_factory=dict, validation_alias="metadata_")


class MediaRelationCreate(ApiModel):
    target_media_id: UUID
    relation_type: str = Field(min_length=1, max_length=50)
    metadata: dict[str, Any] = Field(default_factory=dict)


class MediaRelationRead(MediaRelationCreate):
    id: UUID
    source_media_id: UUID
    metadata: dict[str, Any] = Field(default_factory=dict, validation_alias="metadata_")


class MediaCreate(ApiModel):
    media_type: MediaType
    title: str = Field(min_length=1, max_length=500)
    original_title: str | None = Field(default=None, max_length=500)
    aliases: list[MediaTitleCreate] = Field(default_factory=list)
    description: str | None = None
    release_year: int | None = Field(default=None, ge=0, le=9999)
    release_date: date | None = None
    status: MediaStatus = MediaStatus.UNKNOWN
    original_language: str | None = Field(default=None, max_length=16)
    languages: list[str] = Field(default_factory=list)
    cover_url: HttpUrl | None = None
    provider: str | None = Field(default=None, max_length=80)
    provider_id: str | None = Field(default=None, max_length=255)
    external_ids: list[ExternalIdCreate] = Field(default_factory=list)
    genres: list[str] = Field(default_factory=list)
    metadata: dict[str, Any] = Field(default_factory=dict)

    @model_validator(mode="after")
    def validate_legacy_external_id(self) -> Self:
        if (self.provider is None) != (self.provider_id is None):
            raise ValueError("provider and provider_id must be supplied together")
        return self


class MediaUpdate(ApiModel):
    media_type: MediaType | None = None
    title: str | None = Field(default=None, min_length=1, max_length=500)
    description: str | None = None
    release_date: date | None = None
    status: MediaStatus | None = None
    original_language: str | None = Field(default=None, max_length=16)
    languages: list[str] | None = None
    cover_url: HttpUrl | None = None
    metadata: dict[str, Any] | None = None


class MediaRead(ApiModel):
    id: UUID
    media_type: MediaType
    title: str
    original_title: str | None
    description: str | None
    release_year: int | None
    release_date: date | None
    status: MediaStatus
    original_language: str | None
    languages: list[str]
    cover_url: str | None
    provider: str | None
    provider_id: str | None
    metadata: dict[str, Any] = Field(default_factory=dict, validation_alias="metadata_")
    created_at: datetime
    updated_at: datetime

    @classmethod
    def from_orm_item(cls, item: Media) -> "MediaRead":
        return cls.model_validate(item)


class MediaDetail(MediaRead):
    titles: list[MediaTitleRead]
    genres: list[str]
    external_ids: list[ExternalIdRead]
    units: list[MediaUnitRead]
    relations: list[MediaRelationRead]

    @classmethod
    def from_orm_item(cls, item: Media) -> "MediaDetail":
        base = MediaRead.from_orm_item(item).model_dump()
        return cls.model_validate(
            {
                **base,
                "titles": item.titles,
                "genres": [genre.name for genre in item.genres],
                "external_ids": item.external_ids,
                "units": item.units,
                "relations": item.outgoing_relations,
            }
        )
