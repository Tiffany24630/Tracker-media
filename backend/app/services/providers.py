import asyncio
import logging

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import get_settings
from app.core.exceptions import AppError, MatchReviewRequiredError
from app.models.media import Media
from app.models.media_details import MediaExternalId
from app.providers.base import ContentProvider, ProviderContent, ProviderFilters, ProviderUnit
from app.providers.registry import ProviderRegistry
from app.schemas.media import MediaUnitCreate
from app.schemas.providers import ProviderFailure
from app.services import matching as matching_service
from app.services import media as media_service

logger = logging.getLogger("app.providers")


async def search_all(
    registry: ProviderRegistry, query: str, filters: ProviderFilters
) -> tuple[list[ProviderContent], list[ProviderFailure]]:
    providers = registry.all(configured_only=True)
    responses = await asyncio.gather(
        *(provider.search(query, filters) for provider in providers), return_exceptions=True
    )
    provider_results: list[list[ProviderContent]] = []
    errors: list[ProviderFailure] = []
    for provider, response in zip(providers, responses, strict=True):
        if isinstance(response, BaseException):
            code = response.code if isinstance(response, AppError) else "external_provider_error"
            message = (
                response.message if isinstance(response, AppError) else "Provider request failed"
            )
            errors.append(ProviderFailure(provider=provider.name, code=code, message=message))
            logger.warning(
                "global_provider_search_failed",
                extra={"provider": provider.name, "exception_type": type(response).__name__},
            )
            continue
        provider_results.append(response)
    results = [
        item
        for index in range(filters.limit)
        for values in provider_results
        if index < len(values)
        for item in [values[index]]
    ]
    return results[: filters.limit], errors


async def get_unified_details(
    provider: ContentProvider, external_id: str, *, include_units: bool
) -> tuple[ProviderContent, list[ProviderUnit]]:
    content = await provider.get_details(external_id)
    units = await provider.get_units(external_id) if include_units else []
    return content, units


async def import_content(
    session: AsyncSession,
    provider: ContentProvider,
    external_id: str,
    *,
    include_units: bool,
) -> Media:
    normalized_id = external_id.strip()
    existing_media_id = await session.scalar(
        select(MediaExternalId.media_id).where(
            MediaExternalId.provider == provider.name,
            MediaExternalId.external_id == normalized_id,
        )
    )
    if existing_media_id:
        return await media_service.get_media(session, existing_media_id)

    content = await provider.get_details(normalized_id)
    suggestions = await matching_service.suggest_matches(session, content)
    best = suggestions[0] if suggestions else None
    settings = get_settings()
    if (
        best
        and best.confidence_level == "high"
        and best.auto_link_eligible
        and settings.matching_auto_link_high_confidence
    ):
        candidate = await media_service.get_media(session, best.media_id)
        item = await matching_service.associate_automatically(session, candidate, provider, content)
    elif best and best.confidence_level in {"high", "medium"}:
        raise MatchReviewRequiredError(
            [suggestion.model_dump(mode="json") for suggestion in suggestions[:5]]
        )
    else:
        payload = provider.map_to_internal_model(content)
        item = await media_service.create_media(session, payload, allow_duplicate_match_key=True)

    if include_units:
        units = await provider.get_units(normalized_id)
        existing_keys = {(unit.unit_type, unit.number) for unit in item.units}
        for unit in units:
            if (unit.unit_type, unit.number) not in existing_keys:
                await media_service.add_unit(
                    session,
                    item,
                    MediaUnitCreate(**unit.model_dump()),
                )
    return await media_service.get_media(session, item.id)
