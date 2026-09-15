from datetime import date, datetime
from typing import Any
from uuid import UUID
from pydantic import BaseModel, ConfigDict, EmailStr, Field, HttpUrl
from app.models.enums import (
    MediaType,
    MediaStatus,
    MediaTitleType,
    MediaUnitType,
    TrackingStatus,
    TrackingSource,
)

class Register(BaseModel):
    email: EmailStr
    display_name: str = Field(min_length=1, max_length=100)
    password: str = Field(min_length=8, max_length=128)

class Login(BaseModel):
    email: EmailStr
    password: str

class Token(BaseModel):
    access_token: str
    token_type: str = 'bearer'

class UserOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    id: UUID | str
    email: EmailStr
    display_name: str

class ExternalIdIn(BaseModel):
    provider: str
    external_id: str
    url: HttpUrl | None = None

class MediaCreate(BaseModel):
    title: str = Field(min_length=1, max_length=500)
    media_type: MediaType
    description: str | None = None
    release_year: int | None = None
    release_date: date | None = None
    status: MediaStatus = MediaStatus.UNKNOWN
    original_language: str | None = None
    cover_url: HttpUrl | None = None
    genres: list[str] = []
    aliases: list[str] = []
    external_ids: list[ExternalIdIn] = []
    metadata: dict[str, Any] = {}

class MediaOut(BaseModel):
    id: UUID | str
    media_type: str
    title: str
    description: str | None = None
    release_year: int | None = None
    status: str
    original_language: str | None = None
    cover_url: str | None = None
    genres: list[str] = []
    external_ids: list[dict[str, Any]] = []
    metadata: dict[str, Any] = {}

class SearchResult(BaseModel):
    source: str
    external_id: str
    media_type: str
    title: str
    description: str | None = None
    release_year: int | None = None
    cover_url: str | None = None

class LibraryUpsert(BaseModel):
    status: TrackingStatus | str = TrackingStatus.PLANNED
    progress: float = Field(default=0, ge=0)
    total: float | None = Field(default=None, ge=0)
    rating: float | None = Field(default=None, ge=0, le=10)
    notes: str | None = None
    source: TrackingSource | str = TrackingSource.MANUAL

class LibraryOut(BaseModel):
    id: UUID | str
    media_id: UUID | str
    status: str
    progress: float
    total: float | None = None
    rating: float | None = None
    notes: str | None = None
    source: str
    media: MediaOut

class ListCreate(BaseModel):
    name: str = Field(min_length=1, max_length=150)
    description: str | None = None

class ProgressIn(BaseModel):
    value: float = Field(ge=0)
    unit: MediaUnitType | str = 'episode'
    source: TrackingSource | str = TrackingSource.MANUAL

__all__ = [
    'Register',
    'Login',
    'Token',
    'UserOut',
    'ExternalIdIn',
    'MediaCreate',
    'MediaOut',
    'SearchResult',
    'LibraryUpsert',
    'LibraryOut',
    'ListCreate',
    'ProgressIn',
]
