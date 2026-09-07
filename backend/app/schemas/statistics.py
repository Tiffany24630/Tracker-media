from datetime import date
from typing import Literal
from uuid import UUID

from pydantic import Field

from app.models.enums import MediaType, TrackingStatus
from app.schemas.common import ApiModel


class StatisticsCounts(ApiModel):
    movies_watched: int = 0
    series_completed: int = 0
    anime_completed: int = 0
    books_completed: int = 0
    albums_completed: int = 0
    tracks_completed: int = 0
    episodes_watched: float = 0
    chapters_read: float = 0
    average_rating: float | None = None
    estimated_consumed_seconds: int = 0


class StatisticsStatus(ApiModel):
    planned: int = 0
    in_progress: int = 0
    completed: int = 0
    paused: int = 0
    dropped: int = 0
    rewatching: int = 0


class GenreConsumption(ApiModel):
    genre: str
    count: int


class CurrentProgressItem(ApiModel):
    media_id: UUID
    title: str
    media_type: MediaType
    status: TrackingStatus
    progress: float = Field(ge=0)
    updated_at: str | None = None


class StatisticsSummary(ApiModel):
    counts: StatisticsCounts
    statuses: StatisticsStatus
    top_genres: list[GenreConsumption]
    currently_in_progress: list[CurrentProgressItem]


class ActivityPoint(ApiModel):
    period: str
    date: date
    events: int
    completed_progress: int


class StatisticsActivity(ApiModel):
    period: Literal["day", "week", "month", "year"]
    points: list[ActivityPoint]
