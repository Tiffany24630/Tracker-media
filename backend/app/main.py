from contextlib import asynccontextmanager
from fastapi import FastAPI, Depends, Query, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from sqlalchemy import select, or_, func
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
)
from app.schemas import (
    Register,
    Login,
    Token,
    UserOut,
    MediaCreate,
    MediaOut,
    SearchResult,
    LibraryUpsert,
    LibraryOut,
    ProgressIn,
)
from app.providers.simple import search_tmdb, search_anilist, search_openlibrary

@asynccontextmanager
async def lifespan(app: FastAPI):
    Base.metadata.create_all(engine)
    yield

app = FastAPI(title='Universal Media Tracker API', version='1.0.0', lifespan=lifespan)

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

@app.post('/api/v1/auth/register', response_model=Token)
def register(p: Register, s: Session = Depends(db)):
    email = p.email.lower().strip()
    if s.scalar(select(User).where(User.email == email)):
        raise HTTPException(409, 'Email already registered')
    u = User(email=email, display_name=p.display_name.strip(), password_hash=hash_password(p.password))
    s.add(u)
    s.commit()
    s.refresh(u)
    return Token(access_token=create_token(u.id))

@app.post('/api/v1/auth/login', response_model=Token)
def login(p: Login, s: Session = Depends(db)):
    email = p.email.lower().strip()
    u = s.scalar(select(User).where(User.email == email))
    if not u or not verify_password(p.password, u.password_hash):
        raise HTTPException(401, 'Invalid credentials')
    return Token(access_token=create_token(u.id))

@app.get('/api/v1/auth/me', response_model=UserOut)
def me(u: User = Depends(current_user)):
    return u

def media_out(m: Media) -> MediaOut:
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
        metadata=m.metadata_ or {}
    )

@app.get('/api/v1/media', response_model=list[MediaOut])
def list_media(query: str = '', media_type: str | None = None, limit: int = 20, offset: int = 0, s: Session = Depends(db)):
    q = select(Media).options(joinedload(Media.genres), joinedload(Media.external_ids)).order_by(Media.title).limit(min(limit, 100)).offset(offset)
    if query:
        q = q.where(Media.title.ilike(f'%{query}%'))
    if media_type:
        q = q.where(Media.media_type == media_type)
    return [media_out(x) for x in s.scalars(q).unique().all()]

@app.get('/api/v1/media/{media_id}', response_model=MediaOut)
def get_media(media_id: str, s: Session = Depends(db)):
    m = s.scalar(select(Media).options(joinedload(Media.genres), joinedload(Media.external_ids)).where(Media.id == media_id))
    if not m:
        raise HTTPException(404, 'Media not found')
    return media_out(m)

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

@app.get('/api/v1/search', response_model=list[SearchResult])
async def search(query: str = Query(min_length=1), media_type: str | None = None, limit: int = 10):
    results = []
    if media_type in (None, 'movie', 'series'):
        results += await search_tmdb(query, limit)
    if media_type in (None, 'anime', 'manga'):
        results += await search_anilist(query, limit)
    if media_type in (None, 'book', 'novel'):
        results += await search_openlibrary(query, limit)
    return [SearchResult(**x) for x in results[:limit * 3]]

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
        metadata_={'imported_from': p.source}
    )
    m.titles = [MediaTitle(title=p.title, title_type='primary')]
    m.external_ids = [MediaExternalId(provider=p.source, external_id=p.external_id)]
    s.add(m)
    s.commit()
    s.refresh(m)
    return media_out(m)

@app.get('/api/v1/library', response_model=list[LibraryOut])
def library(status: str | None = None, media_type: str | None = None, s: Session = Depends(db), u: User = Depends(current_user)):
    q = select(UserMedia).options(
        joinedload(UserMedia.media).joinedload(Media.genres),
        joinedload(UserMedia.media).joinedload(Media.external_ids)
    ).where(UserMedia.user_id == u.id)
    if status:
        q = q.where(UserMedia.status == status)
    if media_type:
        q = q.join(UserMedia.media).where(Media.media_type == media_type)
    rows = s.scalars(q.order_by(UserMedia.updated_at.desc())).unique().all()
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
        raise HTTPException(404, 'Media not found')
    e = s.scalar(select(UserMedia).where(UserMedia.user_id == u.id, UserMedia.media_id == m.id))
    if not e:
        e = UserMedia(user_id=u.id, media_id=m.id)
        s.add(e)
    status_val = getattr(p.status, 'value', str(p.status))
    source_val = getattr(p.source, 'value', str(p.source))
    e.status = status_val
    e.progress = p.progress
    if p.total is not None:
        e.total = p.total
    if p.rating is not None:
        e.rating = p.rating
    if p.notes is not None:
        e.notes = p.notes
    e.source = source_val
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
def progress(media_id: str, p: ProgressIn, s: Session = Depends(db), u: User = Depends(current_user)):
    e = s.scalar(select(UserMedia).where(UserMedia.user_id == u.id, UserMedia.media_id == media_id))
    if not e:
        raise HTTPException(404, 'Add media to library first')
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
    return {'progress': e.progress, 'status': e.status, 'unit': p.unit, 'source': src}

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
