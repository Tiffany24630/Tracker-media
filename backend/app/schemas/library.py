from datetime import datetime
from uuid import UUID

from pydantic import Field

from app.models.enums import TrackingStatus
from app.schemas.common import ApiModel
from app.schemas.media import MediaRead


class LibraryEntryUpsert(ApiModel):
    status: TrackingStatus
    progress: int = Field(default=0, ge=0)
    rating: float | None = Field(default=None, ge=0, le=10)
    notes: str | None = None


class LibraryEntryRead(LibraryEntryUpsert):
    id: UUID
    media_id: UUID
    media: MediaRead
    created_at: datetime
    updated_at: datetime
