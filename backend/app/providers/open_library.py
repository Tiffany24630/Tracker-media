import asyncio
from collections.abc import Mapping
from datetime import date
from time import monotonic
from typing import Any

import httpx

from app.core.exceptions import AppError
from app.models.enums import MediaStatus, MediaType
from app.providers.base import ContentProvider, ProviderContent, ProviderFilters, ProviderUnit
from app.providers.http import HttpProviderMixin
from app.services.provider_cache import ProviderCache


class OpenLibraryProvider(HttpProviderMixin, ContentProvider):
    name = "open_library"
    api_url = "https://openlibrary.org"

    def __init__(
        self,
        client: httpx.AsyncClient,
        cache: ProviderCache,
        *,
        contact_email: str | None,
        search_cache_ttl: int,
        detail_cache_ttl: int,
    ) -> None:
        self.client = client
        self.cache = cache
        self.search_cache_ttl = search_cache_ttl
        self.detail_cache_ttl = detail_cache_ttl
        self.headers = {
            "User-Agent": f"UniversalMediaTracker/0.1 ({contact_email or 'local-development'})",
            "Accept": "application/json",
        }
        self._minimum_interval = 1 / (3 if contact_email else 1)
        self._last_request = 0.0
        self._rate_lock = asyncio.Lock()

    async def before_request(self) -> None:
        async with self._rate_lock:
            delay = self._minimum_interval - (monotonic() - self._last_request)
            if delay > 0:
                await asyncio.sleep(delay)
            self._last_request = monotonic()

    async def search(self, query: str, filters: ProviderFilters) -> list[ProviderContent]:
        if filters.media_type and filters.media_type not in {MediaType.BOOK, MediaType.NOVEL}:
            return []
        params: dict[str, Any] = {
            "q": query,
            "limit": filters.limit,
            "fields": (
                "key,title,author_name,first_publish_year,cover_i,language,"
                "subject,edition_count,isbn"
            ),
        }
        if filters.year:
            params["first_publish_year"] = filters.year
        if filters.language:
            params["language"] = filters.language
        payload = await self.cached_request(
            "GET",
            f"{self.api_url}/search.json",
            cache_operation="search",
            cache_value=f"{query}|{filters.model_dump_json()}",
            ttl_seconds=self.search_cache_ttl,
            params=params,
            headers=self.headers,
        )
        return [
            self.normalize(value)
            for value in payload.get("docs", [])[: filters.limit]
            if value.get("key")
        ]

    async def get_details(self, external_id: str) -> ProviderContent:
        work_id = _work_id(external_id)
        payload = await self.cached_request(
            "GET",
            f"{self.api_url}/works/{work_id}.json",
            cache_operation="details",
            cache_value=work_id,
            ttl_seconds=self.detail_cache_ttl,
            headers=self.headers,
        )
        try:
            editions = await self.cached_request(
                "GET",
                f"{self.api_url}/works/{work_id}/editions.json",
                cache_operation="editions",
                cache_value=work_id,
                ttl_seconds=self.detail_cache_ttl,
                params={"limit": 20, "fields": "isbn_10,isbn_13"},
                headers=self.headers,
            )
        except AppError:
            # Edition identifiers improve matching but are not required for work details.
            editions = {}
        return self.normalize(payload, editions=editions.get("entries", []))

    async def get_units(self, external_id: str) -> list[ProviderUnit]:
        await self.get_details(external_id)
        return []

    def normalize(self, payload: Mapping[str, Any], **context: Any) -> ProviderContent:
        work_id = _work_id(str(payload.get("key") or payload.get("id") or ""))
        description = payload.get("description")
        if isinstance(description, Mapping):
            description = description.get("value")
        release_year = payload.get("first_publish_year")
        release_date = _open_library_date(payload.get("first_publish_date"), release_year)
        covers = payload.get("covers") or []
        cover_id = payload.get("cover_i") or (covers[0] if covers else None)
        languages = [
            language
            for value in payload.get("languages", payload.get("language", []))
            if (language := _language_code(value))
        ]
        subjects = [
            str(value) for value in (payload.get("subjects") or payload.get("subject") or [])[:30]
        ]
        editions = context.get("editions") or []
        isbn_10 = sorted(
            {str(value) for edition in editions for value in edition.get("isbn_10", [])}
            | {str(value) for value in payload.get("isbn", []) if len(str(value)) == 10}
        )
        isbn_13 = sorted(
            {str(value) for edition in editions for value in edition.get("isbn_13", [])}
            | {str(value) for value in payload.get("isbn", []) if len(str(value)) == 13}
        )
        return ProviderContent(
            source=self.name,
            external_id=work_id,
            media_type=MediaType.BOOK,
            title=str(payload.get("title") or work_id),
            description=str(description) if description else None,
            release_date=release_date,
            release_year=int(release_year)
            if release_year
            else (release_date.year if release_date else None),
            status=MediaStatus.RELEASED,
            languages=languages,
            genres=subjects,
            cover_url=f"https://covers.openlibrary.org/b/id/{cover_id}-L.jpg" if cover_id else None,
            external_url=f"https://openlibrary.org/works/{work_id}",
            identifiers={
                source: values
                for source, values in {"isbn10": isbn_10, "isbn13": isbn_13}.items()
                if values
            },
            metadata={
                "authors": payload.get("author_name", []),
                "edition_count": payload.get("edition_count"),
            },
        )


def _work_id(value: str) -> str:
    work_id = value.strip().removeprefix("/works/")
    if not work_id or "/" in work_id:
        raise AppError("Invalid Open Library work ID", code="invalid_external_id", status_code=422)
    return work_id


def _language_code(value: Any) -> str | None:
    if isinstance(value, Mapping):
        value = value.get("key")
    if not isinstance(value, str):
        return None
    return value.removeprefix("/languages/")


def _open_library_date(value: Any, year: Any) -> date | None:
    if value:
        for pattern in ("%Y-%m-%d", "%B %d, %Y", "%Y"):
            try:
                from datetime import datetime

                return datetime.strptime(str(value), pattern).date()
            except ValueError:
                pass
    try:
        return date(int(year), 1, 1) if year else None
    except (TypeError, ValueError):
        return None
