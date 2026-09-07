from datetime import UTC, datetime, timedelta
from typing import Self
from uuid import UUID

from pydantic import Field, model_validator

from app.models.enums import ProgressUnit, TrackingSource, TrackingStatus
from app.schemas.common import ApiModel
from app.schemas.media import MediaRead


class LibraryEntryUpsert(ApiModel):
    status: TrackingStatus = TrackingStatus.PLANNED
    is_favorite: bool = False
    notes: str | None = None
    source: TrackingSource = TrackingSource.MANUAL
    source_updated_at: datetime | None = None
    started_at: datetime | None = None
    completed_at: datetime | None = None
    episode: float | None = Field(default=None, ge=0)
    chapter: float | None = Field(default=None, ge=0)
    volume: float | None = Field(default=None, ge=0)
    track: float | None = Field(default=None, ge=0)
    page: int | None = Field(default=None, ge=0)
    percentage: float | None = Field(default=None, ge=0, le=100)
    progress: int | None = Field(default=None, ge=0)
    rating: float | None = Field(default=None, ge=0, le=10)

    @model_validator(mode="after")
    def validate_source(self) -> Self:
        _validate_source_timestamp(self.source, self.source_updated_at)
        return self


class LibraryEntryCreate(LibraryEntryUpsert):
    media_id: UUID


class LibraryEntryPatch(ApiModel):
    status: TrackingStatus | None = None
    is_favorite: bool | None = None
    notes: str | None = None
    source: TrackingSource = TrackingSource.MANUAL
    source_updated_at: datetime | None = None
    started_at: datetime | None = None
    completed_at: datetime | None = None
    episode: float | None = Field(default=None, ge=0)
    chapter: float | None = Field(default=None, ge=0)
    volume: float | None = Field(default=None, ge=0)
    track: float | None = Field(default=None, ge=0)
    page: int | None = Field(default=None, ge=0)
    percentage: float | None = Field(default=None, ge=0, le=100)
    progress: int | None = Field(default=None, ge=0)
    rating: float | None = Field(default=None, ge=0, le=10)

    @model_validator(mode="after")
    def require_change(self) -> Self:
        modifiable_fields = {
            "status",
            "is_favorite",
            "notes",
            "started_at",
            "completed_at",
            "episode",
            "chapter",
            "volume",
            "track",
            "page",
            "percentage",
            "progress",
            "rating",
        }
        if not modifiable_fields.intersection(self.model_fields_set):
            raise ValueError("At least one library field must be supplied")
        _validate_source_timestamp(self.source, self.source_updated_at)
        return self


class ProgressSnapshot(ApiModel):
    episode: float | None = None
    chapter: float | None = None
    volume: float | None = None
    track: float | None = None
    page: int | None = None
    percentage: float | None = None
    source: TrackingSource
    updated_at: datetime | None


class LibraryEntryRead(ApiModel):
    id: UUID
    media_id: UUID
    status: TrackingStatus
    is_favorite: bool
    notes: str | None
    source: TrackingSource
    source_updated_at: datetime
    started_at: datetime | None
    completed_at: datetime | None
    progress: int
    rating: float | None
    current_progress: ProgressSnapshot
    episode: float | None = None
    chapter: float | None = None
    volume: float | None = None
    track: float | None = None
    page: int | None = None
    percentage: float | None = None
    media: MediaRead
    created_at: datetime
    updated_at: datetime

    @classmethod
    def from_orm_entry(cls, entry) -> "LibraryEntryRead":
        return cls.model_validate(
            {
                "id": entry.id,
                "media_id": entry.media_id,
                "status": entry.status,
                "is_favorite": entry.is_favorite,
                "notes": entry.notes,
                "source": entry.source,
                "source_updated_at": entry.source_updated_at,
                "started_at": entry.started_at,
                "completed_at": entry.completed_at,
                "progress": entry.progress,
                "rating": entry.rating,
                "current_progress": {
                    "episode": entry.current_episode,
                    "chapter": entry.current_chapter,
                    "volume": entry.current_volume,
                    "track": entry.current_track,
                    "page": entry.current_page,
                    "percentage": entry.percentage,
                    "source": entry.progress_source,
                    "updated_at": entry.progress_updated_at,
                },
                "episode": entry.current_episode,
                "chapter": entry.current_chapter,
                "volume": entry.current_volume,
                "track": entry.current_track,
                "page": entry.current_page,
                "percentage": entry.percentage,
                "media": MediaRead.from_orm_item(entry.media),
                "created_at": entry.created_at,
                "updated_at": entry.updated_at,
            }
        )


class ProgressUpdate(ApiModel):
    episode: float | None = Field(default=None, ge=0)
    chapter: float | None = Field(default=None, ge=0)
    volume: float | None = Field(default=None, ge=0)
    track: float | None = Field(default=None, ge=0)
    page: int | None = Field(default=None, ge=0)
    percentage: float | None = Field(default=None, ge=0, le=100)
    status: TrackingStatus | None = None
    note: str | None = Field(default=None, max_length=500)
    source: TrackingSource = TrackingSource.MANUAL
    source_updated_at: datetime | None = None

    @model_validator(mode="after")
    def validate_progress(self) -> Self:
        dimensions = (
            self.episode,
            self.chapter,
            self.volume,
            self.track,
            self.page,
            self.percentage,
        )
        if all(value is None for value in dimensions) and self.status is None:
            raise ValueError("At least one progress field or status must be supplied")
        _validate_source_timestamp(self.source, self.source_updated_at)
        return self


class ProgressEventRead(ApiModel):
    id: UUID
    episode: float | None
    chapter: float | None
    volume: float | None
    track: float | None
    page: int | None
    percentage: float | None
    value: float | None = None
    unit: ProgressUnit | None = None
    source: TrackingSource
    source_updated_at: datetime
    applied: bool
    conflict_reason: str | None
    note: str | None
    occurred_at: datetime


class ProgressUpdateResult(ApiModel):
    applied: bool
    conflict_reason: str | None = None
    entry: LibraryEntryRead
    event: ProgressEventRead


class RatingUpsert(ApiModel):
    score: float = Field(ge=0, le=10)
    source: TrackingSource = TrackingSource.MANUAL
    source_updated_at: datetime | None = None

    @model_validator(mode="after")
    def validate_source(self) -> Self:
        _validate_source_timestamp(self.source, self.source_updated_at)
        return self


class RatingRead(RatingUpsert):
    id: UUID
    media_id: UUID
    created_at: datetime
    updated_at: datetime


class ReviewUpsert(ApiModel):
    title: str | None = Field(default=None, max_length=200)
    body: str = Field(min_length=1)
    contains_spoilers: bool = False
    is_public: bool = False
    source: TrackingSource = TrackingSource.MANUAL
    source_updated_at: datetime | None = None

    @model_validator(mode="after")
    def validate_source(self) -> Self:
        _validate_source_timestamp(self.source, self.source_updated_at)
        return self


class ReviewRead(ReviewUpsert):
    id: UUID
    media_id: UUID
    published_at: datetime | None
    created_at: datetime
    updated_at: datetime


class UserListCreate(ApiModel):
    name: str = Field(min_length=1, max_length=150)
    description: str | None = None
    is_public: bool = False


class UserListUpdate(ApiModel):
    name: str | None = Field(default=None, min_length=1, max_length=150)
    description: str | None = None
    is_public: bool | None = None


class UserListItemCreate(ApiModel):
    media_id: UUID
    position: int = Field(default=0, ge=0)
    notes: str | None = None


class UserListItemUpdate(ApiModel):
    position: int | None = Field(default=None, ge=0)
    notes: str | None = None


class UserListItemRead(ApiModel):
    id: UUID
    media_id: UUID
    position: int
    notes: str | None
    media: MediaRead
    created_at: datetime
    updated_at: datetime


class UserListRead(UserListCreate):
    id: UUID
    items: list[UserListItemRead]
    created_at: datetime
    updated_at: datetime


def _as_utc(value: datetime) -> datetime:
    return value.replace(tzinfo=UTC) if value.tzinfo is None else value.astimezone(UTC)


def _validate_source_timestamp(
    source: TrackingSource, source_updated_at: datetime | None
) -> None:
    if source != TrackingSource.MANUAL and source_updated_at is None:
        raise ValueError("External updates require source_updated_at")
    if source_updated_at and _as_utc(source_updated_at) > datetime.now(UTC) + timedelta(minutes=5):
        raise ValueError("source_updated_at cannot be more than five minutes in the future")
