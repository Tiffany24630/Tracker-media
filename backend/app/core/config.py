from functools import lru_cache
from pathlib import Path
from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict

BASE_DIR = Path(__file__).resolve().parent.parent.parent
DEFAULT_SQLITE_PATH = (BASE_DIR / 'tracker.db').as_posix()

class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file='.env', extra='ignore')
    database_url: str = f'sqlite:///{DEFAULT_SQLITE_PATH}'
    redis_url: str = 'redis://localhost:6379/0'
    jwt_secret: str = Field(default='dev-only-change-this-secret-32chars', min_length=32)
    access_token_expire_minutes: int = 60 * 24 * 7
    cors_origins: str = 'http://localhost:3000,http://localhost:5173,http://127.0.0.1:3000,http://127.0.0.1:5173,http://10.0.2.2:8000,http://10.0.2.2:3000'
    tmdb_api_key: str | None = None
    anilist_enabled: bool = True
    spotify_client_id: str | None = None
    spotify_client_secret: str | None = None
    public_base_url: str = 'http://localhost:8000'

    @property
    def cors_list(self) -> list[str]:
        import json
        if self.cors_origins.strip().startswith('['):
            try:
                return json.loads(self.cors_origins)
            except Exception:
                pass
        return [x.strip() for x in self.cors_origins.split(',') if x.strip()]

@lru_cache
def get_settings() -> Settings:
    return Settings()
