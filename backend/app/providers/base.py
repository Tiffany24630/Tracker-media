from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any

from app.models.enums import MediaType


@dataclass(frozen=True, slots=True)
class ProviderMedia:
    provider: str
    provider_id: str
    media_type: MediaType
    title: str
    original_title: str | None = None
    description: str | None = None
    release_year: int | None = None
    cover_url: str | None = None
    metadata: dict[str, Any] = field(default_factory=dict)


class MediaProvider(ABC):
    """Boundary for legal, documented external catalog APIs."""

    name: str

    @abstractmethod
    async def search(
        self, query: str, media_type: MediaType | None = None, limit: int = 20
    ) -> list[ProviderMedia]:
        raise NotImplementedError

    @abstractmethod
    async def get(self, provider_id: str) -> ProviderMedia | None:
        raise NotImplementedError
