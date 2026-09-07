from __future__ import annotations

from typing import Any

from app.models.enums import TrackingStatus
from app.models.media import Media
from app.recommendations.base import RecommendationProfile, RecommendationStrategy
from app.schemas.recommendations import RecommendationExplanation, RecommendationRead
from app.utils.matching import normalize_title


class ContentBasedStrategy(RecommendationStrategy):
    name = "content_based"

    def build_profile(self, entries: list[tuple[object, Media]]) -> RecommendationProfile:
        profile = RecommendationProfile()
        for entry, media in entries:
            weight = self._entry_weight(entry)
            target = profile.negative if weight < 0 else profile.positive
            _add(target["types"], _value(media.media_type), abs(weight))
            for genre in media.genres:
                _add(target["genres"], normalize_title(genre.name), abs(weight))
            for tag in _media_tags(media):
                _add(target["tags"], tag, abs(weight))
        return profile

    def score(self, media: Media, profile: RecommendationProfile) -> RecommendationRead:
        genres = {normalize_title(genre.name) for genre in media.genres}
        tags = set(_media_tags(media))
        media_type = _value(media.media_type)
        positive_type = _ratio(profile.positive_types.get(media_type, 0), profile.positive_types)
        positive_genres = _overlap_ratio(genres, profile.positive_genres)
        positive_tags = _overlap_ratio(tags, profile.positive_tags)
        negative = (
            _ratio(profile.negative_types.get(media_type, 0), profile.negative_types) * 0.35
            + _overlap_ratio(genres, profile.negative_genres) * 0.45
            + _overlap_ratio(tags, profile.negative_tags) * 0.20
        )
        contributions = {
            "type_affinity": positive_type * 0.25,
            "genre_affinity": positive_genres * 0.45,
            "tag_affinity": positive_tags * 0.20,
            "negative_preference_penalty": -negative * 0.20,
        }
        score = max(0.0, min(1.0, sum(contributions.values())))
        explanations = self._explanations(media, profile, contributions, genres, tags)
        return RecommendationRead(
            media_id=media.id,
            title=media.title,
            media_type=media.media_type,
            release_year=media.release_year,
            score=round(score, 4),
            strategy=self.name,
            explanations=explanations,
        )

    def _entry_weight(self, entry: object) -> float:
        status = getattr(entry, "status", None)
        if status == TrackingStatus.DROPPED:
            return -2.5
        weight = {
            TrackingStatus.COMPLETED: 3.0,
            TrackingStatus.IN_PROGRESS: 2.0,
            TrackingStatus.REWATCHING: 2.0,
            TrackingStatus.PLANNED: 0.5,
            TrackingStatus.PAUSED: 0.25,
        }.get(status, 0.25)
        if getattr(entry, "is_favorite", False):
            weight += 1.5
        rating = getattr(entry, "rating", None)
        if rating is not None:
            if rating >= 8:
                weight += 2.0
            elif rating <= 4:
                weight -= 1.5
        return weight

    def _explanations(
        self,
        media: Media,
        profile: RecommendationProfile,
        contributions: dict[str, float],
        genres: set[str],
        tags: set[str],
    ) -> list[RecommendationExplanation]:
        explanations: list[RecommendationExplanation] = []
        if contributions["type_affinity"]:
            explanations.append(
                RecommendationExplanation(
                    signal="type_affinity",
                    contribution=round(contributions["type_affinity"], 4),
                    details={"media_type": _value(media.media_type)},
                )
            )
        matching_genres = sorted(genres & profile.positive_genres.keys())
        if matching_genres:
            explanations.append(
                RecommendationExplanation(
                    signal="genre_affinity",
                    contribution=round(contributions["genre_affinity"], 4),
                    details={"genres": matching_genres},
                )
            )
        matching_tags = sorted(tags & profile.positive_tags.keys())
        if matching_tags:
            explanations.append(
                RecommendationExplanation(
                    signal="tag_affinity",
                    contribution=round(contributions["tag_affinity"], 4),
                    details={"tags": matching_tags},
                )
            )
        if contributions["negative_preference_penalty"]:
            explanations.append(
                RecommendationExplanation(
                    signal="negative_preference_penalty",
                    contribution=round(contributions["negative_preference_penalty"], 4),
                    details={
                        "genres": sorted(genres & profile.negative_genres.keys()),
                        "tags": sorted(tags & profile.negative_tags.keys()),
                    },
                )
            )
        if not explanations:
            explanations.append(
                RecommendationExplanation(
                    signal="catalog_fallback",
                    contribution=0.0,
                    details={"reason": "No matching user signals yet"},
                )
            )
        return explanations


def _media_tags(media: Media) -> set[str]:
    metadata = media.metadata_ if isinstance(media.metadata_, dict) else {}
    values: list[Any] = []
    for key in ("tags", "tag", "keywords"):
        candidate = metadata.get(key, [])
        values.extend(candidate if isinstance(candidate, list) else [candidate])
    return {normalize_title(str(value)) for value in values if str(value).strip()}


def _ratio(value: float, values: dict[str, float]) -> float:
    total = sum(values.values())
    return value / total if total else 0.0


def _overlap_ratio(values: set[str], profile: dict[str, float]) -> float:
    if not profile:
        return 0.0
    return min(1.0, sum(profile.get(value, 0.0) for value in values) / sum(profile.values()))


def _add(target: dict[str, float], key: str, value: float) -> None:
    if key:
        target[key] = target.get(key, 0.0) + value


def _value(value: object) -> str:
    return str(getattr(value, "value", value))
