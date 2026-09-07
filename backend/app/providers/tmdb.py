from collections.abc import Mapping
from datetime import date
from typing import Any

import httpx

from app.core.exceptions import AppError
from app.models.enums import MediaStatus, MediaTitleType, MediaType, MediaUnitType
from app.providers.base import (
    ContentProvider,
    ProviderContent,
    ProviderFilters,
    ProviderTitle,
    ProviderUnit,
)
from app.providers.http import HttpProviderMixin
from app.services.provider_cache import ProviderCache


class TMDBProvider(HttpProviderMixin, ContentProvider):
    name = "tmdb"
    api_url = "https://api.themoviedb.org/3"
    image_url = "https://image.tmdb.org/t/p/w500"

    def __init__(
        self,
        client: httpx.AsyncClient,
        cache: ProviderCache,
        *,
        access_token: str | None,
        language: str,
        search_cache_ttl: int,
        detail_cache_ttl: int,
    ) -> None:
        self.client = client
        self.cache = cache
        self.access_token = access_token
        self.language = language
        self.search_cache_ttl = search_cache_ttl
        self.detail_cache_ttl = detail_cache_ttl
        self.configured = bool(access_token)

    @property
    def headers(self) -> dict[str, str]:
        return {"Authorization": f"Bearer {self.access_token}", "Accept": "application/json"}

    async def search(self, query: str, filters: ProviderFilters) -> list[ProviderContent]:
        if filters.media_type and filters.media_type not in {MediaType.MOVIE, MediaType.TV_SERIES}:
            return []
        language = filters.language or self.language
        params: dict[str, Any] = {
            "query": query,
            "language": language,
            "include_adult": "false",
            "page": 1,
        }
        payload = await self.cached_request(
            "GET",
            f"{self.api_url}/search/multi",
            cache_operation="search",
            cache_value=f"{query}|{filters.model_dump_json()}",
            ttl_seconds=self.search_cache_ttl,
            params=params,
            headers=self.headers,
        )
        results = []
        for value in payload.get("results", []):
            if value.get("media_type") not in {"movie", "tv"}:
                continue
            item = self.normalize(value)
            if filters.media_type and item.media_type != filters.media_type:
                continue
            if filters.year and item.release_year != filters.year:
                continue
            results.append(item)
            if len(results) >= filters.limit:
                break
        return results

    async def get_details(self, external_id: str) -> ProviderContent:
        kind, value = self._parse_external_id(external_id)
        payload = await self.cached_request(
            "GET",
            f"{self.api_url}/{kind}/{value}",
            cache_operation="details",
            cache_value=external_id,
            ttl_seconds=self.detail_cache_ttl,
            params={
                "language": self.language,
                "append_to_response": "alternative_titles,external_ids",
            },
            headers=self.headers,
        )
        return self.normalize(payload, kind=kind)

    async def get_units(self, external_id: str) -> list[ProviderUnit]:
        details = await self.get_details(external_id)
        if details.media_type != MediaType.TV_SERIES:
            return []
        return [
            ProviderUnit(
                unit_type=MediaUnitType.SEASON,
                number=float(season["season_number"]),
                title=season.get("name"),
                release_date=_parse_date(season.get("air_date")),
                metadata={
                    "episode_count": season.get("episode_count"),
                    "tmdb_id": season.get("id"),
                },
            )
            for season in details.metadata.get("seasons", [])
            if season.get("season_number") is not None
        ]

    def normalize(self, payload: Mapping[str, Any], **context: Any) -> ProviderContent:
        kind = context.get("kind") or payload.get("media_type")
        if kind not in {"movie", "tv"}:
            raise AppError(
                "Unsupported TMDB content type", code="invalid_provider_content", status_code=422
            )
        is_movie = kind == "movie"
        title = payload.get("title") if is_movie else payload.get("name")
        original_title = payload.get("original_title") if is_movie else payload.get("original_name")
        release_date = _parse_date(
            payload.get("release_date") if is_movie else payload.get("first_air_date")
        )
        aliases_payload = payload.get("alternative_titles", {})
        aliases = aliases_payload.get("titles" if is_movie else "results", [])
        external_id = f"{kind}:{payload['id']}"
        poster = payload.get("poster_path")
        genres = [value["name"] for value in payload.get("genres", []) if value.get("name")]
        cross_ids = payload.get("external_ids") or {}
        imdb_id = payload.get("imdb_id") or cross_ids.get("imdb_id")
        identifiers = {
            source: [str(value)]
            for source, value in {
                "imdb": imdb_id,
                "tvdb": cross_ids.get("tvdb_id"),
            }.items()
            if value
        }
        return ProviderContent(
            source=self.name,
            external_id=external_id,
            media_type=MediaType.MOVIE if is_movie else MediaType.TV_SERIES,
            title=title or original_title or external_id,
            original_title=original_title,
            alternative_titles=[
                ProviderTitle(
                    title=value["title"],
                    language_code=value.get("iso_3166_1"),
                    title_type=MediaTitleType.ALTERNATIVE,
                )
                for value in aliases
                if value.get("title")
            ],
            description=payload.get("overview") or None,
            release_date=release_date,
            release_year=release_date.year if release_date else None,
            status=_status(payload.get("status")),
            original_language=payload.get("original_language"),
            languages=[
                value["iso_639_1"]
                for value in payload.get("spoken_languages", [])
                if value.get("iso_639_1")
            ],
            genres=genres,
            cover_url=f"{self.image_url}{poster}" if poster else None,
            external_url=f"https://www.themoviedb.org/{kind}/{payload['id']}",
            identifiers=identifiers,
            metadata={
                "popularity": payload.get("popularity"),
                "vote_average": payload.get("vote_average"),
                "vote_count": payload.get("vote_count"),
                "seasons": payload.get("seasons", []),
            },
        )

    @staticmethod
    def _parse_external_id(external_id: str) -> tuple[str, int]:
        try:
            kind, raw_id = external_id.split(":", 1)
            value = int(raw_id)
        except (ValueError, TypeError):
            raise AppError(
                "TMDB external_id must use 'movie:<id>' or 'tv:<id>'",
                code="invalid_external_id",
                status_code=422,
            ) from None
        if kind not in {"movie", "tv"} or value <= 0:
            raise AppError(
                "TMDB external_id must use 'movie:<id>' or 'tv:<id>'",
                code="invalid_external_id",
                status_code=422,
            )
        return kind, value


def _parse_date(value: Any) -> date | None:
    try:
        return date.fromisoformat(value) if value else None
    except ValueError:
        return None


def _status(value: Any) -> MediaStatus:
    normalized = str(value or "").lower()
    if normalized in {"released", "ended"}:
        return MediaStatus.RELEASED
    if normalized in {"returning series", "in production", "post production"}:
        return MediaStatus.RELEASING
    if normalized in {"planned", "pilot"}:
        return MediaStatus.ANNOUNCED
    if normalized == "canceled":
        return MediaStatus.CANCELLED
    return MediaStatus.UNKNOWN
