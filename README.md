# Universal Media Tracker

Full-stack media tracker for movies, series, anime, manga/manhua/manhwa/webtoons, books and music. Android and Web use the same FastAPI backend and PostgreSQL database.

## Current implementation

- FastAPI + SQLAlchemy + PostgreSQL
- JWT authentication
- Universal media model and external IDs
- Manual media creation
- Global search through AniList, Open Library and optional TMDB
- Import external search results into the local catalog
- Personal library and manual progress tracking
- Ratings/notes stored in library entries
- Statistics endpoint
- Redis included for future background synchronization
- Next.js web client
- Native Android client (Kotlin)
- Docker Compose for backend, PostgreSQL, Redis and web

External platform synchronization is intentionally limited to documented/public APIs. Netflix/Crunchyroll private endpoints are not used.

## 1. Run everything with Docker

Requirements: Docker Desktop with Compose.

```bash
copy .env.example .env
# edit .env and set JWT_SECRET; TMDB_API_KEY is optional
docker compose up --build
```

Open:
- Web: http://localhost:3000
- API: http://localhost:18001
- Swagger: http://localhost:18001/docs
- Health: http://localhost:18001/health

The web service waits for the backend and database health checks. `BACKEND_PORT`
can override the host API port; internal Docker traffic uses `backend:8000`.
An unavailable configured PostgreSQL database never silently switches to SQLite.

Stop with `docker compose down`. To also erase the database: `docker compose down -v`.

## 2. Run backend locally from VS Code

Requirements: Python 3.12+, PostgreSQL (or use Docker only for PostgreSQL/Redis).

```bash
cd backend
python -m venv .venv
# Windows PowerShell
.\.venv\Scripts\Activate.ps1
# Windows CMD
.venv\Scripts\activate.bat
pip install -r requirements.txt
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

For a local SQLite fallback, before starting:

```powershell
$env:DATABASE_URL="sqlite:///./tracker.db"
$env:JWT_SECRET="change-this-development-secret-please-32chars"
```

## 3. Run Web locally from VS Code

```bash
cd web
npm ci
npm run dev
```

Open http://localhost:3000.

If backend is not on localhost, create `web/.env.local`:

```env
API_INTERNAL_URL=http://YOUR_PC_IP:8000
```

## 4. Android from VS Code

The Android project is in `/android`.

Recommended: install Android Studio once to obtain the Android SDK, emulator/device drivers and Gradle tooling. You can then edit/run the project from VS Code.

Open `/android` in VS Code. Connect a physical Android phone with USB debugging enabled or start an Android emulator.

For the Android emulator the app already uses:

`http://10.0.2.2:8000/api/v1`

because `localhost` inside an emulator points to the emulator itself.

For a physical phone, edit `android/app/src/main/java/com/umt/tracker/MainActivity.kt` and change `baseUrl` to your PC LAN address, e.g. `http://192.168.1.50:8000/api/v1`. Start backend with `--host 0.0.0.0` and allow TCP port 8000 through Windows Firewall if necessary.

From a terminal with Gradle available:

```bash
cd android
gradle assembleDebug
```

Then install the generated APK from `android/app/build/outputs/apk/debug/app-debug.apk`.

Alternatively open the Android folder in Android Studio and press Run.

## 5. API flow

1. Register/login.
2. Search `/api/v1/search?query=...`.
3. Import a result with `POST /api/v1/media/import`.
4. Add it to `/api/v1/library/{media_id}`.
5. Update progress with `/api/v1/library/{media_id}/progress`.
6. Read the synchronized library from `/api/v1/library`.

## Providers

- AniList: anime/manga search, no API key required for basic public GraphQL queries.
- Open Library: book search, no API key required for basic public search.
- TMDB: movie/series search; requires `TMDB_API_KEY`.
- CheapShark: commercial PC game search, supplemented with Steam release/genre metadata and FreeToGame.
- MangaDex and Kitsu: additional manga/manhwa metadata alongside AniList and Jikan.
- Book/comic year searches pass the selected years to Open Library instead of filtering only popular works. The year is the first publication year; catalog coverage depends on the provider.

For Android connected to Docker, configure `http://YOUR_PC_IP:18001/api/v1` (or
`http://10.0.2.2:18001/api/v1` in the emulator). Local mode queries providers directly.

Backend regression checks: `python -m pip install pytest` then `python -m pytest tests -q`
from `backend`. Build Android with JDK 17: `gradlew.bat :app:assembleDebug`.

The provider layer is intentionally isolated so Trakt, Spotify, MyAnimeList and other documented integrations can be added without changing the core media model.

## 6. Public deployment

The production topology is: Netlify (web) → Render (FastAPI) → Neon (PostgreSQL).
Use `render.yaml` to create the API. Set `DATABASE_URL` to Neon’s pooled
connection string, `CORS_ORIGINS` to the exact Netlify URL, and `PUBLIC_BASE_URL`
to the Render URL. In Netlify set `NEXT_PUBLIC_API_URL` to
`hh`.

The Electron wrapper is in `/electron`; it loads the deployed web URL (or
`http://localhost:3000` during development) with Node integration disabled.
