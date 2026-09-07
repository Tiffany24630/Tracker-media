from app.providers.base import MediaProvider


class ProviderRegistry:
    def __init__(self) -> None:
        self._providers: dict[str, MediaProvider] = {}

    def register(self, provider: MediaProvider) -> None:
        if provider.name in self._providers:
            raise ValueError(f"Provider '{provider.name}' is already registered")
        self._providers[provider.name] = provider

    def get(self, name: str) -> MediaProvider:
        try:
            return self._providers[name]
        except KeyError:
            raise KeyError(f"Provider '{name}' is not registered") from None

    def all(self) -> tuple[MediaProvider, ...]:
        return tuple(self._providers.values())
