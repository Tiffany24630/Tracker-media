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

SPOTIFY_AUTH_URL = "https://accounts.spotify.com/authorize"
SPOTIFY_TOKEN_URL = "https://accounts.spotify.com/api/token"
SPOTIFY_API_URL = "https://api.spotify.com/v1"
SPOTIFY_SCOPES = "user-library-read user-read-recently-played"


class SpotifyIntegrationProvider(BaseIntegrationProvider):
    name = "spotify"
    display_name = "Spotify"
    description = "Official Spotify integration for Music tracks and Albums tracking (Music only)"
    auth_type = "oauth"
    capabilities = {
        IntegrationCapability.USER_LIBRARY,
        IntegrationCapability.PROGRESS,
        IntegrationCapability.HISTORY,
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
        client_id = settings.spotify_client_id or "test_spotify_client_id"
        uri = redirect_uri or "http://localhost:3000/integrations/spotify/callback"
        query = {
            "response_type": "code",
            "client_id": client_id,
            "scope": SPOTIFY_SCOPES,
            "redirect_uri": uri,
        }
        if state:
            query["state"] = state
        url = f"{SPOTIFY_AUTH_URL}?{urlencode(query)}"
        return {"authorization_url": url, "state": state or ""}

    async def oauth_callback(
        self, *, code: str, redirect_uri: str | None = None
    ) -> OAuthTokenData:
        settings = get_settings()
        client_id = settings.spotify_client_id or "test_spotify_client_id"
        client_secret = settings.spotify_client_secret or "test_spotify_client_secret"
        uri = redirect_uri or "http://localhost:3000/integrations/spotify/callback"

        data = {
            "grant_type": "authorization_code",
            "code": code,
            "redirect_uri": uri,
            "client_id": client_id,
            "client_secret": client_secret,
        }

        client = self._get_client()
        should_close = self.http_client is None
        try:
            token_resp = await client.post(SPOTIFY_TOKEN_URL, data=data)
            if token_resp.status_code != 200:
                raise ConflictError(f"Spotify OAuth token exchange failed: {token_resp.text}")
            token_data = token_resp.json()
            access_token = token_data["access_token"]
            refresh_token = token_data.get("refresh_token")

            # Get user profile
            me_resp = await client.get(
                f"{SPOTIFY_API_URL}/me",
                headers={"Authorization": f"Bearer {access_token}"},
            )
            if me_resp.status_code != 200:
                raise ConflictError("Failed to fetch Spotify user profile")
            user_data = me_resp.json()
            user_id = user_data.get("id", "unknown")

            expires_in = token_data.get("expires_in")
            expires_at = (
                datetime.fromtimestamp(datetime.now(UTC).timestamp() + expires_in, tz=UTC)
                if expires_in
                else None
            )

            return OAuthTokenData(
                access_token=access_token,
                refresh_token=refresh_token,
                expires_at=expires_at,
                token_type=token_data.get("token_type", "Bearer"),
                scopes=token_data.get("scope", "").split(),
                external_user_id=user_id,
                extra_data={"display_name": user_data.get("display_name")},
            )
        finally:
            if should_close:
                await client.aclose()

    async def refresh_credentials(self, credentials: dict[str, Any]) -> dict[str, Any]:
        refresh_token = credentials.get("refresh_token")
        if not refresh_token:
            return credentials

        settings = get_settings()
        client_id = settings.spotify_client_id or "test_spotify_client_id"
        client_secret = settings.spotify_client_secret or "test_spotify_client_secret"

        data = {
            "grant_type": "refresh_token",
            "refresh_token": refresh_token,
            "client_id": client_id,
            "client_secret": client_secret,
        }

        client = self._get_client()
        should_close = self.http_client is None
        try:
            resp = await client.post(SPOTIFY_TOKEN_URL, data=data)
            if resp.status_code == 200:
                token_data = resp.json()
                credentials["access_token"] = token_data["access_token"]
                if "refresh_token" in token_data:
                    credentials["refresh_token"] = token_data["refresh_token"]
                expires_in = token_data.get("expires_in")
                if expires_in:
                    credentials["expires_at"] = (
                        datetime.fromtimestamp(datetime.now(UTC).timestamp() + expires_in, tz=UTC)
                        .isoformat()
                    )
            return credentials
        finally:
            if should_close:
                await client.aclose()

    async def fetch_user_data(self, credentials: dict[str, Any]) -> list[dict[str, Any]]:
        access_token = credentials.get("access_token")
        if not access_token:
            raise ConflictError("Missing access token for Spotify sync")

        headers = {"Authorization": f"Bearer {access_token}"}
        client = self._get_client()
        should_close = self.http_client is None
        items: list[dict[str, Any]] = []

        try:
            # 1. Recently played tracks
            recent_resp = await client.get(
                f"{SPOTIFY_API_URL}/me/player/recently-played?limit=50",
                headers=headers,
            )
            if recent_resp.status_code == 200:
                for entry in recent_resp.json().get("items", []):
                    entry["_item_type"] = "recently_played"
                    items.append(entry)

            # 2. Saved tracks (liked songs)
            tracks_resp = await client.get(
                f"{SPOTIFY_API_URL}/me/tracks?limit=50",
                headers=headers,
            )
            if tracks_resp.status_code == 200:
                for entry in tracks_resp.json().get("items", []):
                    entry["_item_type"] = "saved_track"
                    items.append(entry)

            # 3. Saved albums
            albums_resp = await client.get(
                f"{SPOTIFY_API_URL}/me/albums?limit=50",
                headers=headers,
            )
            if albums_resp.status_code == 200:
                for entry in albums_resp.json().get("items", []):
                    entry["_item_type"] = "saved_album"
                    items.append(entry)

            return items
        finally:
            if should_close:
                await client.aclose()

    def normalize(self, raw_items: list[dict[str, Any]]) -> list[NormalizedSyncItem]:
        normalized: list[NormalizedSyncItem] = []
        seen_keys: set[str] = set()

        for item in raw_items:
            itype = item.get("_item_type")

            if itype in ("recently_played", "saved_track"):
                track = item.get("track", {})
                track_id = track.get("id")
                if not track_id or track_id in seen_keys:
                    continue
                seen_keys.add(track_id)

                title = track.get("name", "Unknown Track")
                artists = [a.get("name") for a in track.get("artists", []) if a.get("name")]
                artist_str = ", ".join(artists) if artists else None
                full_title = f"{title} - {artist_str}" if artist_str else title

                played_at = item.get("played_at") or item.get("added_at")
                source_updated_at = self._parse_iso(played_at) or datetime.now(UTC)

                norm_media = NormalizedMedia(
                    title=full_title,
                    media_type=MediaType.MUSIC,
                    external_ids={"spotify": str(track_id)},
                )

                sync_item = NormalizedSyncItem(
                    media=norm_media,
                    status=TrackingStatus.COMPLETED,
                    source=TrackingSource.SPOTIFY,
                    source_updated_at=source_updated_at,
                    track=float(track.get("track_number", 1)),
                    completed_at=source_updated_at,
                )
                normalized.append(sync_item)

            elif itype == "saved_album":
                album = item.get("album", {})
                album_id = album.get("id")
                if not album_id or album_id in seen_keys:
                    continue
                seen_keys.add(album_id)

                title = album.get("name", "Unknown Album")
                artists = [a.get("name") for a in album.get("artists", []) if a.get("name")]
                artist_str = ", ".join(artists) if artists else None
                full_title = f"{title} - {artist_str}" if artist_str else title

                year = None
                rel_date = album.get("release_date")
                if rel_date and len(rel_date) >= 4:
                    try:
                        year = int(rel_date[:4])
                    except ValueError:
                        pass

                added_at = item.get("added_at")
                source_updated_at = self._parse_iso(added_at) or datetime.now(UTC)

                norm_media = NormalizedMedia(
                    title=full_title,
                    media_type=MediaType.ALBUM,
                    release_year=year,
                    external_ids={"spotify": str(album_id)},
                )

                total_tracks = float(album.get("total_tracks", 1))
                sync_item = NormalizedSyncItem(
                    media=norm_media,
                    status=TrackingStatus.COMPLETED,
                    source=TrackingSource.SPOTIFY,
                    source_updated_at=source_updated_at,
                    track=total_tracks,
                    percentage=100.0,
                    completed_at=source_updated_at,
                )
                normalized.append(sync_item)

        return normalized

    def _parse_iso(self, val: str | None) -> datetime | None:
        if not val:
            return None
        try:
            return datetime.fromisoformat(val.replace("Z", "+00:00"))
        except ValueError:
            return None
