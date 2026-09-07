import re
import unicodedata

from app.models.enums import MediaType

NON_ALPHANUMERIC = re.compile(r"[\W_]+", re.UNICODE)


def normalize_title(title: str) -> str:
    normalized = unicodedata.normalize("NFKC", title).casefold()
    return NON_ALPHANUMERIC.sub(" ", normalized).strip()


def build_media_match_key(media_type: MediaType, title: str, release_year: int | None) -> str:
    """Conservative exact key; fuzzy matching can use normalized titles separately."""
    year = str(release_year) if release_year is not None else "unknown"
    return f"{media_type.value}:{normalize_title(title)}:{year}"
