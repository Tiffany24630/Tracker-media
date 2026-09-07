import asyncio
import logging
from collections.abc import Mapping
from typing import Any

import httpx

from app.core.exceptions import ExternalProviderError, NotFoundError, ProviderRateLimitError
from app.services.provider_cache import ProviderCache

logger = logging.getLogger("app.providers.http")


class HttpProviderMixin:
    name: str
    client: httpx.AsyncClient
    cache: ProviderCache

    async def before_request(self) -> None:
        return None

    async def cached_request(
        self,
        method: str,
        url: str,
        *,
        cache_operation: str,
        cache_value: str,
        ttl_seconds: int,
        params: Mapping[str, Any] | None = None,
        json: Any = None,
        headers: Mapping[str, str] | None = None,
    ) -> Any:
        key = self.cache.key(self.name, cache_operation, cache_value)
        cached = await self.cache.get(key)
        if cached is not None:
            return cached
        await self.before_request()
        for attempt in range(2):
            try:
                response = await self.client.request(
                    method, url, params=params, json=json, headers=headers
                )
            except httpx.TimeoutException as exc:
                if attempt == 0:
                    await asyncio.sleep(0.15)
                    continue
                raise ExternalProviderError(self.name, "request timed out") from exc
            except httpx.HTTPError as exc:
                raise ExternalProviderError(self.name, "network request failed") from exc
            if response.status_code == 429:
                raise ProviderRateLimitError(self.name, response.headers.get("Retry-After"))
            if response.status_code == 404:
                raise NotFoundError(f"Content was not found in provider '{self.name}'")
            if response.status_code >= 500 and attempt == 0:
                await asyncio.sleep(0.15)
                continue
            if response.is_error:
                logger.warning(
                    "external_provider_error",
                    extra={"provider": self.name, "status_code": response.status_code},
                )
                raise ExternalProviderError(
                    self.name, f"upstream returned HTTP {response.status_code}"
                )
            try:
                payload = response.json()
            except ValueError as exc:
                raise ExternalProviderError(self.name, "upstream returned invalid JSON") from exc
            await self.cache.set(key, payload, ttl_seconds)
            return payload
        raise ExternalProviderError(self.name, "request failed")
