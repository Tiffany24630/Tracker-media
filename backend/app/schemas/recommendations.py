from typing import Any
from uuid import UUID

from pydantic import Field

from app.models.enums import MediaType
from app.schemas.common import ApiModel


class RecommendationExplanation(ApiModel):
    signal: str
    contribution: float
    details: dict[str, Any] = Field(default_factory=dict)


class RecommendationRead(ApiModel):
    media_id: UUID
    title: str
    media_type: MediaType
    release_year: int | None = None
    score: float
    strategy: str
    explanations: list[RecommendationExplanation]


class RecommendationResponse(ApiModel):
    strategy: str
    recommendations: list[RecommendationRead]
