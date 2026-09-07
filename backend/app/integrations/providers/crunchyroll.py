from datetime import UTC, datetime
from typing import Any

from app.core.exceptions import ConflictError
from app.integrations.base import (
    BaseIntegrationProvider,
    NormalizedMedia,
    NormalizedSyncItem,
    OAuthTokenData,
)
from app.models.enums import IntegrationCapability, MediaType, TrackingSource, TrackingStatus


class CrunchyrollIntegrationProvider(BaseIntegrationProvider):
    name = "crunchyroll"
    display_name = "Crunchyroll"
    description = (
        "Crunchyroll integration prepared for watchlist export import and future "
        "official partner APIs. Unofficial private APIs are strictly not used."
    )
    auth_type = "import"
    capabilities = {
        IntegrationCapability.CATALOG,
        IntegrationCapability.USER_LIBRARY,
        IntegrationCapability.IMPORT,
    }

    async def authenticate(
        self, *, redirect_uri: str | None = None, state: str | None = None
    ) -> dict[str, Any]:
        return {
            "message": (
                "Crunchyroll does not provide a public consumer OAuth API. "
                "Use watchlist/history file import or await official partner integration."
            ),
            "supports_oauth": False,
            "auth_type": self.auth_type,
        }

    async def oauth_callback(
        self, *, code: str, redirect_uri: str | None = None
    ) -> OAuthTokenData:
        raise ConflictError(
            "Crunchyroll direct OAuth is not available. Private or reverse-engineered APIs are not supported."
        )

    async def fetch_user_data(self, credentials: dict[str, Any]) -> list[dict[str, Any]]:
        return credentials.get("imported_items", [])

    def normalize(self, raw_items: list[dict[str, Any]]) -> list[NormalizedSyncItem]:
        normalized: list[NormalizedSyncItem] = []

        for item in raw_items:
            # Example import format: {"series_title": "Jujutsu Kaisen", "episode_number": 24, "date": "2024-02-01"}
            title = item.get("series_title") or item.get("title", "Unknown Anime")
            ep = item.get("episode_number") or item.get("episode")
            ep_val = float(ep) if ep is not None else None

            date_str = item.get("date")
            source_updated_at = datetime.now(UTC)
            if date_str:
                try:
                    source_updated_at = datetime.strptime(date_str, "%Y-%m-%d").replace(tzinfo=UTC)
                except ValueError:
                    pass

            norm_media = NormalizedMedia(
                title=title,
                media_type=MediaType.ANIME,
            )

            sync_item = NormalizedSyncItem(
                media=norm_media,
                status=TrackingStatus.IN_PROGRESS if ep_val else TrackingStatus.PLANNED,
                source=TrackingSource.OTHER,
                source_updated_at=source_updated_at,
                episode=ep_val,
                started_at=source_updated_at,
            )
            normalized.append(sync_item)

        return normalized
