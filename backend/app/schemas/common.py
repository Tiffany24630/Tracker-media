from typing import Any

from pydantic import BaseModel, ConfigDict, Field


class ApiModel(BaseModel):
    model_config = ConfigDict(from_attributes=True, populate_by_name=True)


class Message(ApiModel):
    detail: str


class ErrorDetail(ApiModel):
    code: str
    message: str
    details: Any = None
    request_id: str


class ErrorResponse(ApiModel):
    error: ErrorDetail


class HealthResponse(ApiModel):
    status: str
    checks: dict[str, str] = Field(default_factory=dict)
