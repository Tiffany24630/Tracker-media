from datetime import UTC, datetime
from typing import Any
from urllib.parse import urlencode

import httpx

from app.core.config import get_settings
from app.core.exceptions import ConflictError
from app.integrations.base import (
    BaseIntegrationProvider,
    NormalizedMedia,
    NormalizedSyncItem,
    OAuthTokenData,
)
from app.models.enums import IntegrationCapability, MediaType, TrackingSource, TrackingStatus

ANILIST_GRAPHQL_URL = "https://graphql.anilist.co"
ANILIST_TOKEN_URL = "https://anilist.co/api/v2/oauth/token"
ANILIST_AUTH_URL = "https://anilist.co/api/v2/oauth/authorize"

STATUS_MAP: dict[str, TrackingStatus] = {
    "CURRENT": TrackingStatus.IN_PROGRESS,
    "PLANNING": TrackingStatus.PLANNED,
    "COMPLETED": TrackingStatus.COMPLETED,
    "DROPPED": TrackingStatus.DROPPED,
    "PAUSED": TrackingStatus.PAUSED,
    "REPEATING": TrackingStatus.REWATCHING,
}


class AniListIntegrationProvider(BaseIntegrationProvider):
    name = "anilist"
    display_name = "AniList"
    description = "Official AniList integration for Anime and Manga tracking"
    auth_type = "oauth"
    capabilities = {
        IntegrationCapability.CATALOG,
        IntegrationCapability.USER_LIBRARY,
        IntegrationCapability.PROGRESS,
        IntegrationCapability.RATINGS,
        IntegrationCapability.AUTOMATIC_SYNC,
        IntegrationCapability.OAUTH,
    }

    def __init__(self, http_client: httpx.AsyncClient | None = None) -> None:
        self.http_client = http_client

    def _get_client(self) -> httpx.AsyncClient:
        return self.http_client or httpx.AsyncClient(timeout=15.0)

    async def authenticate(
        self, *, redirect_uri: str | None = None, state: str | None = None
    ) -> dict[str, Any]:
        settings = get_settings()
        client_id = settings.anilist_client_id or "test_anilist_client_id"
        uri = redirect_uri or "http://localhost:3000/integrations/anilist/callback"
        query = {"client_id": client_id, "response_type": "code", "redirect_uri": uri}
        if state:
            query["state"] = state
        url = f"{ANILIST_AUTH_URL}?{urlencode(query)}"
        return {"authorization_url": url, "state": state or ""}

    async def oauth_callback(
        self, *, code: str, redirect_uri: str | None = None
    ) -> OAuthTokenData:
        settings = get_settings()
        client_id = settings.anilist_client_id or "test_anilist_client_id"
        client_secret = settings.anilist_client_secret or "test_anilist_client_secret"
        uri = redirect_uri or "http://localhost:3000/integrations/anilist/callback"

        payload = {
            "grant_type": "authorization_code",
            "client_id": client_id,
            "client_secret": client_secret,
            "redirect_uri": uri,
            "code": code,
        }

        client = self._get_client()
        should_close = self.http_client is None
        try:
            token_resp = await client.post(ANILIST_TOKEN_URL, json=payload)
            if token_resp.status_code != 200:
                raise ConflictError(f"AniList OAuth token exchange failed: {token_resp.text}")
            token_data = token_resp.json()
            access_token = token_data["access_token"]

            # Query viewer identity
            viewer_query = "query { Viewer { id name } }"
            viewer_resp = await client.post(
                ANILIST_GRAPHQL_URL,
                headers={"Authorization": f"Bearer {access_token}"},
                json={"query": viewer_query},
            )
            if viewer_resp.status_code != 200:
                raise ConflictError("Failed to fetch AniList viewer profile")
            viewer = viewer_resp.json().get("data", {}).get("Viewer", {})
            user_id = str(viewer.get("id", "unknown"))

            return OAuthTokenData(
                access_token=access_token,
                refresh_token=token_data.get("refresh_token"),
                token_type=token_data.get("token_type", "Bearer"),
                external_user_id=user_id,
                extra_data={"username": viewer.get("name")},
            )
        finally:
            if should_close:
                await client.aclose()

    async def fetch_user_data(self, credentials: dict[str, Any]) -> list[dict[str, Any]]:
        access_token = credentials.get("access_token")
        if not access_token:
            raise ConflictError("Missing access token for AniList sync")

        user_id_int = int(credentials.get("external_user_id", 0)) or None
        query = """
        query ($userId: Int, $type: MediaType) {
          MediaListCollection(userId: $userId, type: $type) {
            lists {
              entries {
                id
                status
                score(format: POINT_10_DECIMAL)
                progress
                progressVolumes
                updatedAt
                startedAt { year month day }
                completedAt { year month day }
                notes
                media {
                  id
                  idMal
                  type
                  format
                  title { romaji english native }
                  startDate { year }
                }
              }
            }
          }
        }
        """

        client = self._get_client()
        should_close = self.http_client is None
        all_entries: list[dict[str, Any]] = []

        try:
            for media_type in ("ANIME", "MANGA"):
                variables: dict[str, Any] = {"type": media_type}
                if user_id_int:
                    variables["userId"] = user_id_int
                resp = await client.post(
                    ANILIST_GRAPHQL_URL,
                    headers={"Authorization": f"Bearer {access_token}"},
                    json={"query": query, "variables": variables},
                )
                if resp.status_code == 200:
                    data = resp.json().get("data", {}).get("MediaListCollection")
                    if data and "lists" in data:
                        for media_list in data["lists"]:
                            for entry in media_list.get("entries", []):
                                all_entries.append(entry)
                elif resp.status_code == 401:
                    raise ConflictError("AniList access token expired or invalid")
            return all_entries
        finally:
            if should_close:
                await client.aclose()

    def normalize(self, raw_items: list[dict[str, Any]]) -> list[NormalizedSyncItem]:
        normalized: list[NormalizedSyncItem] = []

        for item in raw_items:
            media_info = item.get("media", {})
            title_dict = media_info.get("title", {})
            title = (
                title_dict.get("romaji")
                or title_dict.get("english")
                or title_dict.get("native")
                or "Unknown Title"
            )
            raw_type = media_info.get("type")
            format_type = media_info.get("format")

            media_type = MediaType.ANIME
            if raw_type == "MANGA":
                media_type = MediaType.MANGA
            elif format_type == "MOVIE":
                media_type = MediaType.MOVIE

            year = media_info.get("startDate", {}).get("year")
            ext_ids = {"anilist": str(media_info.get("id"))}
            if media_info.get("idMal"):
                ext_ids["myanimelist"] = str(media_info.get("idMal"))

            norm_media = NormalizedMedia(
                title=title,
                media_type=media_type,
                release_year=year,
                external_ids=ext_ids,
            )

            status = STATUS_MAP.get(str(item.get("status")), TrackingStatus.PLANNED)
            raw_score = item.get("score")
            score = float(raw_score) if raw_score and raw_score > 0 else None

            # Timestamp
            updated_at_raw = item.get("updatedAt")
            if updated_at_raw:
                source_updated_at = datetime.fromtimestamp(updated_at_raw, tz=UTC)
            else:
                source_updated_at = datetime.now(UTC)

            # Dates
            started_at = self._parse_fuzzy_date(item.get("startedAt"))
            completed_at = self._parse_fuzzy_date(item.get("completedAt"))

            progress = item.get("progress")
            progress_val = float(progress) if progress is not None else None
            volumes = item.get("progressVolumes")
            volume_val = float(volumes) if volumes is not None else None

            sync_item = NormalizedSyncItem(
                media=norm_media,
                status=status,
                source=TrackingSource.ANILIST,
                source_updated_at=source_updated_at,
                episode=progress_val if media_type in (MediaType.ANIME, MediaType.MOVIE) else None,
                chapter=progress_val if media_type == MediaType.MANGA else None,
                volume=volume_val if media_type == MediaType.MANGA else None,
                rating=score,
                notes=item.get("notes"),
                started_at=started_at,
                completed_at=completed_at,
            )
            normalized.append(sync_item)

        return normalized

    def _parse_fuzzy_date(self, date_dict: dict[str, Any] | None) -> datetime | None:
        if not date_dict:
            return None
        year = date_dict.get("year")
        month = date_dict.get("month") or 1
        day = date_dict.get("day") or 1
        if not year:
            return None
        try:
            return datetime(year, month, day, tzinfo=UTC)
        except ValueError:
            return None
