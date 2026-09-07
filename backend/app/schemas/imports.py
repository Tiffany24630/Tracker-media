from datetime import datetime
from typing import Any
from uuid import UUID

from pydantic import Field

from app.models.enums import MediaType, TrackingStatus
from app.schemas.common import ApiModel


class ImportAdapterRead(ApiModel):
    name: str
    display_name: str
    extensions: list[str]


class ImportMatchRead(ApiModel):
    media_id: UUID
    title: str
    confidence: float
    confidence_level: str
    auto_link_eligible: bool
    evidence: dict[str, Any] = Field(default_factory=dict)


class ImportPreviewItemRead(ApiModel):
    row: int
    title: str
    media_type: MediaType
    status: TrackingStatus
    rating: float | None = None
    source_updated_at: datetime
    matches: list[ImportMatchRead] = Field(default_factory=list)
    requires_review: bool = False
    proposed_action: str
    source_details: dict[str, Any] = Field(default_factory=dict)


class ImportBatchRead(ApiModel):
    id: UUID
    adapter: str
    filename: str
    status: str
    row_count: int
    preview: list[ImportPreviewItemRead] = Field(default_factory=list)
    source_details: dict[str, Any] = Field(default_factory=dict)
    result: dict[str, Any] | None = None
    error: str | None = None
    confirmed_at: datetime | None = None
    created_at: datetime
    updated_at: datetime


class ImportDecision(ApiModel):
    row: int = Field(ge=1)
    media_id: UUID | None = None
    skip: bool = False


class ImportConfirmPayload(ApiModel):
    decisions: list[ImportDecision] = Field(default_factory=list)
