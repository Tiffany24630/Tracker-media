from pydantic import Field

from app.providers.base import ProviderContent, ProviderUnit
from app.schemas.common import ApiModel


class ProviderInfo(ApiModel):
    name: str
    configured: bool


class ProviderFailure(ApiModel):
    provider: str
    code: str
    message: str


class GlobalSearchResponse(ApiModel):
    results: list[ProviderContent]
    errors: list[ProviderFailure] = Field(default_factory=list)


class UnifiedProviderDetail(ApiModel):
    content: ProviderContent
    units: list[ProviderUnit] = Field(default_factory=list)
