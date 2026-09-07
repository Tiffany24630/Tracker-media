from abc import ABC, abstractmethod
from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class OAuthIdentity:
    provider: str
    external_user_id: str
    email: str | None
    display_name: str | None
    scopes: tuple[str, ...]


class OAuthProvider(ABC):
    """Boundary implemented later by documented OAuth/OIDC providers."""

    name: str

    @abstractmethod
    def authorization_url(self, *, state: str, redirect_uri: str) -> str:
        raise NotImplementedError

    @abstractmethod
    async def exchange_code(self, *, code: str, redirect_uri: str) -> OAuthIdentity:
        raise NotImplementedError
