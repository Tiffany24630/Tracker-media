from datetime import datetime
from typing import Any
from uuid import UUID

from pydantic import Field

from app.models.enums import IntegrationCapability, IntegrationStatus, SyncJobStatus
from app.schemas.common import ApiModel


class IntegrationProviderRead(ApiModel):
    name: str
    display_name: str
    description: str
    auth_type: str
    capabilities: list[IntegrationCapability]


class UserIntegrationRead(ApiModel):
    id: UUID
    provider: str
    external_user_id: str
    status: IntegrationStatus
    scopes: list[str] = Field(default_factory=list)
    last_synced_at: datetime | None = None
    capabilities: list[IntegrationCapability] = Field(default_factory=list)
    created_at: datetime
    updated_at: datetime


class OAuthAuthorizeRead(ApiModel):
    authorization_url: str | None = None
    state: str | None = None
    message: str | None = None
    supports_oauth: bool = True
    auth_type: str = "oauth"


class OAuthCallbackPayload(ApiModel):
    code: str = Field(min_length=1)
    redirect_uri: str | None = None
    state: str | None = None


class ManualImportPayload(ApiModel):
    imported_items: list[dict[str, Any]] = Field(default_factory=list)


class SyncJobRead(ApiModel):
    id: UUID
    user_id: UUID
    user_integration_id: UUID | None
    job_type: str
    status: SyncJobStatus
    payload: dict[str, Any] = Field(default_factory=dict)
    result: dict[str, Any] | None = None
    error: str | None = None
    attempts: int
    started_at: datetime | None = None
    finished_at: datetime | None = None
    created_at: datetime
