from datetime import datetime
from typing import Any, Literal
from uuid import UUID

from app.schemas.common import ApiModel


class MediaMatchSuggestion(ApiModel):
    media_id: UUID
    title: str
    confidence: float
    confidence_level: Literal["high", "medium", "low"]
    auto_link_eligible: bool
    evidence: dict[str, Any]


class MediaMatchDecisionCreate(ApiModel):
    media_id: UUID
    decision: Literal["confirm", "reject"]


class MediaMatchDecisionRead(ApiModel):
    id: UUID
    provider: str
    external_id: str
    candidate_media_id: UUID
    status: Literal["confirmed", "rejected"]
    confidence: float
    confidence_level: Literal["high", "medium", "low"]
    evidence: dict[str, Any]
    reviewed_by_user_id: UUID | None
    reviewed_at: datetime | None
