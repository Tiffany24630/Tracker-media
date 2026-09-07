from app.core.exceptions import NotFoundError, ProviderConfigurationError
from app.providers.base import ContentProvider


class ProviderRegistry:
    def __init__(self) -> None:
        self._providers: dict[str, ContentProvider] = {}

    def register(self, provider: ContentProvider) -> None:
        name = provider.name.lower()
        if name in self._providers:
            raise ValueError(f"Provider '{name}' is already registered")
        self._providers[name] = provider

    def get(self, name: str, *, require_configured: bool = True) -> ContentProvider:
        provider = self._providers.get(name.lower())
        if provider is None:
            raise NotFoundError(f"Content provider '{name}' is not registered")
        if require_configured and not provider.configured:
            raise ProviderConfigurationError(name)
        return provider

    def all(self, *, configured_only: bool = False) -> tuple[ContentProvider, ...]:
        providers = tuple(self._providers.values())
        if configured_only:
            return tuple(provider for provider in providers if provider.configured)
        return providers
