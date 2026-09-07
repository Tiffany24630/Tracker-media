import hashlib
import json
import logging
from typing import Any

from redis.asyncio import Redis
from redis.exceptions import RedisError

logger = logging.getLogger("app.providers.cache")


class ProviderCache:
    def __init__(self, redis: Redis, *, prefix: str = "umt:providers") -> None:
        self.redis = redis
        self.prefix = prefix

    def key(self, provider: str, operation: str, value: str) -> str:
        digest = hashlib.sha256(value.encode()).hexdigest()
        return f"{self.prefix}:{provider}:{operation}:{digest}"

    async def get(self, key: str) -> Any | None:
        try:
            value = await self.redis.get(key)
        except RedisError:
            logger.warning("provider_cache_read_failed", extra={"provider_cache_key": key})
            return None
        if value is None:
            return None
        try:
            return json.loads(value)
        except (TypeError, json.JSONDecodeError):
            logger.warning("provider_cache_invalid_value", extra={"provider_cache_key": key})
            return None

    async def set(self, key: str, value: Any, ttl_seconds: int) -> None:
        try:
            await self.redis.set(key, json.dumps(value, default=str), ex=ttl_seconds)
        except RedisError:
            logger.warning("provider_cache_write_failed", extra={"provider_cache_key": key})
