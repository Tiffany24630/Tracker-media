from datetime import datetime
from typing import Any
from uuid import UUID

from pydantic import Field, HttpUrl

from app.models.enums import MediaType
from app.models.media import MediaItem
from app.schemas.common import ApiModel


class MediaCreate(ApiModel):
    media_type: MediaType
    title: str = Field(min_length=1, max_length=500)
    original_title: str | None = Field(default=None, max_length=500)
    description: str | None = None
    release_year: int | None = Field(default=None, ge=0, le=9999)
    cover_url: HttpUrl | None = None
    provider: str | None = Field(default=None, max_length=80)
    provider_id: str | None = Field(default=None, max_length=255)
    metadata: dict[str, Any] = Field(default_factory=dict)


class MediaRead(MediaCreate):
    id: UUID
    cover_url: str | None = None
    created_at: datetime
    updated_at: datetime

    metadata: dict[str, Any] = Field(default_factory=dict, validation_alias="metadata_")

    @classmethod
    def from_orm_item(cls, item: MediaItem) -> "MediaRead":
        return cls.model_validate(item)
