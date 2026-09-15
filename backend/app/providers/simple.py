import logging
import httpx
from app.core.config import get_settings
from app.models.enums import MediaType

logger = logging.getLogger("app.providers.simple")

async def search_tmdb(q: str, limit: int = 10) -> list[dict]:
    s = get_settings()
    if not s.tmdb_api_key:
        return []
    try:
        async with httpx.AsyncClient(timeout=10.0) as c:
            r = await c.get(
                'https://api.themoviedb.org/3/search/multi',
                params={'api_key': s.tmdb_api_key, 'query': q, 'page': 1}
            )
            r.raise_for_status()
            data = r.json()
        out = []
        for x in data.get('results', [])[:limit]:
            t = x.get('media_type')
            mt = MediaType.MOVIE.value if t == 'movie' else MediaType.SERIES.value if t == 'tv' else None
            if not mt:
                continue
            poster = x.get('poster_path')
            cover_url = f"https://image.tmdb.org/t/p/w500{poster}" if poster else None
            date_str = x.get('release_date') or x.get('first_air_date') or ''
            year = int(date_str[:4]) if len(date_str) >= 4 and date_str[:4].isdigit() else None
            out.append({
                'source': 'tmdb',
                'external_id': str(x['id']),
                'media_type': mt,
                'title': x.get('title') or x.get('name') or q,
                'description': x.get('overview'),
                'release_year': year,
                'cover_url': cover_url
            })
        return out
    except Exception as e:
        logger.warning("TMDB search failed: %s", e)
        return []

async def search_anilist(q: str, limit: int = 10) -> list[dict]:
    query = '''
    query($search: String, $perPage: Int) {
        Page(perPage: $perPage) {
            media(search: $search) {
                id
                type
                title { romaji english native }
                description(asHtml: false)
                startDate { year }
                coverImage { large }
                format
            }
        }
    }
    '''
    try:
        async with httpx.AsyncClient(timeout=10.0) as c:
            r = await c.post(
                'https://graphql.anilist.co',
                json={'query': query, 'variables': {'search': q, 'perPage': limit}}
            )
            r.raise_for_status()
            data = r.json()
        out = []
        for x in data.get('data', {}).get('Page', {}).get('media', []):
            mt = MediaType.ANIME.value if x.get('type') == 'ANIME' else MediaType.MANGA.value if x.get('type') == 'MANGA' else None
            if not mt:
                continue
            title = x.get('title', {}).get('romaji') or x.get('title', {}).get('english') or x.get('title', {}).get('native') or q
            desc = x.get('description')
            if desc and len(desc) > 500:
                desc = desc[:497] + '...'
            out.append({
                'source': 'anilist',
                'external_id': str(x['id']),
                'media_type': mt,
                'title': title,
                'description': desc,
                'release_year': (x.get('startDate') or {}).get('year'),
                'cover_url': (x.get('coverImage') or {}).get('large')
            })
        return out
    except Exception as e:
        logger.warning("AniList search failed: %s", e)
        return []

async def search_openlibrary(q: str, limit: int = 10) -> list[dict]:
    try:
        async with httpx.AsyncClient(timeout=10.0) as c:
            r = await c.get('https://openlibrary.org/search.json', params={'q': q, 'limit': limit})
            r.raise_for_status()
            data = r.json()
        out = []
        for x in data.get('docs', [])[:limit]:
            key = x.get('key', '')
            if not key:
                continue
            ext_id = key.split('/')[-1]
            cover_id = x.get('cover_i')
            cover_url = f"https://covers.openlibrary.org/b/id/{cover_id}-L.jpg" if cover_id else None
            out.append({
                'source': 'openlibrary',
                'external_id': ext_id,
                'media_type': MediaType.BOOK.value,
                'title': x.get('title') or q,
                'description': None,
                'release_year': x.get('first_publish_year'),
                'cover_url': cover_url
            })
        return out
    except Exception as e:
        logger.warning("OpenLibrary search failed: %s", e)
        return []
