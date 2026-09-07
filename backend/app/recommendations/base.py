from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field

from app.models.media import Media
from app.schemas.recommendations import RecommendationRead


@dataclass(slots=True)
class RecommendationProfile:
    positive_types: dict[str, float] = field(default_factory=dict)
    positive_genres: dict[str, float] = field(default_factory=dict)
    positive_tags: dict[str, float] = field(default_factory=dict)
    negative_types: dict[str, float] = field(default_factory=dict)
    negative_genres: dict[str, float] = field(default_factory=dict)
    negative_tags: dict[str, float] = field(default_factory=dict)

    @property
    def positive(self) -> dict[str, dict[str, float]]:
        return {
            "types": self.positive_types,
            "genres": self.positive_genres,
            "tags": self.positive_tags,
        }

    @property
    def negative(self) -> dict[str, dict[str, float]]:
        return {
            "types": self.negative_types,
            "genres": self.negative_genres,
            "tags": self.negative_tags,
        }


class RecommendationStrategy(ABC):
    name: str

    @abstractmethod
    def build_profile(self, entries: list[tuple[object, Media]]) -> RecommendationProfile:
        raise NotImplementedError

    @abstractmethod
    def score(self, media: Media, profile: RecommendationProfile) -> RecommendationRead:
        raise NotImplementedError
