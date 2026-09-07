from abc import ABC, abstractmethod
from collections.abc import Mapping
from datetime import date
from typing import Any

from pydantic import BaseModel, ConfigDict, Field

from app.models.enums import MediaStatus, MediaTitleType, MediaType, MediaUnitType
from app.schemas.media import ExternalIdCreate, MediaCreate, MediaTitleCreate


class ProviderFilters(BaseModel):
    model_config = ConfigDict(extra="forbid")
    media_type: MediaType | None = None
    language: str | None = Field(default=None, max_length=16)
    year: int | None = Field(default=None, ge=0, le=9999)
    limit: int = Field(default=20, ge=1, le=50)


class ProviderTitle(BaseModel):
    title: str
    language_code: str | None = None
    title_type: MediaTitleType = MediaTitleType.ALTERNATIVE


class ProviderUnit(BaseModel):
    unit_type: MediaUnitType
    number: float
    title: str | None = None
    description: str | None = None
    release_date: date | None = None
    duration_seconds: int | None = None
    metadata: dict[str, Any] = Field(default_factory=dict)


class ProviderContent(BaseModel):
    source: str
    external_id: str
    media_type: MediaType
    title: str
    original_title: str | None = None
    alternative_titles: list[ProviderTitle] = Field(default_factory=list)
    description: str | None = None
    release_date: date | None = None
    release_year: int | None = None
    status: MediaStatus = MediaStatus.UNKNOWN
    original_language: str | None = None
    languages: list[str] = Field(default_factory=list)
    genres: list[str] = Field(default_factory=list)
    cover_url: str | None = None
    external_url: str | None = None
    identifiers: dict[str, list[str]] = Field(default_factory=dict)
    metadata: dict[str, Any] = Field(default_factory=dict)


class ContentProvider(ABC):
    """Stable boundary around a documented external content API."""

    name: str
    configured: bool = True

    @abstractmethod
    async def search(self, query: str, filters: ProviderFilters) -> list[ProviderContent]:
        raise NotImplementedError

    @abstractmethod
    async def get_details(self, external_id: str) -> ProviderContent:
        raise NotImplementedError

    @abstractmethod
    async def get_units(self, external_id: str) -> list[ProviderUnit]:
        raise NotImplementedError

    @abstractmethod
    def normalize(self, payload: Mapping[str, Any], **context: Any) -> ProviderContent:
        raise NotImplementedError

    def map_to_internal_model(self, content: ProviderContent) -> MediaCreate:
        aliases = [
            MediaTitleCreate(
                title=value.title,
                language_code=value.language_code,
                title_type=value.title_type,
            )
            for value in content.alternative_titles
            if value.title.strip() and value.title.strip() != content.title.strip()
        ]
        external_ids = [
            ExternalIdCreate(
                provider=self.name,
                external_id=content.external_id,
                url=content.external_url,
            )
        ]
        external_ids.extend(
            ExternalIdCreate(provider=source, external_id=value)
            for source, values in content.identifiers.items()
            for value in values
            if value
        )
        return MediaCreate(
            media_type=content.media_type,
            title=content.title,
            original_title=content.original_title,
            aliases=aliases,
            description=content.description,
            release_year=content.release_year,
            release_date=content.release_date,
            status=content.status,
            original_language=content.original_language,
            languages=content.languages,
            cover_url=content.cover_url,
            external_ids=external_ids,
            genres=content.genres,
            metadata={**content.metadata, "source": self.name},
        )


MediaProvider = ContentProvider
ProviderMedia = ProviderContent
