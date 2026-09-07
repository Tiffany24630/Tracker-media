from functools import lru_cache

import httpx

from app.core.config import get_settings
from app.integrations.redis import redis_client
from app.providers.anilist import AniListProvider
from app.providers.open_library import OpenLibraryProvider
from app.providers.registry import ProviderRegistry
from app.providers.tmdb import TMDBProvider
from app.services.provider_cache import ProviderCache


class ProviderContainer:
    def __init__(self) -> None:
        settings = get_settings()
        self.client = httpx.AsyncClient(timeout=settings.provider_request_timeout_seconds)
        cache = ProviderCache(redis_client)
        common = {
            "search_cache_ttl": settings.provider_search_cache_ttl_seconds,
            "detail_cache_ttl": settings.provider_detail_cache_ttl_seconds,
        }
        self.registry = ProviderRegistry()
        self.registry.register(
            TMDBProvider(
                self.client,
                cache,
                access_token=settings.tmdb_access_token,
                language=settings.tmdb_language,
                **common,
            )
        )
        self.registry.register(AniListProvider(self.client, cache, **common))
        self.registry.register(
            OpenLibraryProvider(
                self.client,
                cache,
                contact_email=settings.open_library_contact_email,
                **common,
            )
        )

    async def close(self) -> None:
        await self.client.aclose()


@lru_cache
def get_provider_container() -> ProviderContainer:
    return ProviderContainer()


def get_provider_registry() -> ProviderRegistry:
    return get_provider_container().registry


async def close_provider_container() -> None:
    if get_provider_container.cache_info().currsize:
        await get_provider_container().close()
        get_provider_container.cache_clear()
