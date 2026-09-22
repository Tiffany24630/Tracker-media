from contextlib import asynccontextmanager
from typing import Any
from datetime import datetime, timezone
import random
import unicodedata
from fastapi import FastAPI, Depends, Query, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from sqlalchemy import select, or_, func, and_, not_
from sqlalchemy.orm import Session, joinedload
from app.core.config import get_settings
from app.core.exceptions import AppError
from app.core.security import hash_password, verify_password, create_token
from app.db.base import Base
from app.db.session import engine
from app.deps import db, current_user
from app.models import (
    User,
    Media,
    MediaExternalId,
    MediaTitle,
    MediaGenre,
    MediaUnit,
    UserMedia,
    UserProgress,
    UserList,
    UserListItem,
    Notification,
)
from app.schemas import (
    Register,
    Login,
    Token,
    UserOut,
    ProfileUpdate,
    ChangePasswordRequest,
    MediaCreate,
    CustomMediaCreate,
    ExistsCheckResult,
    MediaOut,
    SearchResult,
    LibraryUpsert,
    LibraryOut,
    RecommendationItem,
    ProgressIn,
    NotificationOut,
)
from app.providers.simple import search_tmdb, search_anilist, search_comics, search_openlibrary, search_spotify


def _normalized_text(value: str) -> str:
    return ''.join(
        char for char in unicodedata.normalize('NFKD', value.casefold().strip())
        if not unicodedata.combining(char)
    )


GENRE_ALIASES = {
    'accion': {'action', 'accion'},
    'aventura': {'adventure', 'aventura'},
    'ciencia ficcion': {'science fiction', 'sci-fi', 'sci fi', 'ciencia ficcion'},
    'comedia': {'comedy', 'comedia'},
    'crimen': {'crime', 'crimen'},
    'deportes': {'sports', 'sport', 'deportes'},
    'documental': {'documentary', 'documental'},
    'fantasia': {'fantasy', 'fantasia'},
    'historico': {'history', 'historical', 'historia', 'historico'},
    'misterio': {'mystery', 'misterio'},
    'romance': {'romance'},
    'suspense': {'thriller', 'suspense'},
    'terror': {'horror', 'terror'},
}


def _genre_matches(selected: str, actual: str) -> bool:
    wanted = _normalized_text(selected)
    found = _normalized_text(actual)
    aliases = GENRE_ALIASES.get(wanted, {wanted})
    return any(alias == found or alias in found or found in alias for alias in aliases)


def _passes_genres(genres: list[str], included: list[str], excluded: list[str]) -> bool:
    included = [part.strip() for value in included for part in value.split(',') if part.strip()]
    excluded = [part.strip() for value in excluded for part in value.split(',') if part.strip()]
    has_included = not included or any(
        _genre_matches(selected, actual) for selected in included for actual in genres
    )
    has_excluded = any(
        _genre_matches(selected, actual) for selected in excluded for actual in genres
    )
    return has_included and not has_excluded

@asynccontextmanager
async def lifespan(app: FastAPI):
    Base.metadata.create_all(engine)
    from sqlalchemy import text
    with engine.begin() as conn:
        for stmt in [
            "ALTER TABLE users ADD COLUMN avatar_url VARCHAR(2048)",
            "ALTER TABLE users ADD COLUMN notify_new_releases BOOLEAN DEFAULT 1",
            "ALTER TABLE users ADD COLUMN notification_settings JSON DEFAULT '{}'"
        ]:
            try:
                conn.execute(text(stmt))
            except Exception:
                pass
    yield

app = FastAPI(title='Universal Media Tracker API', version='1.1.0', lifespan=lifespan)

settings = get_settings()
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_list,
    allow_origin_regex=r"^https?://(localhost|127\.0\.0\.1|10\.0\.2\.2|192\.168\.\d+\.\d+|172\.(1[6-9]|2\d|3[0-1])\.\d+\.\d+|10\.\d+\.\d+\.\d+)(:\d+)?$",
    allow_credentials=True,
    allow_methods=['*'],
    allow_headers=['*'],
)

@app.exception_handler(AppError)
async def app_error(_, e: AppError):
    from fastapi.responses import JSONResponse
    return JSONResponse(status_code=e.status_code, content={'error': {'code': e.code, 'message': e.message}})

@app.get('/health')
def health():
    return {'status': 'ok'}

# -------------------------------------------------------------
# Autenticación y Perfil (Settings)
# -------------------------------------------------------------
@app.post('/api/v1/auth/register', response_model=Token)
def register(p: Register, s: Session = Depends(db)):
    email = p.email.lower().strip()
    if s.scalar(select(User).where(User.email == email)):
        raise HTTPException(409, 'El correo electrónico ya está registrado')
    u = User(
        email=email,
        display_name=p.display_name.strip(),
        password_hash=hash_password(p.password),
        avatar_url=f"https://api.dicebear.com/9.x/shapes/svg?seed={email}"
    )
    s.add(u)
    s.commit()
    s.refresh(u)
    return Token(access_token=create_token(u.id))

@app.post('/api/v1/auth/login', response_model=Token)
def login(p: Login, s: Session = Depends(db)):
    email = p.email.lower().strip()
    u = s.scalar(select(User).where(User.email == email))
    if not u or not verify_password(p.password, u.password_hash):
        raise HTTPException(401, 'Credenciales incorrectas')
    return Token(access_token=create_token(u.id))

@app.get('/api/v1/auth/me', response_model=UserOut)
def me(u: User = Depends(current_user)):
    return u

@app.put('/api/v1/auth/profile', response_model=UserOut)
def update_profile(p: ProfileUpdate, s: Session = Depends(db), u: User = Depends(current_user)):
    if p.display_name is not None:
        name = p.display_name.strip()
        if not name:
            raise HTTPException(400, 'El nombre no puede estar vacío')
        u.display_name = name
    if p.avatar_url is not None:
        u.avatar_url = p.avatar_url.strip() or None
    if p.notify_new_releases is not None:
        u.notify_new_releases = p.notify_new_releases
    if p.notification_settings is not None:
        u.notification_settings = p.notification_settings
    s.commit()
    s.refresh(u)
    return u

@app.post('/api/v1/auth/change-password')
def change_password(p: ChangePasswordRequest, s: Session = Depends(db), u: User = Depends(current_user)):
    if not verify_password(p.current_password, u.password_hash):
        raise HTTPException(400, 'La contraseña actual es incorrecta')
    u.password_hash = hash_password(p.new_password)
    s.commit()
    return {'status': 'success', 'message': 'Contraseña actualizada correctamente'}

# -------------------------------------------------------------
# Catálogo de Medios y Filtros Avanzados
# -------------------------------------------------------------
def media_out(m: Media) -> MediaOut:
    meta = m.metadata_ or {}
    total_units = meta.get('total_units')
    age_rating = meta.get('age_rating')
    return MediaOut(
        id=str(m.id),
        media_type=m.media_type,
        title=m.title,
        description=m.description,
        release_year=m.release_year,
        status=m.status,
        original_language=m.original_language,
        cover_url=m.cover_url,
        genres=[g.name for g in m.genres],
        external_ids=[{'provider': e.provider, 'external_id': e.external_id, 'url': e.url} for e in m.external_ids],
        metadata=meta,
        total_units=total_units,
        age_rating=age_rating,
        creator=meta.get('creator') or meta.get('studio') or meta.get('publisher')
    )

@app.get('/api/v1/media', response_model=list[MediaOut])
def list_media(
    query: str = '',
    media_type: str | None = None,
    media_status: str | None = None,
    include_genres: list[str] = Query(default=[]),
    exclude_genres: list[str] = Query(default=[]),
    year_from: int | None = None,
    year_to: int | None = None,
    years: list[int] = Query(default=[]),
    age_rating: str | None = None,
    min_units: int | None = None,
    max_units: int | None = None,
    limit: int = 50,
    offset: int = 0,
    s: Session = Depends(db)
):
    q = select(Media).options(joinedload(Media.genres), joinedload(Media.external_ids))

    if query:
        q = q.where(Media.title.ilike(f'%{query}%'))
    if media_type and media_type != 'all':
        q = q.where(Media.media_type == media_type)
    if media_status and media_status != 'all':
        q = q.where(Media.status == media_status)
    if year_from:
        q = q.where(Media.release_year >= year_from)
    if year_to:
        q = q.where(Media.release_year <= year_to)
    if years:
        q = q.where(Media.release_year.in_(set(years)))

    # Inclusión de géneros (debe incluir los seleccionados)
    # La normalización de acentos y alias se aplica en memoria para fuentes existentes.

    # Exclusión de géneros (NO debe contener ninguno de los excluidos)


    rows = s.scalars(q.order_by(Media.title).limit(500)).unique().all()
    rows = [row for row in rows if _passes_genres(
        [genre.name for genre in row.genres], include_genres, exclude_genres
    )]
    rows = rows[offset:offset + min(limit, 100)]
    results = [media_out(x) for x in rows]

    if age_rating and age_rating != 'all':
        results = [x for x in results if (x.age_rating or 'safe') == age_rating]

    if min_units is not None:
        results = [x for x in results if x.total_units is not None and x.total_units >= min_units]

    if max_units is not None:
        results = [x for x in results if x.total_units is not None and x.total_units <= max_units]

    return results

@app.get('/api/v1/media/check-exists', response_model=ExistsCheckResult)
def check_media_exists(
    title: str = Query(min_length=1),
    media_type: str | None = None,
    s: Session = Depends(db)
):
    import unicodedata
    def strip_accents(text: str) -> str:
        return ''.join(c for c in unicodedata.normalize('NFD', text) if unicodedata.category(c) != 'Mn').lower().strip()

    clean = title.strip()
    clean_norm = strip_accents(clean)
    words = [w for w in clean_norm.split() if len(w) > 2]

    q = select(Media).options(joinedload(Media.genres), joinedload(Media.external_ids))
    if media_type and media_type != 'all':
        q = q.where(Media.media_type == media_type)

    candidates = s.scalars(q.limit(200)).unique().all()
    for cand in candidates:
        cand_norm = strip_accents(cand.title)
        if cand_norm == clean_norm:
            return ExistsCheckResult(
                exists=True,
                match=media_out(cand),
                similarity_message=f"Se encontró una coincidencia exacta para '{cand.title}' en la base de datos."
            )
        if (clean_norm in cand_norm) or (cand_norm in clean_norm):
            return ExistsCheckResult(
                exists=True,
                match=media_out(cand),
                similarity_message=f"Se encontró un título similar: '{cand.title}' ({cand.media_type}, {cand.release_year or 'Año desc.'})."
            )
        if words and sum(1 for w in words if w in cand_norm) >= max(1, len(words) // 2 + 1):
            return ExistsCheckResult(
                exists=True,
                match=media_out(cand),
                similarity_message=f"Se encontró un título similar: '{cand.title}' ({cand.media_type}, {cand.release_year or 'Año desc.'})."
            )

    return ExistsCheckResult(exists=False, match=None)

@app.get('/api/v1/media/{media_id}', response_model=MediaOut)
def get_media(media_id: str, s: Session = Depends(db)):
    m = s.scalar(select(Media).options(joinedload(Media.genres), joinedload(Media.external_ids)).where(Media.id == media_id))
    if not m:
        raise HTTPException(404, 'Medio no encontrado')
    return media_out(m)

@app.post('/api/v1/media/custom', response_model=LibraryOut)
def create_custom_media(
    p: CustomMediaCreate,
    s: Session = Depends(db),
    u: User = Depends(current_user)
):
    import uuid
    media_id = str(uuid.uuid4())
    meta = {
        'total_units': p.total_units,
        'age_rating': p.age_rating,
        'custom': True
    }
    m = Media(
        id=media_id,
        media_type=p.media_type,
        title=p.title.strip(),
        description=p.description,
        release_year=p.release_year,
        status=p.status,
        cover_url=p.cover_url,
        metadata_=meta
    )
    m.titles = [MediaTitle(media_id=media_id, title=m.title, title_type='primary')]
    m.genres = [MediaGenre(media_id=media_id, name=g.strip()) for g in set(p.genres) if g.strip()]
    m.external_ids = [MediaExternalId(media_id=media_id, provider='manual', external_id=media_id)]
    s.add(m)
    s.flush()

    um = UserMedia(
        user_id=str(u.id),
        media_id=media_id,
        status=p.initial_status or 'planned',
        progress=0.0,
        total=float(p.total_units) if p.total_units else None,
        source='manual'
    )
    s.add(um)
    s.commit()
    s.refresh(um)
    m_loaded = s.scalar(select(Media).options(joinedload(Media.genres), joinedload(Media.external_ids)).where(Media.id == m.id))
    return LibraryOut(
        id=str(um.id),
        media_id=str(um.media_id),
        status=um.status,
        progress=um.progress,
        total=um.total,
        rating=um.rating,
        notes=um.notes,
        source=um.source,
        media=media_out(m_loaded)
    )

@app.post('/api/v1/media', response_model=MediaOut)
def create_media(p: MediaCreate, s: Session = Depends(db), u: User = Depends(current_user)):
    media_type_val = getattr(p.media_type, 'value', str(p.media_type))
    status_val = getattr(p.status, 'value', str(p.status))
    m = Media(
        media_type=media_type_val,
        title=p.title.strip(),
        description=p.description,
        release_year=p.release_year,
        release_date=p.release_date,
        status=status_val,
        original_language=p.original_language,
        cover_url=str(p.cover_url) if p.cover_url else None,
        metadata_=p.metadata
    )
    m.titles = [MediaTitle(title=m.title, language_code=p.original_language, title_type='primary')]
    m.genres = [MediaGenre(name=g.strip()) for g in set(p.genres) if g.strip()]
    m.external_ids = [MediaExternalId(provider=e.provider.lower(), external_id=e.external_id, url=str(e.url) if e.url else None) for e in p.external_ids]
    s.add(m)
    s.commit()
    s.refresh(m)
    return media_out(m)

# -------------------------------------------------------------
# Buscador Universal por Tipo y Filtros
# -------------------------------------------------------------
@app.get('/api/v1/search', response_model=list[SearchResult])
async def search(
    query: str = Query(min_length=1),
    media_type: str | None = None,
    media_status: str | None = None,
    include_genres: list[str] = Query(default=[]),
    exclude_genres: list[str] = Query(default=[]),
    year_from: int | None = None,
    year_to: int | None = None,
    years: list[int] = Query(default=[]),
    year: str | None = None,
    age_rating: str | None = None,
    min_units: int | None = None,
    max_units: int | None = None,
    limit: int = 15,
    s: Session = Depends(db),
    u: User = Depends(current_user),
):
    if year:
        years = list(dict.fromkeys(year_value for part in year.split(',') if (year_value := int(part.strip()))))
    results: list[dict] = []
    
    # Búsqueda selectiva según el tipo para mayor velocidad y orden
    if media_type in (None, 'all', 'movie', 'series'):
        results += await search_tmdb(query, limit, media_type)
    if media_type in (None, 'all', 'anime', 'manga'):
        results += await search_anilist(query, limit)
    if media_type in (None, 'all', 'book', 'novel'):
        results += await search_openlibrary(query, limit)
    if media_type in (None, 'all', 'comic'):
        results += await search_comics(query, limit)
    if media_type in (None, 'all', 'music', 'album'):
        results += await search_spotify(query, limit)

    parsed = [SearchResult(**x) for x in results]

    # Aplicar filtros adicionales en memoria para resultados externos
    filtered = []
    library_external_ids = set(s.execute(
        select(MediaExternalId.provider, MediaExternalId.external_id)
        .join(Media, Media.id == MediaExternalId.media_id)
        .join(UserMedia, UserMedia.media_id == Media.id)
        .where(UserMedia.user_id == u.id)
    ).all())
    seen: set[tuple[str, str]] = set()

    for item in parsed:
        if media_type and media_type != 'all' and item.media_type != media_type:
            continue
        if media_status and media_status != 'all' and item.status != media_status:
            continue
        if year_from and item.release_year and item.release_year < year_from:
            continue
        if year_to and item.release_year and item.release_year > year_to:
            continue
        if years and item.release_year not in years:
            continue
        if age_rating and age_rating != 'all' and (item.age_rating or 'safe') != age_rating:
            continue
        if min_units is not None and item.total_units is not None and item.total_units < min_units:
            continue
        if max_units is not None and item.total_units is not None and item.total_units > max_units:
            continue

        # Inclusión (debe tener al menos uno si se especificaron)
        if not _passes_genres(item.genres, include_genres, exclude_genres):
            continue
        # Exclusión (NO debe tener ninguno de los excluidos)
        item.in_library = (item.source, item.external_id) in library_external_ids
        key = (_normalized_text(item.title), item.media_type)
        if key in seen:
            continue
        seen.add(key)
        filtered.append(item)

    return filtered[:limit * 3]

@app.post('/api/v1/media/import', response_model=MediaOut)
def import_media(p: SearchResult, s: Session = Depends(db), u: User = Depends(current_user)):
    existing = s.scalar(
        select(Media).join(Media.external_ids).where(
            MediaExternalId.provider == p.source,
            MediaExternalId.external_id == p.external_id
        )
    )
    if existing:
        existing = s.scalar(select(Media).options(joinedload(Media.genres), joinedload(Media.external_ids)).where(Media.id == existing.id))
        return media_out(existing)

    m = Media(
        media_type=p.media_type,
        title=p.title,
        description=p.description,
        release_year=p.release_year,
        cover_url=p.cover_url,
        status=p.status or 'unknown',
        metadata_={
            'imported_from': p.source,
            'total_units': p.total_units,
            'age_rating': p.age_rating,
            'creator': p.creator
        }
    )
    m.titles = [MediaTitle(title=p.title, title_type='primary')]
    m.genres = [MediaGenre(name=g.strip()) for g in set(p.genres) if g.strip()]
    m.external_ids = [MediaExternalId(provider=p.source, external_id=p.external_id)]
    s.add(m)
    s.commit()
    s.refresh(m)
    return media_out(m)

# -------------------------------------------------------------
# Biblioteca Personal (con validación de límite de capítulos)
# -------------------------------------------------------------
@app.get('/api/v1/library', response_model=list[LibraryOut])
def library(
    status: str | None = None,
    media_type: str | None = None,
    include_genres: list[str] = Query(default=[]),
    exclude_genres: list[str] = Query(default=[]),
    years: list[int] = Query(default=[]),
    year: str | None = None,
    s: Session = Depends(db),
    u: User = Depends(current_user)
):
    q = select(UserMedia).options(
        joinedload(UserMedia.media).joinedload(Media.genres),
        joinedload(UserMedia.media).joinedload(Media.external_ids)
    ).where(UserMedia.user_id == u.id)

    if status and status != 'all':
        q = q.where(UserMedia.status == status)
    if media_type and media_type != 'all':
        q = q.join(UserMedia.media).where(Media.media_type == media_type)

    rows = s.scalars(q.order_by(UserMedia.updated_at.desc())).unique().all()
    if year:
        years = list(dict.fromkeys(int(part.strip()) for part in year.split(',') if part.strip().isdigit()))
    rows = [row for row in rows if (not years or row.media.release_year in years) and _passes_genres(
        [genre.name for genre in row.media.genres], include_genres, exclude_genres
    )]
    return [
        LibraryOut(
            id=str(x.id),
            media_id=str(x.media_id),
            status=x.status,
            progress=x.progress,
            total=x.total,
            rating=x.rating,
            notes=x.notes,
            source=x.source,
            media=media_out(x.media)
        )
        for x in rows
    ]

@app.put('/api/v1/library/{media_id}', response_model=LibraryOut)
def upsert_library(media_id: str, p: LibraryUpsert, s: Session = Depends(db), u: User = Depends(current_user)):
    m = s.get(Media, media_id)
    if not m:
        raise HTTPException(404, 'Medio no encontrado')
    e = s.scalar(select(UserMedia).where(UserMedia.user_id == u.id, UserMedia.media_id == m.id))
    if not e:
        e = UserMedia(user_id=u.id, media_id=m.id)
        # Pre-cargar total de unidades si viene del metadata
        meta_total = (m.metadata_ or {}).get('total_units')
        if meta_total and p.total is None:
            e.total = float(meta_total)
        s.add(e)

    status_val = getattr(p.status, 'value', str(p.status))
    source_val = getattr(p.source, 'value', str(p.source))
    
    # Validar no exceder el total si está definido
    effective_total = p.total if p.total is not None else e.total
    if effective_total and effective_total > 0 and p.progress > effective_total:
        raise HTTPException(
            400,
            f"El progreso ({int(p.progress)}) no puede superar el máximo ({int(effective_total)}) según la base de datos."
        )

    e.status = status_val
    e.progress = p.progress
    if p.total is not None:
        e.total = p.total
    elif e.total is None and (m.metadata_ or {}).get('total_units'):
        e.total = float(m.metadata_['total_units'])

    if p.rating is not None:
        e.rating = p.rating
    if p.notes is not None:
        e.notes = p.notes
    e.source = source_val

    # Auto-completar si progreso alcanza el total
    if e.total and e.progress >= e.total:
        e.status = 'completed'

    s.commit()
    s.refresh(e)
    m = s.scalar(select(Media).options(joinedload(Media.genres), joinedload(Media.external_ids)).where(Media.id == m.id))
    return LibraryOut(
        id=str(e.id),
        media_id=str(e.media_id),
        status=e.status,
        progress=e.progress,
        total=e.total,
        rating=e.rating,
        notes=e.notes,
        source=e.source,
        media=media_out(m)
    )

@app.delete('/api/v1/library/{media_id}', status_code=204)
def remove_library(media_id: str, s: Session = Depends(db), u: User = Depends(current_user)):
    e = s.scalar(select(UserMedia).where(UserMedia.user_id == u.id, UserMedia.media_id == media_id))
    if e:
        s.delete(e)
        s.commit()

@app.post('/api/v1/library/{media_id}/progress')
def update_library_progress(media_id: str, p: ProgressIn, s: Session = Depends(db), u: User = Depends(current_user)):
    e = s.scalar(select(UserMedia).where(UserMedia.user_id == u.id, UserMedia.media_id == media_id))
    if not e:
        raise HTTPException(404, 'Primero debes añadir el medio a tu biblioteca')
    
    # REQUERIMIENTO 5: Validar que no exceda el monto máximo según la DB
    if e.total and e.total > 0 and p.value > e.total:
        raise HTTPException(
            400,
            f"Has alcanzado el límite máximo ({int(e.total)}) de capítulos/tomos/páginas disponibles en la base de datos."
        )

    e.progress = p.value
    if e.progress > 0 and e.status == 'planned':
        e.status = 'in_progress'
    if e.total and e.progress >= e.total:
        e.status = 'completed'

    src = getattr(p.source, 'value', str(p.source))
    e.source = src
    s.add(UserProgress(user_media_id=e.id, value=p.value, unit=str(p.unit), source=src))
    s.commit()
    s.refresh(e)
    return {
        'progress': e.progress,
        'total': e.total,
        'status': e.status,
        'unit': p.unit,
        'source': src
    }

# -------------------------------------------------------------
# Motor de Recomendaciones (Content-Based con Fallback)
# -------------------------------------------------------------
@app.get('/api/v1/recommendations', response_model=list[RecommendationItem])
def get_recommendations(
    media_type: str | None = None,
    limit: int = 12,
    refresh: str | None = None,
    exclude_ids: str | None = None,
    s: Session = Depends(db),
    u: User = Depends(current_user),
):
    # 1. Obtener los medios que el usuario ya tiene para no recomendárselos
    user_entries = s.scalars(
        select(UserMedia).options(
            joinedload(UserMedia.media).joinedload(Media.genres)
        ).where(UserMedia.user_id == u.id)
    ).unique().all()

    tracked_ids = {entry.media_id for entry in user_entries}

    # 2. Calcular afinidad por géneros y tipo de medio
    genre_weights: dict[str, float] = {}
    type_weights: dict[str, float] = {}

    for entry in user_entries:
        w = 1.0
        if entry.status == 'completed':
            w += 2.0
        elif entry.status == 'in_progress':
            w += 1.5
        elif entry.status == 'dropped':
            w -= 2.5

        if entry.rating:
            if entry.rating >= 8:
                w += 2.5
            elif entry.rating <= 4:
                w -= 2.0

        m_type = entry.media.media_type
        type_weights[m_type] = type_weights.get(m_type, 0.0) + w

        for g in entry.media.genres:
            name = g.name.strip().title()
            genre_weights[name] = genre_weights.get(name, 0.0) + w

    # 3. Obtener candidatos del catálogo que no estén en la biblioteca
    q = select(Media).options(joinedload(Media.genres), joinedload(Media.external_ids)).where(~Media.id.in_(tracked_ids) if tracked_ids else True)
    if media_type and media_type != 'all':
        q = q.where(Media.media_type == media_type)
    refresh_exclusions = {
        value.strip() for value in (exclude_ids or '').split(',') if value.strip()
    }
    if refresh_exclusions:
        q = q.where(~Media.id.in_(refresh_exclusions))

    candidates = s.scalars(q.limit(500)).unique().all()
    rng = random.Random(refresh or f"initial:{u.id}:{media_type or 'all'}")
    rng.shuffle(candidates)

    scored_items: list[RecommendationItem] = []
    top_positive_genres = {k for k, v in genre_weights.items() if v > 0}

    for c in candidates:
        score = 0.0
        matched_genres: list[str] = []
        for g in c.genres:
            g_name = g.name.strip().title()
            if g_name in genre_weights:
                score += genre_weights[g_name]
                if genre_weights[g_name] > 0:
                    matched_genres.append(g_name)

        if c.media_type in type_weights:
            score += type_weights[c.media_type] * 0.5

        # Motivo de recomendación
        if matched_genres:
            reason = f"Porque te gustan obras con géneros como {', '.join(matched_genres[:3])}"
        elif c.media_type in type_weights and type_weights[c.media_type] > 0:
            reason = f"Popular en tu formato favorito: {c.media_type.title()}"
        else:
            reason = "Obra destacada en el catálogo general"

        scored_items.append(
            RecommendationItem(
                media=media_out(c),
                score=round(max(0.1, score), 2),
                reason=reason,
                matching_genres=matched_genres
            )
        )

    # Ordenar por puntaje descendente
    scored_items.sort(key=lambda x: (x.score, rng.random()), reverse=True)
    return scored_items[:limit]

# -------------------------------------------------------------
# Estadísticas Generales
# -------------------------------------------------------------
@app.get('/api/v1/stats')
def stats(s: Session = Depends(db), u: User = Depends(current_user)):
    rows = s.scalars(select(UserMedia).where(UserMedia.user_id == u.id)).all()
    counts = {}
    for r in rows:
        counts[r.status] = counts.get(r.status, 0) + 1
    return {
        'total': len(rows),
        'by_status': counts,
        'completed': counts.get('completed', 0),
        'in_progress': counts.get('in_progress', 0),
        'planned': counts.get('planned', 0),
        'on_hold': counts.get('on_hold', 0),
        'dropped': counts.get('dropped', 0)
    }

# -------------------------------------------------------------
# Notificaciones
# -------------------------------------------------------------
@app.get('/api/v1/notifications', response_model=list[NotificationOut])
def list_notifications(s: Session = Depends(db), u: User = Depends(current_user)):
    notes = s.scalars(
        select(Notification)
        .where(Notification.user_id == str(u.id))
        .order_by(Notification.created_at.desc())
        .limit(50)
    ).all()
    return [NotificationOut.model_validate(n) for n in notes]

@app.post('/api/v1/notifications/{notif_id}/read')
def mark_notification_read(notif_id: str, s: Session = Depends(db), u: User = Depends(current_user)):
    n = s.scalar(select(Notification).where(Notification.id == notif_id, Notification.user_id == str(u.id)))
    if not n:
        raise HTTPException(404, 'Notificación no encontrada')
    n.is_read = True
    s.commit()
    return {'status': 'ok'}

@app.post('/api/v1/notifications/read-all')
def mark_all_notifications_read(s: Session = Depends(db), u: User = Depends(current_user)):
    from sqlalchemy import update
    s.execute(
        update(Notification)
        .where(Notification.user_id == str(u.id))
        .values(is_read=True)
    )
    s.commit()
    return {'status': 'ok'}

@app.post('/api/v1/notifications/test', response_model=NotificationOut)
def create_test_notification(s: Session = Depends(db), u: User = Depends(current_user)):
    updated_at = datetime.now(timezone.utc).strftime('%d/%m/%Y %H:%M UTC')
    n = Notification(
        user_id=str(u.id),
        title="¡Nuevo capítulo disponible!",
        message=f"Contenido actualizado el {updated_at}.",
        media_title="Serie de Prueba",
        is_read=False
    )
    s.add(n)
    s.commit()
    s.refresh(n)
    return NotificationOut.model_validate(n)

@app.post('/api/v1/notifications/check-updates')
def check_updates_notifications(s: Session = Depends(db), u: User = Depends(current_user)):
    user_items = s.scalars(
        select(UserMedia)
        .options(joinedload(UserMedia.media))
        .where(UserMedia.user_id == str(u.id), UserMedia.status.in_(['in_progress', 'planned']))
    ).all()
    created_count = 0
    for item in user_items:
        if item.media and item.media.status == 'releasing':
            existing = s.scalar(
                select(Notification).where(
                    Notification.user_id == str(u.id),
                    Notification.media_id == str(item.media.id)
                )
            )
            if not existing:
                media_updated = getattr(item.media, 'updated_at', None) or datetime.now(timezone.utc)
                if media_updated.tzinfo is None:
                    media_updated = media_updated.replace(tzinfo=timezone.utc)
                date_label = media_updated.astimezone(timezone.utc).strftime('%d/%m/%Y %H:%M UTC')
                n = Notification(
                    user_id=str(u.id),
                    title=f"Nuevo lanzamiento: {item.media.title}",
                    message=f"{item.media.title} se actualizó el {date_label}.",
                    media_id=str(item.media.id),
                    media_title=item.media.title,
                    is_read=False
                )
                s.add(n)
                created_count += 1
    s.commit()
    return {'status': 'ok', 'new_notifications': created_count}
