"""Additional public metadata catalogs, independent of reading/download services."""
import asyncio
import logging
import re
import httpx

logger = logging.getLogger(__name__)


async def search_cheapshark(query: str, limit: int = 40) -> list[dict]:
    if not query.strip():
        return []
    try:
        async with httpx.AsyncClient(timeout=15, follow_redirects=True,
                                     headers={'User-Agent': 'TrackerMedia/1.8 (public media catalog)'}) as client:
            response = await client.get('https://www.cheapshark.com/api/1.0/games',
                                        params={'title': query, 'limit': min(limit, 60)})
            response.raise_for_status()
            items = response.json()
            semaphore = asyncio.Semaphore(4)

            async def enrich(item):
                app_id = item.get('steamAppID')
                if not app_id:
                    return {}
                async with semaphore:
                    try:
                        detail = await client.get('https://store.steampowered.com/api/appdetails',
                                                  params={'appids': app_id, 'l': 'english'})
                        detail.raise_for_status()
                        data = (detail.json().get(str(app_id)) or {}).get('data') or {}
                        release = data.get('release_date') or {}
                        year = re.search(r'\b(?:19|20)\d{2}\b', release.get('date', ''))
                        return dict(release_year=int(year[0]) if year else None,
                                    genres=[g['description'] for g in data.get('genres', [])],
                                    description=data.get('short_description'),
                                    creator=', '.join(data.get('developers') or []),
                                    status='upcoming' if release.get('coming_soon') else 'finished')
                    except (httpx.HTTPError, ValueError, KeyError):
                        return {}

            details = await asyncio.gather(*(enrich(item) for item in items[:15]))
        return [{**dict(source='cheapshark', external_id=str(x['gameID']),
                     media_type='game', title=x['external'], cover_url=x.get('thumb'),
                     status='finished', genres=[]), **(details[i] if i < len(details) else {})}
                for i, x in enumerate(items)]
    except (httpx.HTTPError, ValueError, KeyError) as exc:
        logger.warning('CheapShark search failed: %s', exc)
        return []


async def search_mangadex(query: str, limit: int = 40, years: list[int] | None = None) -> list[dict]:
    try:
        params = [('limit', str(min(limit, 100))), ('includes[]', 'cover_art'),
                  ('includes[]', 'author'), ('includes[]', 'artist')]
        if query.strip():
            params.append(('title', query))
        if years and len(years) == 1:
            params.append(('year', str(years[0])))
        async with httpx.AsyncClient(timeout=15, follow_redirects=True) as client:
            response = await client.get('https://api.mangadex.org/manga', params=params)
            response.raise_for_status()
        results = []
        for item in response.json().get('data', []):
            attrs = item['attributes']
            titles = attrs.get('title') or {}
            relationships = item.get('relationships', [])
            cover = next((r.get('attributes', {}).get('fileName') for r in relationships
                          if r['type'] == 'cover_art'), None)
            creators = list(dict.fromkeys(r.get('attributes', {}).get('name') for r in relationships
                                         if r['type'] in ('author', 'artist')))
            results.append(dict(
                source='mangadex', external_id=item['id'], media_type='manga',
                title=titles.get('en') or next(iter(titles.values()), 'Manga'),
                description=(attrs.get('description') or {}).get('en'),
                release_year=attrs.get('year'),
                cover_url=f"https://uploads.mangadex.org/covers/{item['id']}/{cover}.256.jpg" if cover else None,
                creator=', '.join(c for c in creators if c),
                status={'ongoing': 'releasing', 'completed': 'finished', 'hiatus': 'on_hold',
                        'cancelled': 'cancelled'}.get(attrs.get('status'), 'unknown'),
                age_rating='adult' if attrs.get('contentRating') in ('erotica', 'pornographic') else 'safe',
                genres=[t['attributes']['name'].get('en', '') for t in attrs.get('tags', [])],
            ))
        return results
    except (httpx.HTTPError, ValueError, KeyError) as exc:
        logger.warning('MangaDex search failed: %s', exc)
        return []


async def search_kitsu(query: str, limit: int = 20) -> list[dict]:
    try:
        async with httpx.AsyncClient(timeout=15, follow_redirects=True) as client:
            response = await client.get('https://kitsu.io/api/edge/manga',
                params={'filter[text]': query, 'page[limit]': min(limit, 20)},
                headers={'Accept': 'application/vnd.api+json'})
            response.raise_for_status()
        results = []
        for item in response.json().get('data', []):
            a = item['attributes']
            date = a.get('startDate') or ''
            results.append(dict(source='kitsu', external_id=item['id'], media_type='manga',
                title=a.get('canonicalTitle') or query, description=a.get('synopsis'),
                release_year=int(date[:4]) if date[:4].isdigit() else None,
                cover_url=(a.get('posterImage') or {}).get('medium'),
                status={'current': 'releasing', 'finished': 'finished', 'tba': 'upcoming',
                        'unreleased': 'upcoming'}.get(a.get('status'), 'unknown'),
                age_rating='adult' if a.get('ageRating') == 'R18' else 'safe', genres=[],
                total_units=a.get('chapterCount')))
        return results
    except (httpx.HTTPError, ValueError, KeyError) as exc:
        logger.warning('Kitsu search failed: %s', exc)
        return []
