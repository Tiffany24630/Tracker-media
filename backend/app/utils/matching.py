import re
import unicodedata
from difflib import SequenceMatcher

from app.models.enums import MediaType

NON_ALPHANUMERIC = re.compile(r"[^\w]+", re.UNICODE)
IDENTIFIER_PUNCTUATION = re.compile(r"[^A-Z0-9]")


def normalize_title(title: str) -> str:
    normalized = unicodedata.normalize("NFKC", title).casefold()
    return " ".join(NON_ALPHANUMERIC.sub(" ", normalized).split())


def normalize_identifier(value: str) -> str:
    return IDENTIFIER_PUNCTUATION.sub("", unicodedata.normalize("NFKC", value).upper())


def title_similarity(left: str, right: str) -> float:
    normalized_left = normalize_title(left)
    normalized_right = normalize_title(right)
    if not normalized_left or not normalized_right:
        return 0.0
    return SequenceMatcher(None, normalized_left, normalized_right).ratio()


def build_media_match_key(media_type: MediaType, title: str, release_year: int | None) -> str:
    """Search key only; identity decisions are made by MediaIdentityService."""
    year = str(release_year) if release_year is not None else "unknown"
    return f"{media_type.value}:{normalize_title(title)}:{year}"
