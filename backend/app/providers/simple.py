import asyncio
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

# Mapeo genérico de géneros en inglés a español (películas, series y respaldos)
GENERIC_GENRE_MAP = {
    'action': 'Acción', 'adventure': 'Aventura', 'animation': 'Animación', 'comedy': 'Comedia',
    'crime': 'Crimen', 'documentary': 'Documental', 'drama': 'Drama', 'family': 'Familiar',
    'fantasy': 'Fantasía', 'history': 'Historia', 'horror': 'Terror', 'music': 'Musical',
    'musical': 'Musical', 'mystery': 'Misterio', 'romance': 'Romance',
    'science fiction': 'Ciencia Ficción', 'sci-fi': 'Ciencia Ficción', 'thriller': 'Suspense',
    'war': 'Guerra', 'western': 'Western', 'anime': 'Anime', 'sports': 'Deportes',
    'biography': 'Biográfico', 'espionage': 'Espionaje', 'supernatural': 'Sobrenatural',
    'sitcom': 'Comedia', 'dance': 'Danza', 'talk show': 'Talk Show', 'reality': 'Reality',
    'game show': 'Concurso', 'kids': 'Infantil', 'short': 'Cortometraje', 'sport': 'Deportes',
    'holiday': 'Navideña', 'news': 'Noticias', 'technology': 'Tecnología', 'travel': 'Viajes',
    'nature': 'Naturaleza', 'food': 'Gastronomía', 'adult': 'Adultos',
}

def _map_generic_genres(raw: list[str]) -> list[str]:
    out: list[str] = []
    for g in raw:
        if not g:
            continue
        mapped = GENERIC_GENRE_MAP.get(str(g).strip().lower())
        label = mapped or str(g).strip().title()
        if label not in out:
            out.append(label)
    return out[:5]

async def _search_itunes_movies(q: str, limit: int = 10) -> list[dict]:
    """Respaldo gratuito (sin API key) para películas usando la API de iTunes."""
    try:
        async with httpx.AsyncClient(timeout=10.0) as c:
            r = await c.get(
                'https://itunes.apple.com/search',
                params={'term': q, 'media': 'movie', 'limit': limit}
            )
            r.raise_for_status()
            data = r.json()
        out = []
        for x in data.get('results', []):
            cover = x.get('artworkUrl100', '')
            if cover and '100x100' in cover:
                cover = cover.replace('100x100bb', '600x600bb')
            date_str = x.get('releaseDate') or ''
            year = int(date_str[:4]) if len(date_str) >= 4 and date_str[:4].isdigit() else None
            out.append({
                'source': 'itunes',
                'external_id': str(x.get('trackId') or x.get('trackName') or q),
                'media_type': MediaType.MOVIE.value,
                'title': x.get('trackName') or q,
                'description': x.get('longDescription') or x.get('shortDescription') or None,
                'release_year': year,
                'cover_url': cover or None,
                'status': 'finished',
                'genres': _map_generic_genres([x.get('primaryGenreName')] if x.get('primaryGenreName') else []),
                'age_rating': 'adult' if x.get('contentAdvisoryRating') in ('R', 'NC-17', 'TV-MA') else 'safe',
                'total_units': None,
                'creator': x.get('artistName') or 'Apple TV',
            })
        return out[:limit]
    except Exception as e:
        logger.warning("iTunes movie search failed: %s", e)
        return []


async def _search_imdb(q: str, limit: int = 10, media_type: str | None = None) -> list[dict]:
    """Respaldo sin clave para títulos de cine y series mediante IMDb Suggestions."""
    try:
        async with httpx.AsyncClient(timeout=10.0) as c:
            r = await c.get(
                f"https://v2.sg.media-imdb.com/suggestion/x/{q.strip()}.json",
                params={'includeVideos': 0},
            )
            r.raise_for_status()
        out: list[dict] = []
        for value in r.json().get('d', []):
            kind = value.get('qid')
            result_type = MediaType.MOVIE.value if kind in {'movie', 'video', 'short'} else (
                MediaType.SERIES.value if kind in {'tvSeries', 'tvMiniSeries'} else None
            )
            if not result_type or (media_type not in (None, 'all', result_type)):
                continue
            image = value.get('i') or {}
            out.append({
                'source': 'imdb',
                'external_id': str(value.get('id')),
                'media_type': result_type,
                'title': value.get('l') or q,
                'description': value.get('s'),
                'release_year': value.get('y'),
                'cover_url': image.get('imageUrl'),
                'status': 'finished' if result_type == MediaType.MOVIE.value else 'unknown',
                'genres': [],
                'age_rating': 'safe',
                'total_units': None,
                'creator': None,
            })
        return out[:limit]
    except Exception as exc:
        logger.warning('IMDb suggestion search failed: %s', exc)
        return []

async def _search_tvmaze_series(q: str, limit: int = 10) -> list[dict]:
    """Respaldo gratuito (sin API key) para series usando la API de TVMaze."""
    try:
        async with httpx.AsyncClient(timeout=10.0) as c:
            r = await c.get('https://api.tvmaze.com/search/shows', params={'q': q})
            r.raise_for_status()
            data = r.json()
        out = []
        for entry in data[:limit]:
            show = entry.get('show') or {}
            img = (show.get('image') or {}).get('medium')
            premiered = show.get('premiered') or ''
            year = int(premiered[:4]) if len(premiered) >= 4 and premiered[:4].isdigit() else None
            summary = show.get('summary') or ''
            for tag in ('<p>', '</p>', '<b>', '</b>', '<i>', '</i>'):
                summary = summary.replace(tag, '')
            summary = summary.strip() or None
            status_raw = show.get('status') or ''
            if 'Ended' in status_raw:
                status = 'finished'
            elif 'Running' in status_raw:
                status = 'releasing'
            else:
                status = 'upcoming'
            genres = _map_generic_genres(show.get('genres') or []) or ['Serie']
            out.append({
                'source': 'tvmaze',
                'external_id': str(show.get('id') or show.get('name') or q),
                'media_type': MediaType.SERIES.value,
                'title': show.get('name') or q,
                'description': summary,
                'release_year': year,
                'cover_url': img,
                'status': status,
                'genres': genres,
                'age_rating': 'safe',
                'total_units': None,
                'creator': ((show.get('network') or show.get('webChannel') or {}).get('name') or 'TV'),
            })
        return out[:limit]
    except Exception as e:
        logger.warning("TVMaze series search failed: %s", e)
        return []

async def search_tmdb(q: str, limit: int = 10, media_type: str | None = None) -> list[dict]:
    s = get_settings()
    out: list[dict] = []
    if s.tmdb_api_key:
        try:
            async with httpx.AsyncClient(timeout=10.0) as c:
                search_kind = 'movie' if media_type == 'movie' else 'tv' if media_type == 'series' else 'multi'
                r = await c.get(
                    f'https://api.themoviedb.org/3/search/{search_kind}',
                    params={'api_key': s.tmdb_api_key, 'query': q, 'page': 1, 'language': 'es-ES'}
                )
                r.raise_for_status()
                data = r.json()
                raw_results = data.get('results', [])[:limit]
                async def details_for(value: dict) -> dict:
                    kind = value.get('media_type') or ('movie' if search_kind == 'movie' else 'tv')
                    try:
                        detail = await c.get(
                            f"https://api.themoviedb.org/3/{kind}/{value['id']}",
                            params={'api_key': s.tmdb_api_key, 'language': 'es-ES'},
                        )
                        return detail.json() if detail.status_code == 200 else {}
                    except Exception:
                        return {}
                details = await asyncio.gather(*(details_for(value) for value in raw_results))
            for x, detail in zip(raw_results, details, strict=False):
                t = x.get('media_type') or ('movie' if search_kind == 'movie' else 'tv')
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
                    'total_units': detail.get('number_of_episodes'),
                    'creator': ', '.join(
                        company.get('name', '')
                        for company in (detail.get('production_companies') or detail.get('networks') or [])[:2]
                        if company.get('name')
                    ) or None,
                })
        except Exception as e:
            logger.warning("TMDB search failed: %s", e)

    # Respaldo gratuito (sin API key) para películas y series
    if media_type in (None, 'all', 'movie'):
        out.extend(await _search_itunes_movies(q, limit))
    if media_type in (None, 'all', 'series'):
        out.extend(await _search_tvmaze_series(q, limit))
    out.extend(await _search_imdb(q, limit, media_type))
    return out[:limit * 2]

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
                studios(isMain: true) { nodes { name } }
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
                ,'creator': ', '.join(
                    studio.get('name', '') for studio in ((x.get('studios') or {}).get('nodes') or [])[:2]
                    if studio.get('name')
                ) or None
            })
        out.extend(await _search_jikan(q, limit))
        return out
    except Exception as e:
        logger.warning("AniList search failed: %s", e)
        return await _search_jikan(q, limit)


async def _search_jikan(q: str, limit: int = 10) -> list[dict]:
    """Segunda fuente para anime y manga mediante MyAnimeList/Jikan."""
    out: list[dict] = []
    try:
        async with httpx.AsyncClient(timeout=12.0) as c:
            for kind in ('anime', 'manga'):
                r = await c.get(
                    f'https://api.jikan.moe/v4/{kind}',
                    params={'q': q, 'limit': max(1, limit // 2)},
                )
                if r.status_code != 200:
                    continue
                for value in r.json().get('data', []):
                    creators = value.get('studios') if kind == 'anime' else value.get('authors')
                    out.append({
                        'source': 'jikan',
                        'external_id': str(value.get('mal_id')),
                        'media_type': kind,
                        'title': value.get('title') or q,
                        'description': value.get('synopsis'),
                        'release_year': value.get('year') or (
                            int(((value.get('published') or {}).get('from') or '')[:4])
                            if ((value.get('published') or {}).get('from') or '')[:4].isdigit() else None
                        ),
                        'cover_url': (((value.get('images') or {}).get('jpg') or {}).get('large_image_url')),
                        'status': normalize_status(value.get('status')),
                        'genres': [g.get('name') for g in value.get('genres', []) if g.get('name')],
                        'age_rating': 'adult' if value.get('rating', '').startswith(('R+', 'Rx')) else 'safe',
                        'total_units': value.get('episodes') or value.get('chapters') or value.get('volumes'),
                        'creator': ', '.join(x.get('name', '') for x in (creators or [])[:2] if x.get('name')) or None,
                    })
        return out
    except Exception as exc:
        logger.warning('Jikan search failed: %s', exc)
        return out

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
                ,'creator': ', '.join(x.get('author_name') or x.get('publisher') or []) or None
            })
        return out
    except Exception as e:
        logger.warning("OpenLibrary search failed: %s", e)
        return []


async def search_comics(q: str, limit: int = 10) -> list[dict]:
    """Busca cómics y novelas gráficas en Google Books."""
    try:
        async with httpx.AsyncClient(timeout=12.0) as c:
            r = await c.get(
                'https://www.googleapis.com/books/v1/volumes',
                params={'q': f'{q} subject:comics', 'maxResults': min(limit, 40), 'printType': 'books'},
            )
            r.raise_for_status()
        out: list[dict] = []
        for entry in r.json().get('items', []):
            value = entry.get('volumeInfo') or {}
            published = value.get('publishedDate') or ''
            image_links = value.get('imageLinks') or {}
            creators = value.get('authors') or ([value.get('publisher')] if value.get('publisher') else [])
            out.append({
                'source': 'googlebooks',
                'external_id': str(entry.get('id')),
                'media_type': 'comic',
                'title': value.get('title') or q,
                'description': value.get('description'),
                'release_year': int(published[:4]) if published[:4].isdigit() else None,
                'cover_url': (image_links.get('thumbnail') or image_links.get('smallThumbnail') or '').replace('http://', 'https://') or None,
                'status': 'finished',
                'genres': _map_generic_genres(value.get('categories') or ['Cómic']),
                'age_rating': 'safe',
                'total_units': value.get('pageCount'),
                'creator': ', '.join(creators) or None,
            })
        return out or await _search_openlibrary_comics(q, limit)
    except Exception as exc:
        logger.warning('Google Books comics search failed: %s', exc)
        return await _search_openlibrary_comics(q, limit)


async def _search_openlibrary_comics(q: str, limit: int = 10) -> list[dict]:
    try:
        async with httpx.AsyncClient(timeout=12.0) as c:
            r = await c.get(
                'https://openlibrary.org/search.json',
                params={'q': q, 'subject': 'comics', 'limit': limit},
            )
            r.raise_for_status()
        out: list[dict] = []
        for value in r.json().get('docs', []):
            key = value.get('key', '').split('/')[-1]
            if not key:
                continue
            cover_id = value.get('cover_i')
            creators = value.get('author_name') or value.get('publisher') or []
            out.append({
                'source': 'openlibrary',
                'external_id': f'comic-{key}',
                'media_type': 'comic',
                'title': value.get('title') or q,
                'description': None,
                'release_year': value.get('first_publish_year'),
                'cover_url': f'https://covers.openlibrary.org/b/id/{cover_id}-L.jpg' if cover_id else None,
                'status': 'finished',
                'genres': _map_generic_genres((value.get('subject') or ['Cómic'])[:5]),
                'age_rating': 'safe',
                'total_units': value.get('number_of_pages_median'),
                'creator': ', '.join(creators[:2]) or None,
            })
        return out
    except Exception as exc:
        logger.warning('OpenLibrary comics search failed: %s', exc)
        return []

# Mapeo de géneros musicales crudos (Spotify/iTunes en inglés) a géneros específicos en español
MUSIC_GENRE_MAP = {
    'hip hop': 'Hip-Hop / Rap', 'hip-hop': 'Hip-Hop / Rap', 'rap': 'Hip-Hop / Rap', 'trap': 'Trap',
    'r&b': 'R&B / Soul', 'soul': 'R&B / Soul', 'funk': 'Funk', 'gospel': 'Gospel',
    'rock': 'Rock', 'indie rock': 'Rock Indie', 'alternative rock': 'Rock Alternativo',
    'hard rock': 'Hard Rock', 'metal': 'Metal', 'punk': 'Punk', 'grunge': 'Grunge',
    'pop': 'Pop', 'indie pop': 'Indie Pop', 'k-pop': 'K-Pop', 'j-pop': 'J-Pop', 'pop latino': 'Pop Latino',
    'dance': 'Dance / Electrónica', 'electronic': 'Electrónica', 'edm': 'EDM', 'house': 'House',
    'techno': 'Techno', 'trance': 'Trance', 'dubstep': 'Dubstep', 'lo-fi': 'Lo-Fi',
    'jazz': 'Jazz', 'classical': 'Música Clásica', 'orchestral': 'Orquestal', 'soundtrack': 'Banda Sonora',
    'anime': 'Anime / Otaku', 'country': 'Country', 'folk': 'Folk', 'blues': 'Blues', 'reggae': 'Reggae',
    'latin': 'Latina', 'reggaeton': 'Reggaetón', 'salsa': 'Salsa', 'bachata': 'Bachata', 'cumbia': 'Cumbia',
    'bossa nova': 'Bossa Nova', 'afrobeat': 'Afrobeats', 'instrumental': 'Instrumental', 'disco': 'Disco',
    'ambient': 'Ambient', 'new age': 'New Age', 'world': 'World Music', 'vocal': 'Vocal',
    'singer/songwriter': 'Cantautor', 'acoustic': 'Acústico', 'christian': 'Cristiana', 'holiday': 'Navideña',
}

def _normalize_music_genres(raw_genres: list[str], fallback_query: str = '') -> list[str]:
    """Convierte géneros crudos en inglés a una lista de géneros específicos en español."""
    out: list[str] = []
    for raw in raw_genres:
        if not raw:
            continue
        key = raw.strip().lower()
        mapped = MUSIC_GENRE_MAP.get(key)
        if not mapped:
            for token, label in MUSIC_GENRE_MAP.items():
                if token in key:
                    mapped = label
                    break
        label = mapped or raw.strip().title()
        if label not in out:
            out.append(label)
    if not out:
        out.append('Música')
    return out[:4]

async def search_spotify(q: str, limit: int = 10) -> list[dict]:
    s = get_settings()
    out: list[dict] = []
    # 1. Intentar Spotify API oficial con Client Credentials si las llaves existen
    if s.spotify_client_id and s.spotify_client_secret:
        try:
            async with httpx.AsyncClient(timeout=12.0) as c:
                token_resp = await c.post(
                    'https://accounts.spotify.com/api/token',
                    data={'grant_type': 'client_credentials'},
                    auth=(s.spotify_client_id, s.spotify_client_secret)
                )
                if token_resp.status_code == 200:
                    token = token_resp.json().get('access_token')
                    headers = {'Authorization': f'Bearer {token}'}
                    search_resp = await c.get(
                        'https://api.spotify.com/v1/search',
                        params={'q': q, 'type': 'track,album', 'limit': limit, 'market': 'US'},
                        headers=headers
                    )
                    if search_resp.status_code == 200:
                        data = search_resp.json()
                        tracks = (data.get('tracks') or {}).get('items', []) or []
                        albums = (data.get('albums') or {}).get('items', []) or []

                        # Obtener géneros de los artistas en una sola llamada por lotes
                        artist_ids: list[str] = []
                        for t in tracks:
                            for a in (t.get('artists') or [])[:2]:
                                if a.get('id'):
                                    artist_ids.append(a['id'])
                        artist_genres: dict[str, list[str]] = {}
                        uniq_ids = list(dict.fromkeys(artist_ids))[:50]
                        if uniq_ids:
                            try:
                                ar = await c.get(
                                    'https://api.spotify.com/v1/artists',
                                    params={'ids': ','.join(uniq_ids)},
                                    headers=headers
                                )
                                if ar.status_code == 200:
                                    for a in ar.json().get('artists', []) or []:
                                        if a and a.get('id'):
                                            artist_genres[a['id']] = a.get('genres', []) or []
                            except Exception:
                                pass

                        for t in tracks:
                            artists_list = [a.get('name', '') for a in (t.get('artists') or [])]
                            artists = ', '.join([x for x in artists_list if x])
                            album = t.get('album', {}) or {}
                            images = album.get('images', []) or []
                            cover = images[0].get('url') if images else None
                            date_str = album.get('release_date') or ''
                            year = int(date_str[:4]) if len(date_str) >= 4 and date_str[:4].isdigit() else None
                            raw_genres: list[str] = []
                            for a in (t.get('artists') or [])[:2]:
                                raw_genres += artist_genres.get(a.get('id', ''), [])
                            out.append({
                                'source': 'spotify',
                                'external_id': t.get('id'),
                                'media_type': MediaType.MUSIC.value,
                                'title': f"{t.get('name')} - {artists}" if artists else t.get('name'),
                                'description': f"Canción de: {artists} | Álbum: {album.get('name')}",
                                'release_year': year,
                                'cover_url': cover,
                                'status': 'finished',
                                'genres': _normalize_music_genres(raw_genres, q),
                                'age_rating': 'adult' if t.get('explicit') else 'safe',
                                'total_units': 1
                                ,'creator': artists or None
                            })
                        for al in albums:
                            artists_list = [a.get('name', '') for a in (al.get('artists') or [])]
                            artists = ', '.join([x for x in artists_list if x])
                            images = al.get('images', []) or []
                            cover = images[0].get('url') if images else None
                            date_str = al.get('release_date') or ''
                            year = int(date_str[:4]) if len(date_str) >= 4 and date_str[:4].isdigit() else None
                            total = al.get('total_tracks') or None
                            out.append({
                                'source': 'spotify',
                                'external_id': al.get('id'),
                                'media_type': MediaType.ALBUM.value,
                                'title': f"{al.get('name')} - {artists}" if artists else al.get('name'),
                                'description': f"Álbum musical de: {artists} | {total or '?'} canciones",
                                'release_year': year,
                                'cover_url': cover,
                                'status': 'finished',
                                'genres': ['Música'],
                                'age_rating': 'safe',
                                'total_units': total
                                ,'creator': artists or None
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
            genres = _normalize_music_genres([genre] if genre else [], q)
            total = x.get('trackCount') if not is_song else 1

            out.append({
                'source': 'itunes',
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
                ,'creator': artist or None
            })
        return out[:limit]
    except Exception as e:
        logger.warning("Music fallback search failed: %s", e)
        return out
