import html
import re
from collections.abc import Mapping
from datetime import date
from typing import Any

import httpx

from app.core.exceptions import ExternalProviderError, NotFoundError
from app.models.enums import MediaStatus, MediaTitleType, MediaType
from app.providers.base import (
    ContentProvider,
    ProviderContent,
    ProviderFilters,
    ProviderTitle,
    ProviderUnit,
)
from app.providers.http import HttpProviderMixin
from app.services.provider_cache import ProviderCache

MEDIA_FIELDS = """
id idMal type format status countryOfOrigin episodes chapters volumes duration genres synonyms
title { romaji english native }
description(asHtml: false)
startDate { year month day }
endDate { year month day }
coverImage { extraLarge large }
siteUrl
"""


class AniListProvider(HttpProviderMixin, ContentProvider):
    name = "anilist"
    api_url = "https://graphql.anilist.co"

    def __init__(
        self,
        client: httpx.AsyncClient,
        cache: ProviderCache,
        *,
        search_cache_ttl: int,
        detail_cache_ttl: int,
    ) -> None:
        self.client = client
        self.cache = cache
        self.search_cache_ttl = search_cache_ttl
        self.detail_cache_ttl = detail_cache_ttl

    async def search(self, query: str, filters: ProviderFilters) -> list[ProviderContent]:
        if filters.media_type and filters.media_type not in {
            MediaType.ANIME,
            MediaType.MANGA,
            MediaType.NOVEL,
        }:
            return []
        media_type = (
            "ANIME"
            if filters.media_type == MediaType.ANIME
            else "MANGA"
            if filters.media_type in {MediaType.MANGA, MediaType.NOVEL}
            else None
        )
        graphql = f"""
        query ($search: String!, $type: MediaType, $perPage: Int!) {{
          Page(page: 1, perPage: $perPage) {{
            media(search: $search, type: $type, isAdult: false) {{ {MEDIA_FIELDS} }}
          }}
        }}
        """
        variables = {"search": query, "type": media_type, "perPage": filters.limit}
        payload = await self._graphql(
            graphql,
            variables,
            "search",
            f"{query}|{filters.model_dump_json()}",
            self.search_cache_ttl,
        )
        results = [
            self.normalize(value)
            for value in payload.get("data", {}).get("Page", {}).get("media", [])
        ]
        if filters.media_type:
            results = [item for item in results if item.media_type == filters.media_type]
        if filters.year:
            results = [item for item in results if item.release_year == filters.year]
        return results[: filters.limit]

    async def get_details(self, external_id: str) -> ProviderContent:
        try:
            media_id = int(external_id)
        except ValueError:
            raise NotFoundError("Invalid AniList content ID") from None
        graphql = f"query ($id: Int!) {{ Media(id: $id) {{ {MEDIA_FIELDS} }} }}"
        payload = await self._graphql(
            graphql, {"id": media_id}, "details", external_id, self.detail_cache_ttl
        )
        value = payload.get("data", {}).get("Media")
        if not value:
            raise NotFoundError("Content was not found in provider 'anilist'")
        return self.normalize(value)

    async def get_units(self, external_id: str) -> list[ProviderUnit]:
        await self.get_details(external_id)
        return []

    async def _graphql(
        self, query: str, variables: dict[str, Any], operation: str, cache_value: str, ttl: int
    ) -> Any:
        payload = await self.cached_request(
            "POST",
            self.api_url,
            cache_operation=operation,
            cache_value=cache_value,
            ttl_seconds=ttl,
            json={"query": query, "variables": variables},
            headers={"Accept": "application/json", "Content-Type": "application/json"},
        )
        if payload.get("errors"):
            raise ExternalProviderError(
                self.name, str(payload["errors"][0].get("message", "GraphQL error"))
            )
        return payload

    def normalize(self, payload: Mapping[str, Any], **context: Any) -> ProviderContent:
        titles = payload.get("title") or {}
        primary = (
            titles.get("english")
            or titles.get("romaji")
            or titles.get("native")
            or str(payload["id"])
        )
        media_type = _media_type(
            payload.get("type"), payload.get("format"), payload.get("countryOfOrigin")
        )
        start_date = _fuzzy_date(payload.get("startDate"))
        alternatives: list[ProviderTitle] = []
        for value, language in (
            (titles.get("romaji"), None),
            (titles.get("native"), None),
            *[(item, None) for item in payload.get("synonyms", [])],
        ):
            if value and value != primary and value not in {item.title for item in alternatives}:
                alternatives.append(
                    ProviderTitle(
                        title=value, language_code=language, title_type=MediaTitleType.ALTERNATIVE
                    )
                )
        cover = payload.get("coverImage") or {}
        return ProviderContent(
            source=self.name,
            external_id=str(payload["id"]),
            media_type=media_type,
            title=primary,
            original_title=titles.get("native"),
            alternative_titles=alternatives,
            description=_plain_text(payload.get("description")),
            release_date=start_date,
            release_year=start_date.year if start_date else None,
            status=_anilist_status(payload.get("status")),
            original_language=None,
            languages=[],
            genres=list(payload.get("genres") or []),
            cover_url=cover.get("extraLarge") or cover.get("large"),
            external_url=payload.get("siteUrl"),
            identifiers={"myanimelist": [str(payload["idMal"])]} if payload.get("idMal") else {},
            metadata={
                "anilist_type": payload.get("type"),
                "format": payload.get("format"),
                "country_of_origin": payload.get("countryOfOrigin"),
                "episodes": payload.get("episodes"),
                "chapters": payload.get("chapters"),
                "volumes": payload.get("volumes"),
                "duration_minutes": payload.get("duration"),
                "mal_id": payload.get("idMal"),
            },
        )


def _media_type(value: Any, media_format: Any, country: Any) -> MediaType:
    if value == "ANIME":
        return MediaType.ANIME
    if media_format == "NOVEL":
        return MediaType.NOVEL
    if country == "CN":
        return MediaType.MANHUA
    if country == "KR":
        return MediaType.MANHWA
    return MediaType.MANGA


def _fuzzy_date(value: Any) -> date | None:
    if not value or not value.get("year"):
        return None
    try:
        return date(int(value["year"]), int(value.get("month") or 1), int(value.get("day") or 1))
    except (TypeError, ValueError):
        return None


def _plain_text(value: Any) -> str | None:
    if not value:
        return None
    return html.unescape(re.sub(r"<[^>]+>", "", str(value))).strip() or None


def _anilist_status(value: Any) -> MediaStatus:
    return {
        "FINISHED": MediaStatus.FINISHED,
        "RELEASING": MediaStatus.RELEASING,
        "NOT_YET_RELEASED": MediaStatus.ANNOUNCED,
        "CANCELLED": MediaStatus.CANCELLED,
        "HIATUS": MediaStatus.RELEASING,
    }.get(str(value), MediaStatus.UNKNOWN)
