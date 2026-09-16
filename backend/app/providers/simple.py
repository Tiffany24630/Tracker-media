import logging
import httpx
from app.core.config import get_settings
from app.models.enums import MediaType

logger = logging.getLogger("app.providers.simple")

TMDB_GENRES = {
    28: "Acción", 12: "Aventura", 16: "Animación", 35: "Comedia",
    80: "Crimen", 99: "Documental", 18: "Drama", 10751: "Familia",
    14: "Fantasía", 36: "Historia", 27: "Terror", 10402: "Música",
    9648: "Misterio", 10749: "Romance", 878: "Ciencia Ficción",
    10770: "Película de TV", 53: "Suspense", 10752: "Guerra", 37: "Western",
    10759: "Acción y Aventura", 10762: "Infantil", 10763: "Noticias",
    10764: "Reality", 10765: "Ciencia Ficción y Fantasía", 10766: "Telenovela",
    10767: "Talk Show", 10768: "Guerra y Política"
}

def normalize_status(raw_status: str | None) -> str:
    if not raw_status:
        return "unknown"
    s = raw_status.lower()
    if any(k in s for k in ("finish", "end", "complet", "cancel")):
        return "finished"
    if any(k in s for k in ("releas", "airing", "publish", "ongoing", "returning")):
        return "releasing"
    if any(k in s for k in ("hiatus", "pause", "hold")):
        return "on_hold"
    if any(k in s for k in ("not_yet", "upcoming", "planned")):
        return "upcoming"
    return "releasing"

async def search_tmdb(q: str, limit: int = 10) -> list[dict]:
    s = get_settings()
    if not s.tmdb_api_key:
        return []
    try:
        async with httpx.AsyncClient(timeout=10.0) as c:
            r = await c.get(
                'https://api.themoviedb.org/3/search/multi',
                params={'api_key': s.tmdb_api_key, 'query': q, 'page': 1, 'language': 'es-ES'}
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
            genre_ids = x.get('genre_ids', [])
            genres = [TMDB_GENRES[gid] for gid in genre_ids if gid in TMDB_GENRES]
            is_adult = bool(x.get('adult', False))
            age_rating = "adult" if is_adult else "safe"
            
            out.append({
                'source': 'tmdb',
                'external_id': str(x['id']),
                'media_type': mt,
                'title': x.get('title') or x.get('name') or q,
                'description': x.get('overview'),
                'release_year': year,
                'cover_url': cover_url,
                'status': 'finished' if mt == MediaType.MOVIE.value else 'releasing',
                'genres': genres,
                'age_rating': age_rating,
                'total_units': None
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
                status
                episodes
                chapters
                volumes
                genres
                isAdult
                averageScore
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
            raw_type = x.get('type')
            mt = MediaType.ANIME.value if raw_type == 'ANIME' else MediaType.MANGA.value if raw_type == 'MANGA' else None
            if not mt:
                continue
            title = x.get('title', {}).get('romaji') or x.get('title', {}).get('english') or x.get('title', {}).get('native') or q
            desc = x.get('description')
            if desc and len(desc) > 500:
                desc = desc[:497] + '...'
            total_units = x.get('episodes') if mt == MediaType.ANIME.value else (x.get('chapters') or x.get('volumes'))
            status = normalize_status(x.get('status'))
            age_rating = "adult" if x.get('isAdult') else "safe"
            
            out.append({
                'source': 'anilist',
                'external_id': str(x['id']),
                'media_type': mt,
                'title': title,
                'description': desc,
                'release_year': (x.get('startDate') or {}).get('year'),
                'cover_url': (x.get('coverImage') or {}).get('large'),
                'status': status,
                'genres': x.get('genres', []),
                'age_rating': age_rating,
                'total_units': total_units
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
            subjects = x.get('subject', [])[:5] if isinstance(x.get('subject'), list) else []
            pages = x.get('number_of_pages_median')
            
            out.append({
                'source': 'openlibrary',
                'external_id': ext_id,
                'media_type': MediaType.BOOK.value,
                'title': x.get('title') or q,
                'description': f"Autor(es): {', '.join(x.get('author_name', ['Desconocido']))}" if x.get('author_name') else None,
                'release_year': x.get('first_publish_year'),
                'cover_url': cover_url,
                'status': 'finished',
                'genres': subjects,
                'age_rating': 'safe',
                'total_units': pages
            })
        return out
    except Exception as e:
        logger.warning("OpenLibrary search failed: %s", e)
        return []

async def search_spotify(q: str, limit: int = 10) -> list[dict]:
    s = get_settings()
    out = []
    # 1. Intentar Spotify API oficial con Client Credentials si las llaves existen
    if s.spotify_client_id and s.spotify_client_secret:
        try:
            async with httpx.AsyncClient(timeout=10.0) as c:
                token_resp = await c.post(
                    'https://accounts.spotify.com/api/token',
                    data={'grant_type': 'client_credentials'},
                    auth=(s.spotify_client_id, s.spotify_client_secret)
                )
                if token_resp.status_code == 200:
                    token = token_resp.json().get('access_token')
                    search_resp = await c.get(
                        'https://api.spotify.com/v1/search',
                        params={'q': q, 'type': 'track,album', 'limit': limit},
                        headers={'Authorization': f'Bearer {token}'}
                    )
                    if search_resp.status_code == 200:
                        data = search_resp.json()
                        tracks = data.get('tracks', {}).get('items', [])
                        for t in tracks:
                            artists = ', '.join([a.get('name', '') for a in t.get('artists', [])])
                            album = t.get('album', {})
                            images = album.get('images', [])
                            cover = images[0].get('url') if images else None
                            date_str = album.get('release_date') or ''
                            year = int(date_str[:4]) if len(date_str) >= 4 and date_str[:4].isdigit() else None
                            out.append({
                                'source': 'spotify',
                                'external_id': t.get('id'),
                                'media_type': MediaType.MUSIC.value,
                                'title': f"{t.get('name')} - {artists}" if artists else t.get('name'),
                                'description': f"Álbum: {album.get('name')} | Artistas: {artists}",
                                'release_year': year,
                                'cover_url': cover,
                                'status': 'finished',
                                'genres': ['Música'],
                                'age_rating': 'adult' if t.get('explicit') else 'safe',
                                'total_units': 1
                            })
                        if out:
                            return out[:limit]
        except Exception as e:
            logger.warning("Spotify official API search error: %s", e)

    # 2. Fallback garantizado de Música (API abierta de iTunes Search compatible con Spotify)
    try:
        async with httpx.AsyncClient(timeout=10.0) as c:
            r = await c.get(
                'https://itunes.apple.com/search',
                params={'term': q, 'media': 'music', 'entity': 'song,album', 'limit': limit}
            )
            r.raise_for_status()
            data = r.json()
        for x in data.get('results', []):
            is_song = x.get('wrapperType') == 'track'
            raw_title = x.get('trackName') if is_song else x.get('collectionName')
            artist = x.get('artistName', '')
            title = f"{raw_title} - {artist}" if artist and raw_title else (raw_title or q)
            cover = x.get('artworkUrl100', '')
            if cover and '100x100' in cover:
                cover = cover.replace('100x100bb', '600x600bb')
            date_str = x.get('releaseDate') or ''
            year = int(date_str[:4]) if len(date_str) >= 4 and date_str[:4].isdigit() else None
            genre = x.get('primaryGenreName')
            genres = [genre] if genre else ['Música']
            total = x.get('trackCount') if not is_song else 1

            out.append({
                'source': 'spotify',
                'external_id': str(x.get('trackId') or x.get('collectionId') or title),
                'media_type': MediaType.MUSIC.value if is_song else MediaType.ALBUM.value,
                'title': title,
                'description': f"Álbum: {x.get('collectionName', 'Sencillo')} | Artista: {artist}",
                'release_year': year,
                'cover_url': cover or None,
                'status': 'finished',
                'genres': genres,
                'age_rating': 'adult' if x.get('trackExplicitness') == 'explicit' else 'safe',
                'total_units': total
            })
        return out[:limit]
    except Exception as e:
        logger.warning("Music fallback search failed: %s", e)
        return out
