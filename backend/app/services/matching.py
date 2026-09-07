from dataclasses import dataclass
from datetime import UTC, datetime
from uuid import UUID

from sqlalchemy import or_, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.core.config import get_settings
from app.core.exceptions import ConflictError, NotFoundError
from app.models.matching import MediaMatchDecision
from app.models.media import Media
from app.models.media_details import MediaExternalId
from app.models.user import User
from app.providers.base import ContentProvider, ProviderContent
from app.schemas.matching import MediaMatchSuggestion
from app.schemas.media import MediaTitleCreate
from app.services.media import external_id_from_payload, title_from_payload
from app.utils.matching import normalize_identifier, normalize_title, title_similarity

STRONG_IDENTIFIER_SOURCES = {"isbn", "isbn10", "isbn13"}


@dataclass(frozen=True, slots=True)
class MatchSignals:
    external_id: bool
    strong_ids: list[str]
    cross_ids: list[str]
    exact_titles: list[str]
    fuzzy_similarity: float
    year_match: bool
    date_match: bool
    type_match: bool


async def suggest_matches(
    session: AsyncSession, content: ProviderContent
) -> list[MediaMatchSuggestion]:
    settings = get_settings()
    identifiers = _content_identifiers(content)
    identifier_sources = list(identifiers)
    candidate_condition = Media.media_type == content.media_type
    if identifier_sources:
        candidate_condition = or_(
            candidate_condition,
            Media.external_ids.any(MediaExternalId.provider.in_(identifier_sources)),
        )
    statement = (
        select(Media)
        .options(selectinload(Media.titles), selectinload(Media.external_ids))
        .where(candidate_condition)
        .order_by(Media.updated_at.desc())
        .limit(250)
    )
    candidates = list((await session.scalars(statement)).unique().all())
    rejected_ids = set(
        await session.scalars(
            select(MediaMatchDecision.candidate_media_id).where(
                MediaMatchDecision.provider == content.source,
                MediaMatchDecision.external_id == content.external_id,
                MediaMatchDecision.status == "rejected",
            )
        )
    )
    suggestions = []
    for candidate in candidates:
        if candidate.id in rejected_ids:
            continue
        signals = _signals(content, candidate)
        score, auto_eligible = _score(signals)
        if score < settings.matching_suggestion_threshold:
            continue
        suggestions.append(
            MediaMatchSuggestion(
                media_id=candidate.id,
                title=candidate.title,
                confidence=round(score, 4),
                confidence_level=_confidence_level(score),
                auto_link_eligible=auto_eligible
                and score >= settings.matching_high_confidence_threshold,
                evidence=_evidence(signals),
            )
        )
    return sorted(suggestions, key=lambda value: value.confidence, reverse=True)


async def record_decision(
    session: AsyncSession,
    provider: ContentProvider,
    content: ProviderContent,
    media_id: UUID,
    decision: str,
    reviewer: User,
) -> MediaMatchDecision:
    candidate = await session.scalar(
        select(Media)
        .options(selectinload(Media.titles), selectinload(Media.external_ids))
        .where(Media.id == media_id)
    )
    if candidate is None:
        raise NotFoundError("Candidate media was not found")
    signals = _signals(content, candidate)
    score, auto_eligible = _score(signals)
    suggestion = MediaMatchSuggestion(
        media_id=candidate.id,
        title=candidate.title,
        confidence=round(score, 4),
        confidence_level=_confidence_level(score),
        auto_link_eligible=auto_eligible,
        evidence=_evidence(signals),
    )
    row = await session.scalar(
        select(MediaMatchDecision).where(
            MediaMatchDecision.provider == content.source,
            MediaMatchDecision.external_id == content.external_id,
            MediaMatchDecision.candidate_media_id == candidate.id,
        )
    )
    if row is None:
        row = MediaMatchDecision(
            provider=content.source,
            external_id=content.external_id,
            candidate_media_id=candidate.id,
        )
        session.add(row)
    elif row.status == "confirmed" and decision == "reject":
        raise ConflictError("A confirmed association cannot be rejected without unlinking IDs")
    row.status = "confirmed" if decision == "confirm" else "rejected"
    row.confidence = suggestion.confidence
    row.confidence_level = suggestion.confidence_level
    row.evidence = suggestion.evidence
    row.reviewed_by_user_id = reviewer.id
    row.reviewed_at = datetime.now(UTC)
    if decision == "confirm":
        _associate_content(candidate, provider, content)
    try:
        await session.commit()
    except IntegrityError:
        await session.rollback()
        raise ConflictError("An external identifier is already assigned to another work") from None
    await session.refresh(row)
    return row


async def associate_automatically(
    session: AsyncSession,
    candidate: Media,
    provider: ContentProvider,
    content: ProviderContent,
) -> Media:
    _associate_content(candidate, provider, content)
    try:
        await session.commit()
    except IntegrityError:
        await session.rollback()
        raise ConflictError("An external identifier is already assigned to another work") from None
    return candidate


def _associate_content(
    candidate: Media, provider: ContentProvider, content: ProviderContent
) -> None:
    payload = provider.map_to_internal_model(content)
    existing_ids = {
        (value.provider, _normalized_external_id(value.provider, value.external_id))
        for value in candidate.external_ids
    }
    for external_id in payload.external_ids:
        key = (
            external_id.provider.lower(),
            _normalized_external_id(external_id.provider, external_id.external_id),
        )
        if key not in existing_ids:
            candidate.external_ids.append(external_id_from_payload(external_id))
            existing_ids.add(key)
    existing_titles = {normalize_title(value.title) for value in candidate.titles}
    incoming_titles = [MediaTitleCreate(title=content.title), *payload.aliases]
    if content.original_title:
        incoming_titles.append(MediaTitleCreate(title=content.original_title))
    for title in incoming_titles:
        normalized = normalize_title(title.title)
        if normalized and normalized not in existing_titles:
            candidate.titles.append(title_from_payload(title))
            existing_titles.add(normalized)


def _signals(content: ProviderContent, candidate: Media) -> MatchSignals:
    incoming_ids = _content_identifiers(content)
    strong_ids: list[str] = []
    cross_ids: list[str] = []
    external_id = False
    for candidate_id in candidate.external_ids:
        source = candidate_id.provider.lower()
        normalized_candidate = _normalized_external_id(source, candidate_id.external_id)
        if any(
            _normalized_external_id(source, value) == normalized_candidate
            for value in incoming_ids.get(source, [])
        ):
            label = f"{source}:{candidate_id.external_id}"
            if source == content.source:
                external_id = True
            elif source in STRONG_IDENTIFIER_SOURCES:
                strong_ids.append(label)
            else:
                cross_ids.append(label)
    incoming_titles = {
        normalize_title(value)
        for value in [
            content.title,
            content.original_title,
            *(title.title for title in content.alternative_titles),
        ]
        if value
    }
    candidate_titles = {normalize_title(candidate.title)} | {
        title.normalized_title for title in candidate.titles
    }
    exact_titles = sorted(incoming_titles & candidate_titles)
    fuzzy = max(
        (title_similarity(left, right) for left in incoming_titles for right in candidate_titles),
        default=0.0,
    )
    return MatchSignals(
        external_id=external_id,
        strong_ids=strong_ids,
        cross_ids=cross_ids,
        exact_titles=exact_titles,
        fuzzy_similarity=fuzzy,
        year_match=bool(content.release_year and content.release_year == candidate.release_year),
        date_match=bool(content.release_date and content.release_date == candidate.release_date),
        type_match=content.media_type == candidate.media_type,
    )


def _score(signals: MatchSignals) -> tuple[float, bool]:
    if signals.external_id:
        return 1.0, True
    if signals.strong_ids:
        return 0.98, True
    if signals.cross_ids:
        return 0.95, True
    if signals.exact_titles:
        score = 0.55 + (0.20 if signals.type_match else 0)
        score += 0.15 if signals.date_match else 0.10 if signals.year_match else 0
        return min(score, 0.90), signals.type_match and (signals.year_match or signals.date_match)
    score = signals.fuzzy_similarity * 0.45
    score += 0.15 if signals.type_match else 0
    score += 0.10 if signals.year_match else 0
    return min(score, 0.70), False


def _confidence_level(score: float) -> str:
    settings = get_settings()
    if score >= settings.matching_high_confidence_threshold:
        return "high"
    if score >= settings.matching_medium_confidence_threshold:
        return "medium"
    return "low"


def _evidence(signals: MatchSignals) -> dict:
    return {
        "external_id_match": signals.external_id,
        "strong_identifiers": signals.strong_ids,
        "cross_identifiers": signals.cross_ids,
        "exact_titles": signals.exact_titles,
        "fuzzy_title_similarity": round(signals.fuzzy_similarity, 4),
        "year_match": signals.year_match,
        "date_match": signals.date_match,
        "type_match": signals.type_match,
    }


def _content_identifiers(content: ProviderContent) -> dict[str, list[str]]:
    values = {
        source.lower(): list(dict.fromkeys(ids)) for source, ids in content.identifiers.items()
    }
    values.setdefault(content.source.lower(), []).append(content.external_id)
    return values


def _normalized_external_id(source: str, value: str) -> str:
    if source.lower() in STRONG_IDENTIFIER_SOURCES:
        return normalize_identifier(value)
    return value.strip().casefold()
