'use client';

import { FormEvent, useEffect, useMemo, useState } from 'react';
import { api, FilterOptions } from '@/lib/api';
import type {
  LibraryEntry,
  LibraryStats,
  MediaItem,
  RecommendationItem,
  SearchResult,
  UserProfile,
} from '@/lib/types';

const STATUS_LABELS: Record<string, { label: string; color: string }> = {
  all: { label: 'Todos', color: '#a782ff' },
  in_progress: { label: 'En progreso', color: '#76e6d5' },
  planned: { label: 'Planificado', color: '#e5c07b' },
  completed: { label: 'Completado', color: '#98c379' },
  on_hold: { label: 'En pausa', color: '#61afef' },
  dropped: { label: 'Abandonado', color: '#e06c75' },
};

const MEDIA_CATEGORIES: Array<{ key: string; label: string; icon: string }> = [
  { key: 'all', label: 'Todos', icon: '🌐' },
  { key: 'anime', label: 'Anime', icon: '⛩️' },
  { key: 'manga', label: 'Manga / Manhwa', icon: '📖' },
  { key: 'movie', label: 'Películas', icon: '🎬' },
  { key: 'series', label: 'Series', icon: '📺' },
  { key: 'book', label: 'Libros', icon: '📚' },
];

const POPULAR_GENRES = [
  'Acción',
  'Aventura',
  'Comedia',
  'Drama',
  'Fantasía',
  'Ciencia Ficción',
  'Romance',
  'Sobrenatural',
  'Misterio',
  'Terror',
  'Psicológico',
  'Recuentos de la vida',
  'Música',
  'Suspense',
];

const PRESET_AVATARS = [
  'https://api.dicebear.com/7.x/bottts/svg?seed=Felix',
  'https://api.dicebear.com/7.x/bottts/svg?seed=Luna',
  'https://api.dicebear.com/7.x/bottts/svg?seed=Aiden',
  'https://api.dicebear.com/7.x/bottts/svg?seed=Milo',
  'https://api.dicebear.com/7.x/bottts/svg?seed=Zoe',
  'https://api.dicebear.com/7.x/bottts/svg?seed=Shadow',
];

export function Dashboard() {
  const [token, setToken] = useState<string | null>(null);
  const [user, setUser] = useState<UserProfile | null>(null);
  const [authMode, setAuthMode] = useState<'login' | 'register'>('login');

  // Navegación principal de vistas
  const [mainView, setMainView] = useState<'library' | 'explore' | 'settings'>('library');
  const [selectedType, setSelectedType] = useState<string>('all');

  // Datos
  const [items, setItems] = useState<MediaItem[]>([]);
  const [library, setLibrary] = useState<LibraryEntry[]>([]);
  const [recommendations, setRecommendations] = useState<RecommendationItem[]>([]);
  const [searchResults, setSearchResults] = useState<SearchResult[]>([]);
  const [stats, setStats] = useState<LibraryStats | null>(null);

  // Filtros Avanzados
  const [showAdvancedFilters, setShowAdvancedFilters] = useState(false);
  const [statusFilter, setStatusFilter] = useState('all');
  const [includeGenres, setIncludeGenres] = useState<string[]>([]);
  const [excludeGenres, setExcludeGenres] = useState<string[]>([]);
  const [yearFrom, setYearFrom] = useState<string>('');
  const [yearTo, setYearTo] = useState<string>('');
  const [publicationStatus, setPublicationStatus] = useState<string>('all');
  const [ageRatingFilter, setAgeRatingFilter] = useState<string>('all');

  // Buscador
  const [searchQuery, setSearchQuery] = useState('');
  const [searching, setSearching] = useState(false);

  // Estados de edición manual de progreso
  const [editingEntryId, setEditingEntryId] = useState<string | null>(null);
  const [manualProgress, setManualProgress] = useState<number>(0);
  const [manualTotal, setManualTotal] = useState<string>('');

  // Settings form states
  const [settingName, setSettingName] = useState('');
  const [settingAvatar, setSettingAvatar] = useState('');
  const [settingNotify, setSettingNotify] = useState(true);
  const [currPass, setCurrPass] = useState('');
  const [newPass, setNewPass] = useState('');

  // Mensajería y carga
  const [message, setMessage] = useState<{ text: string; type: 'info' | 'error' | 'success' } | null>(null);
  const [loading, setLoading] = useState(false);

  function notify(text: string, type: 'info' | 'error' | 'success' = 'info') {
    setMessage({ text, type });
    setTimeout(() => setMessage(null), 5000);
  }

  useEffect(() => {
    const savedToken = localStorage.getItem('umt_token');
    if (savedToken) {
      setToken(savedToken);
      loadUserData(savedToken);
    }
    loadCatalog();
  }, [selectedType]);

  async function loadUserData(authToken: string) {
    try {
      const [userData, libData, statsData, recsData] = await Promise.all([
        api.getMe(authToken),
        api.listLibrary(authToken, 'all', { mediaType: selectedType }),
        api.getStats(authToken).catch(() => null),
        api.getRecommendations(authToken, selectedType).catch(() => []),
      ]);
      setUser(userData);
      setLibrary(libData);
      setRecommendations(recsData);
      if (statsData) setStats(statsData);

      setSettingName(userData.display_name);
      setSettingAvatar(userData.avatar_url ?? '');
      setSettingNotify(userData.notify_new_releases ?? true);
    } catch {
      logout();
    }
  }

  async function loadCatalog() {
    try {
      const filterOpts: FilterOptions = {
        mediaType: selectedType,
        includeGenres,
        excludeGenres,
        mediaStatus: publicationStatus,
        yearFrom: yearFrom ? parseInt(yearFrom, 10) : undefined,
        yearTo: yearTo ? parseInt(yearTo, 10) : undefined,
        ageRating: ageRatingFilter,
      };
      const catalog = await api.listMedia('', filterOpts);
      setItems(catalog);
    } catch (e) {
      console.error(e);
    }
  }

  // Manejador del ciclo de género: Neutro -> Incluir (+) -> Excluir (-) -> Neutro
  function toggleGenreFilter(genre: string) {
    if (includeGenres.includes(genre)) {
      setIncludeGenres((prev) => prev.filter((g) => g !== genre));
      setExcludeGenres((prev) => [...prev, genre]);
    } else if (excludeGenres.includes(genre)) {
      setExcludeGenres((prev) => prev.filter((g) => g !== genre));
    } else {
      setIncludeGenres((prev) => [...prev, genre]);
    }
  }

  function clearAllFilters() {
    setIncludeGenres([]);
    setExcludeGenres([]);
    setYearFrom('');
    setYearTo('');
    setPublicationStatus('all');
    setAgeRatingFilter('all');
    setStatusFilter('all');
  }

  async function handleAuth(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setLoading(true);
    const form = new FormData(e.currentTarget);
    const email = String(form.get('email') ?? '').trim();
    const password = String(form.get('password') ?? '');
    const displayName = String(form.get('displayName') ?? '').trim();

    try {
      let res: { access_token: string };
      if (authMode === 'register') {
        res = await api.register(email, displayName, password);
        notify('¡Cuenta creada con éxito! Bienvenido a UMT.', 'success');
      } else {
        res = await api.login(email, password);
        notify('Sesión iniciada correctamente.', 'success');
      }
      localStorage.setItem('umt_token', res.access_token);
      setToken(res.access_token);
      await loadUserData(res.access_token);
    } catch (err) {
      notify(err instanceof Error ? err.message : 'Error de autenticación', 'error');
    } finally {
      setLoading(false);
    }
  }

  function logout() {
    localStorage.removeItem('umt_token');
    setToken(null);
    setUser(null);
    setLibrary([]);
    setStats(null);
    setRecommendations([]);
    notify('Has cerrado sesión.', 'info');
  }

  async function handleSearch(e?: FormEvent) {
    if (e) e.preventDefault();
    if (!searchQuery.trim()) {
      setSearchResults([]);
      return;
    }
    setSearching(true);
    try {
      const filterOpts: FilterOptions = {
        mediaType: selectedType,
        includeGenres,
        excludeGenres,
        mediaStatus: publicationStatus,
        yearFrom: yearFrom ? parseInt(yearFrom, 10) : undefined,
        yearTo: yearTo ? parseInt(yearTo, 10) : undefined,
        ageRating: ageRatingFilter,
      };
      const results = await api.search(searchQuery, filterOpts);
      setSearchResults(results);
      if (results.length === 0) {
        notify('No se encontraron resultados con los filtros seleccionados.', 'info');
      }
    } catch (err) {
      notify(err instanceof Error ? err.message : 'Error al buscar', 'error');
    } finally {
      setSearching(false);
    }
  }

  // REQUERIMIENTO 5: Validar que no se exceda el monto de capítulos/tomos de la DB
  async function incrementProgress(entry: LibraryEntry, delta: number) {
    if (!token) return;
    const target = entry.progress + delta;

    if (delta > 0 && entry.total && target > entry.total) {
      notify(
        `Has alcanzado el límite máximo disponible (${entry.total}) de episodios/capítulos según la base de datos.`,
        'error'
      );
      return;
    }

    const newProgress = Math.max(0, target);
    try {
      const res = await api.updateProgress(token, entry.media_id, newProgress);
      setLibrary((prev) =>
        prev.map((item) =>
          item.id === entry.id
            ? { ...item, progress: res.progress, status: res.status }
            : item
        )
      );
      refreshUserData();
    } catch (err) {
      notify(err instanceof Error ? err.message : 'No se pudo actualizar el progreso', 'error');
    }
  }

  // Edición manual de progreso y total
  async function saveManualProgress(entry: LibraryEntry) {
    if (!token) return;
    const parsedTotal = manualTotal.trim() ? parseFloat(manualTotal) : null;

    if (parsedTotal && manualProgress > parsedTotal) {
      notify(`El progreso no puede ser mayor al total (${parsedTotal}).`, 'error');
      return;
    }

    try {
      const updated = await api.upsertLibrary(token, entry.media_id, {
        status: entry.status,
        progress: manualProgress,
        total: parsedTotal,
        rating: entry.rating,
        notes: entry.notes,
      });
      setLibrary((prev) => prev.map((item) => (item.id === entry.id ? updated : item)));
      setEditingEntryId(null);
      notify('Progreso guardado correctamente.', 'success');
      refreshUserData();
    } catch (err) {
      notify(err instanceof Error ? err.message : 'Error al guardar progreso', 'error');
    }
  }

  // Marcar como terminado rápido
  async function markAsFinished(entry: LibraryEntry) {
    if (!token) return;
    const targetTotal = entry.total ?? entry.progress;
    try {
      const updated = await api.upsertLibrary(token, entry.media_id, {
        status: 'completed',
        progress: targetTotal,
        total: entry.total,
        rating: entry.rating,
        notes: entry.notes,
      });
      setLibrary((prev) => prev.map((item) => (item.id === entry.id ? updated : item)));
      notify(`"${entry.media.title}" marcado como terminado.`, 'success');
      refreshUserData();
    } catch (err) {
      notify(err instanceof Error ? err.message : 'Error al marcar terminado', 'error');
    }
  }

  async function updateStatus(entry: LibraryEntry, newStatus: string) {
    if (!token) return;
    try {
      const updated = await api.upsertLibrary(token, entry.media_id, {
        status: newStatus,
        progress: entry.progress,
        rating: entry.rating,
        notes: entry.notes,
        total: entry.total,
      });
      setLibrary((prev) => prev.map((item) => (item.id === entry.id ? updated : item)));
      refreshUserData();
      notify(`Estado actualizado a: ${STATUS_LABELS[newStatus]?.label ?? newStatus}`, 'success');
    } catch (err) {
      notify(err instanceof Error ? err.message : 'Error al cambiar estado', 'error');
    }
  }

  async function updateRating(entry: LibraryEntry, rating: number) {
    if (!token) return;
    try {
      const updated = await api.upsertLibrary(token, entry.media_id, {
        status: entry.status,
        progress: entry.progress,
        rating: rating === entry.rating ? null : rating,
        notes: entry.notes,
        total: entry.total,
      });
      setLibrary((prev) => prev.map((item) => (item.id === entry.id ? updated : item)));
      notify('Calificación guardada.', 'success');
    } catch (err) {
      notify(err instanceof Error ? err.message : 'Error al calificar', 'error');
    }
  }

  async function removeFromLibrary(entry: LibraryEntry) {
    if (!token) return;
    if (!confirm(`¿Quitar "${entry.media.title}" de tu biblioteca?`)) return;
    try {
      await api.removeFromLibrary(token, entry.media_id);
      setLibrary((prev) => prev.filter((x) => x.id !== entry.id));
      notify(`"${entry.media.title}" eliminado.`, 'info');
      refreshUserData();
    } catch (err) {
      notify(err instanceof Error ? err.message : 'Error al eliminar', 'error');
    }
  }

  async function addToLibrary(item: MediaItem) {
    if (!token) {
      notify('Inicia sesión para guardar contenido en tu lista.', 'error');
      return;
    }
    try {
      const entry = await api.track(token, item.id);
      setLibrary((prev) => [entry, ...prev.filter((x) => x.media_id !== item.id)]);
      notify(`"${item.title}" añadido a tu biblioteca.`, 'success');
      refreshUserData();
    } catch (err) {
      notify(err instanceof Error ? err.message : 'No se pudo guardar', 'error');
    }
  }

  async function importAndAdd(result: SearchResult) {
    if (!token) {
      notify('Inicia sesión para importar contenido.', 'error');
      return;
    }
    try {
      const media = await api.importResult(token, result);
      const entry = await api.track(token, media.id);
      setLibrary((prev) => [entry, ...prev.filter((x) => x.media_id !== media.id)]);
      notify(`"${media.title}" importado y guardado.`, 'success');
      refreshUserData();
    } catch (err) {
      notify(err instanceof Error ? err.message : 'Error al importar', 'error');
    }
  }

  // REQUERIMIENTO 3: Guardar perfil y settings
  async function handleSaveSettings(e: FormEvent) {
    e.preventDefault();
    if (!token) return;
    try {
      const updated = await api.updateProfile(token, {
        display_name: settingName,
        avatar_url: settingAvatar || undefined,
        notify_new_releases: settingNotify,
      });
      setUser(updated);
      notify('Perfil y configuración de notificaciones guardados.', 'success');
    } catch (err) {
      notify(err instanceof Error ? err.message : 'Error al guardar perfil', 'error');
    }
  }

  async function handleChangePassword(e: FormEvent) {
    e.preventDefault();
    if (!token) return;
    if (newPass.length < 8) {
      notify('La nueva contraseña debe tener al menos 8 caracteres.', 'error');
      return;
    }
    try {
      await api.changePassword(token, currPass, newPass);
      setCurrPass('');
      setNewPass('');
      notify('Contraseña cambiada exitosamente.', 'success');
    } catch (err) {
      notify(err instanceof Error ? err.message : 'Error al cambiar contraseña', 'error');
    }
  }

  async function refreshUserData() {
    if (token) {
      const [s, recs] = await Promise.all([
        api.getStats(token).catch(() => null),
        api.getRecommendations(token, selectedType).catch(() => []),
      ]);
      if (s) setStats(s);
      setRecommendations(recs);
    }
  }

  // Filtrado de la biblioteca
  const filteredLibrary = useMemo(() => {
    return library.filter((entry) => {
      const matchStatus = statusFilter === 'all' || entry.status === statusFilter;
      const matchType = selectedType === 'all' || entry.media.media_type === selectedType;

      const mediaGenres = entry.media.genres?.map((g) => g.toLowerCase()) ?? [];
      const hasIncludes =
        includeGenres.length === 0 ||
        includeGenres.every((ig) => mediaGenres.includes(ig.toLowerCase()));
      const hasExcludes =
        excludeGenres.length > 0 &&
        excludeGenres.some((eg) => mediaGenres.includes(eg.toLowerCase()));

      return matchStatus && matchType && hasIncludes && !hasExcludes;
    });
  }, [library, statusFilter, selectedType, includeGenres, excludeGenres]);

  const inLibraryMediaIds = useMemo(() => {
    return new Set(library.map((x) => x.media_id));
  }, [library]);

  return (
    <section className="dashboard shell" id="dashboard">
      {/* Barra de Usuario y Navegación Principal */}
      <div className="sectionTitle">
        <div style={{ display: 'flex', alignItems: 'center', gap: '16px' }}>
          {user?.avatar_url ? (
            <img src={user.avatar_url} alt="Avatar" className="userAvatar" />
          ) : (
            <div className="userAvatarPlaceholder">
              {user?.display_name?.charAt(0) ?? 'U'}
            </div>
          )}
          <div>
            <p className="eyebrow">Universal Media Tracker</p>
            <h2>{user ? user.display_name : 'Bienvenido a tu Tracker'}</h2>
          </div>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
          {token && (
            <>
              <button
                className={`navViewBtn ${mainView === 'library' ? 'activeNavView' : ''}`}
                onClick={() => setMainView('library')}
              >
                📚 Mi Biblioteca
              </button>
              <button
                className={`navViewBtn ${mainView === 'explore' ? 'activeNavView' : ''}`}
                onClick={() => setMainView('explore')}
              >
                🔍 Explorar
              </button>
              <button
                className={`navViewBtn ${mainView === 'settings' ? 'activeNavView' : ''}`}
                onClick={() => setMainView('settings')}
                title="Configuración de cuenta y notificaciones"
              >
                ⚙️ Ajustes
              </button>
            </>
          )}
        </div>
      </div>

      {/* Alerta de notificación */}
      {message && (
        <div className={`notice ${message.type === 'error' ? 'noticeError' : message.type === 'success' ? 'noticeSuccess' : ''}`}>
          {message.text}
        </div>
      )}

      {/* Pantalla de Inicio de Sesión / Registro si no está logueado */}
      {!token && (
        <div className="authCard">
          <div className="authTabs">
            <button
              type="button"
              className={authMode === 'login' ? 'activeTab' : ''}
              onClick={() => setAuthMode('login')}
            >
              Iniciar sesión
            </button>
            <button
              type="button"
              className={authMode === 'register' ? 'activeTab' : ''}
              onClick={() => setAuthMode('register')}
            >
              Crear cuenta nueva
            </button>
          </div>

          <form className="signup" onSubmit={handleAuth}>
            {authMode === 'register' && (
              <input name="displayName" placeholder="Tu nombre" required minLength={2} />
            )}
            <input name="email" type="email" placeholder="correo@ejemplo.com" required />
            <input
              name="password"
              type="password"
              placeholder="Contraseña (mínimo 8 caracteres)"
              minLength={8}
              required
            />
            <button disabled={loading}>
              {loading ? 'Procesando...' : authMode === 'login' ? 'Entrar' : 'Registrarme'}
            </button>
          </form>
        </div>
      )}

      {/* REQUERIMIENTO 4: Barra de Categorías / Tipos Separados */}
      <div className="categoryBar">
        {MEDIA_CATEGORIES.map((cat) => (
          <button
            key={cat.key}
            className={`categoryTab ${selectedType === cat.key ? 'activeCategory' : ''}`}
            onClick={() => setSelectedType(cat.key)}
          >
            <span>{cat.icon}</span> {cat.label}
          </button>
        ))}
      </div>

      {/* Panel de Estadísticas Rápidas */}
      {token && stats && (
        <div className="statsBar">
          <div className="statItem">
            <span className="statValue">{stats.total}</span>
            <span className="statLabel">Total</span>
          </div>
          <div className="statItem">
            <span className="statValue statActive">{stats.in_progress}</span>
            <span className="statLabel">En progreso</span>
          </div>
          <div className="statItem">
            <span className="statValue statDone">{stats.completed}</span>
            <span className="statLabel">Completados</span>
          </div>
          <div className="statItem">
            <span className="statValue statPlan">{stats.planned}</span>
            <span className="statLabel">Planificados</span>
          </div>
        </div>
      )}

      {/* REQUERIMIENTO 1 y 6: Filtros Avanzados (con inclusión y exclusión de géneros) */}
      <div className="filterToggleContainer">
        <button
          className="advancedFilterToggleBtn"
          onClick={() => setShowAdvancedFilters(!showAdvancedFilters)}
        >
          {showAdvancedFilters ? '▲ Ocultar Filtros Avanzados' : '▼ Mostrar Filtros Avanzados (Géneros +/-, Años, Estado...)'}
        </button>

        {(includeGenres.length > 0 || excludeGenres.length > 0 || yearFrom || yearTo || publicationStatus !== 'all' || ageRatingFilter !== 'all') && (
          <button className="clearFiltersBtn" onClick={clearAllFilters}>
            ✕ Limpiar todos los filtros
          </button>
        )}
      </div>

      {showAdvancedFilters && (
        <div className="advancedFilterPanel">
          <div className="filterSectionTitle">
            <strong>Filtro de Géneros:</strong> Haz clic para <span style={{ color: 'var(--cyan)' }}>Incluir (+)</span>, doble clic para <span style={{ color: 'var(--red)' }}>Excluir (-)</span>, o tercer clic para desactivar.
          </div>

          <div className="genreChipsGrid">
            {POPULAR_GENRES.map((g) => {
              const isInc = includeGenres.includes(g);
              const isExc = excludeGenres.includes(g);
              return (
                <button
                  key={g}
                  type="button"
                  className={`genreChip ${isInc ? 'genreInclude' : isExc ? 'genreExclude' : ''}`}
                  onClick={() => toggleGenreFilter(g)}
                >
                  {isInc ? '✓ ' : isExc ? '✕ ' : ''}
                  {g}
                </button>
              );
            })}
          </div>

          <div className="filtersRow">
            <div className="filterField">
              <label>Estado de Emisión:</label>
              <select
                value={publicationStatus}
                onChange={(e) => setPublicationStatus(e.target.value)}
              >
                <option value="all">Cualquier estado</option>
                <option value="finished">Finalizado</option>
                <option value="releasing">En emisión / En curso</option>
                <option value="on_hold">En pausa / Hiatus</option>
                <option value="upcoming">Próximamente</option>
              </select>
            </div>

            <div className="filterField">
              <label>Rango de Años:</label>
              <div style={{ display: 'flex', gap: '6px' }}>
                <input
                  type="number"
                  placeholder="Desde (ej. 2000)"
                  value={yearFrom}
                  onChange={(e) => setYearFrom(e.target.value)}
                  style={{ width: '120px' }}
                />
                <input
                  type="number"
                  placeholder="Hasta (ej. 2026)"
                  value={yearTo}
                  onChange={(e) => setYearTo(e.target.value)}
                  style={{ width: '120px' }}
                />
              </div>
            </div>

            <div className="filterField">
              <label>Clasificación:</label>
              <select
                value={ageRatingFilter}
                onChange={(e) => setAgeRatingFilter(e.target.value)}
              >
                <option value="all">Todas las edades</option>
                <option value="safe">Todo Público</option>
                <option value="adult">Adultos (18+)</option>
              </select>
            </div>
          </div>
        </div>
      )}

      {/* ========================================================= */}
      {/* VISTA 1: MI BIBLIOTECA                                    */}
      {/* ========================================================= */}
      {token && mainView === 'library' && (
        <div className="librarySection">
          <div className="subHeader">
            <h3>
              Mi Biblioteca · {MEDIA_CATEGORIES.find((c) => c.key === selectedType)?.label} ({filteredLibrary.length})
            </h3>

            <div className="filterGroup">
              <select
                className="filterSelect"
                value={statusFilter}
                onChange={(e) => setStatusFilter(e.target.value)}
              >
                {Object.entries(STATUS_LABELS).map(([k, v]) => (
                  <option key={k} value={k}>
                    {v.label}
                  </option>
                ))}
              </select>
            </div>
          </div>

          {filteredLibrary.length === 0 ? (
            <div className="emptyState">
              <span>📚</span>
              <h3>No tienes medios en esta lista</h3>
              <p>Cambia de categoría arriba o ve a <strong>"Explorar"</strong> para buscar y agregar contenido.</p>
            </div>
          ) : (
            <div className="mediaGrid">
              {filteredLibrary.map((entry) => {
                const statusMeta = STATUS_LABELS[entry.status] ?? {
                  label: entry.status,
                  color: '#a782ff',
                };
                const isEditing = editingEntryId === entry.id;

                return (
                  <article className="mediaCard libraryCard" key={entry.id}>
                    {entry.media.cover_url ? (
                      <div className="coverContainer">
                        <img src={entry.media.cover_url} alt={entry.media.title} className="coverImg" />
                      </div>
                    ) : (
                      <div className="coverPlaceholder">
                        <span>{entry.media.title.charAt(0)}</span>
                      </div>
                    )}

                    <div className="cardHeader">
                      <span className="mediaType">{entry.media.media_type}</span>
                      <span
                        className="statusBadge"
                        style={{ borderColor: statusMeta.color, color: statusMeta.color }}
                      >
                        {statusMeta.label}
                      </span>
                    </div>

                    <h3 title={entry.media.title}>{entry.media.title}</h3>

                    {/* REQUERIMIENTO 5: Barra de Progreso con Control de Límite y Edición Manual */}
                    <div className="progressControl">
                      <span className="progressLabel">
                        Progreso: <strong>{entry.progress}</strong>
                        {entry.total ? ` / ${entry.total}` : ' / ?'}
                      </span>
                      <div className="stepperButtons">
                        <button
                          type="button"
                          className="stepBtn"
                          onClick={() => incrementProgress(entry, -1)}
                          disabled={entry.progress <= 0}
                          title="Restar 1"
                        >
                          -
                        </button>
                        <button
                          type="button"
                          className="stepBtn stepBtnAdd"
                          onClick={() => incrementProgress(entry, 1)}
                          title="Añadir +1 (Episodio / Capítulo / Tomo / Página)"
                        >
                          +1
                        </button>
                      </div>
                    </div>

                    {/* Botón y panel de edición manual de progreso */}
                    {!isEditing ? (
                      <div style={{ display: 'flex', gap: '8px', marginBottom: '8px' }}>
                        <button
                          type="button"
                          className="smallActionBtn"
                          onClick={() => {
                            setEditingEntryId(entry.id);
                            setManualProgress(entry.progress);
                            setManualTotal(entry.total ? String(entry.total) : '');
                          }}
                        >
                          ✏️ Editar número
                        </button>
                        <button
                          type="button"
                          className="smallActionBtn"
                          onClick={() => markAsFinished(entry)}
                        >
                          ✓ Marcar terminado
                        </button>
                      </div>
                    ) : (
                      <div className="manualEditBox">
                        <div style={{ display: 'flex', gap: '6px', alignItems: 'center' }}>
                          <input
                            type="number"
                            min="0"
                            value={manualProgress}
                            onChange={(e) => setManualProgress(parseFloat(e.target.value) || 0)}
                            style={{ width: '70px', padding: '4px 6px' }}
                            placeholder="Prog."
                          />
                          <span>/</span>
                          <input
                            type="number"
                            min="0"
                            value={manualTotal}
                            onChange={(e) => setManualTotal(e.target.value)}
                            style={{ width: '70px', padding: '4px 6px' }}
                            placeholder="Total"
                          />
                          <button
                            type="button"
                            className="saveBtn"
                            onClick={() => saveManualProgress(entry)}
                          >
                            Guardar
                          </button>
                          <button
                            type="button"
                            className="cancelBtn"
                            onClick={() => setEditingEntryId(null)}
                          >
                            ✕
                          </button>
                        </div>
                      </div>
                    )}

                    {/* Acciones inferiores: Calificación y Estado */}
                    <div className="cardActions">
                      <select
                        className="statusSelect"
                        value={entry.status}
                        onChange={(e) => updateStatus(entry, e.target.value)}
                      >
                        <option value="planned">Planificado</option>
                        <option value="in_progress">En progreso</option>
                        <option value="completed">Completado</option>
                        <option value="on_hold">En pausa</option>
                        <option value="dropped">Abandonado</option>
                      </select>

                      <div className="ratingGroup">
                        {[2, 4, 6, 8, 10].map((starVal) => (
                          <button
                            key={starVal}
                            type="button"
                            className={`starBtn ${(entry.rating ?? 0) >= starVal ? 'starFilled' : ''}`}
                            onClick={() => updateRating(entry, starVal)}
                            title={`Calificar ${starVal}/10`}
                          >
                            ★
                          </button>
                        ))}
                      </div>

                      <button
                        type="button"
                        className="deleteBtn"
                        onClick={() => removeFromLibrary(entry)}
                        title="Eliminar de mi lista"
                      >
                        🗑️ Quitar
                      </button>
                    </div>
                  </article>
                );
              })}
            </div>
          )}
        </div>
      )}

      {/* ========================================================= */}
      {/* VISTA 2: EXPLORAR Y BUSCAR + RECOMENDACIONES               */}
      {/* ========================================================= */}
      {token && mainView === 'explore' && (
        <div className="searchSection" style={{ borderTop: 'none', paddingTop: 0 }}>
          {/* REQUERIMIENTO 2: Sección de Recomendaciones Personalizadas */}
          {recommendations.length > 0 && (
            <div className="recommendationsContainer">
              <div className="subHeader" style={{ marginBottom: '14px' }}>
                <div>
                  <span className="eyebrow" style={{ color: 'var(--violet)' }}>Inteligencia UMT</span>
                  <h3 style={{ margin: '4px 0' }}>✨ Recomendaciones Personalizadas para ti</h3>
                </div>
                <button
                  className="smallActionBtn"
                  onClick={() => refreshUserData()}
                >
                  🔄 Actualizar
                </button>
              </div>

              <div className="mediaGrid" style={{ marginTop: '10px', marginBottom: '40px' }}>
                {recommendations.slice(0, 4).map((rec) => (
                  <article className="mediaCard recommendationCard" key={rec.media.id}>
                    {rec.media.cover_url ? (
                      <div className="coverContainer">
                        <img src={rec.media.cover_url} alt={rec.media.title} className="coverImg" />
                      </div>
                    ) : (
                      <div className="coverPlaceholder">
                        <span>{rec.media.title.charAt(0)}</span>
                      </div>
                    )}
                    <div className="cardHeader">
                      <span className="mediaType">{rec.media.media_type}</span>
                      <span className="scorePill">Afición: {rec.score}</span>
                    </div>
                    <h3>{rec.media.title}</h3>
                    <p className="recReason">💡 {rec.reason}</p>
                    <button
                      className="addBtn"
                      type="button"
                      onClick={() => addToLibrary(rec.media)}
                      disabled={inLibraryMediaIds.has(rec.media.id)}
                    >
                      {inLibraryMediaIds.has(rec.media.id) ? '✓ En biblioteca' : '＋ Añadir'}
                    </button>
                  </article>
                ))}
              </div>
            </div>
          )}

          {/* Formulario de búsqueda en vivo */}
          <h3>Explorador Global ({MEDIA_CATEGORIES.find((c) => c.key === selectedType)?.label})</h3>
          <form className="searchBar" onSubmit={handleSearch}>
            <input
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder="Busca por título (ej: Jujutsu Kaisen, Interstellar, Cien Años de Soledad)..."
            />
            <button type="submit" disabled={searching}>
              {searching ? 'Buscando...' : 'Buscar'}
            </button>
          </form>

          {/* Resultados de búsqueda */}
          <div className="mediaGrid">
            {searchResults.length > 0 ? (
              searchResults.map((r) => (
                <article className="mediaCard" key={`${r.source}-${r.external_id}`}>
                  {r.cover_url ? (
                    <div className="coverContainer">
                      <img src={r.cover_url} alt={r.title} className="coverImg" />
                    </div>
                  ) : (
                    <div className="coverPlaceholder">
                      <span>{r.title.charAt(0)}</span>
                    </div>
                  )}
                  <div className="cardHeader">
                    <span className="mediaType">{r.media_type}</span>
                    <span className="sourceTag">{r.source}</span>
                  </div>
                  <h3>{r.title}</h3>
                  <p>
                    {r.release_year ?? 'Sin fecha'}
                    {r.total_units ? ` · ${r.total_units} caps/págs` : ''}
                    {r.status ? ` · ${r.status}` : ''}
                  </p>
                  {r.description && <p className="synopsis">{r.description}</p>}
                  <button
                    className="addBtn"
                    type="button"
                    onClick={() => importAndAdd(r)}
                  >
                    ＋ Añadir a mi lista
                  </button>
                </article>
              ))
            ) : (
              items.map((m) => (
                <article className="mediaCard" key={m.id}>
                  {m.cover_url ? (
                    <div className="coverContainer">
                      <img src={m.cover_url} alt={m.title} className="coverImg" />
                    </div>
                  ) : (
                    <div className="coverPlaceholder">
                      <span>{m.title.charAt(0)}</span>
                    </div>
                  )}
                  <div className="cardHeader">
                    <span className="mediaType">{m.media_type}</span>
                    {m.status && <span className="sourceTag">{m.status}</span>}
                  </div>
                  <h3>{m.title}</h3>
                  <p>{m.release_year ?? 'Sin año'}</p>
                  <button
                    className="addBtn"
                    type="button"
                    onClick={() => addToLibrary(m)}
                    disabled={inLibraryMediaIds.has(m.id)}
                  >
                    {inLibraryMediaIds.has(m.id) ? '✓ En biblioteca' : '＋ Añadir a mi lista'}
                  </button>
                </article>
              ))
            )}
          </div>
        </div>
      )}

      {/* ========================================================= */}
      {/* VISTA 3: AJUSTES / SETTINGS                                */}
      {/* ========================================================= */}
      {token && mainView === 'settings' && (
        <div className="settingsSection">
          <h3>⚙️ Configuración de Cuenta y Notificaciones</h3>
          <p className="lead" style={{ fontSize: '0.95rem' }}>
            Personaliza tu perfil, avatar y notificaciones para estar al día con tus series y libros favoritos.
          </p>

          <div className="settingsGrid">
            {/* Formulario de perfil */}
            <form className="settingsCard" onSubmit={handleSaveSettings}>
              <h4>Perfil de Usuario</h4>

              <div className="avatarPicker">
                <div style={{ textAlign: 'center' }}>
                  {settingAvatar ? (
                    <img src={settingAvatar} alt="Avatar" className="largeAvatar" />
                  ) : (
                    <div className="largeAvatarPlaceholder">
                      {settingName.charAt(0) || 'U'}
                    </div>
                  )}
                </div>

                <div style={{ flex: 1 }}>
                  <label className="fieldLabel">Avatares Rápidos:</label>
                  <div className="presetAvatars">
                    {PRESET_AVATARS.map((avUrl, i) => (
                      <img
                        key={i}
                        src={avUrl}
                        alt="Avatar preset"
                        className={`presetAvatarItem ${settingAvatar === avUrl ? 'activeAvatarPreset' : ''}`}
                        onClick={() => setSettingAvatar(avUrl)}
                      />
                    ))}
                  </div>

                  <label className="fieldLabel" style={{ marginTop: '10px' }}>
                    O ingresa URL de tu foto de perfil:
                  </label>
                  <input
                    className="settingsInput"
                    placeholder="https://ejemplo.com/mifoto.jpg"
                    value={settingAvatar}
                    onChange={(e) => setSettingAvatar(e.target.value)}
                  />
                </div>
              </div>

              <label className="fieldLabel">Nombre para mostrar:</label>
              <input
                className="settingsInput"
                value={settingName}
                onChange={(e) => setSettingName(e.target.value)}
                required
              />

              <label className="fieldLabel">Correo Electrónico:</label>
              <input
                className="settingsInput"
                value={user?.email ?? ''}
                disabled
                style={{ opacity: 0.6 }}
              />

              {/* REQUERIMIENTO 3: Switch de notificaciones */}
              <div className="notificationToggleBox">
                <div>
                  <strong>🔔 Notificaciones de Nuevos Capítulos</strong>
                  <p style={{ margin: '4px 0 0', color: 'var(--muted)', fontSize: '0.85rem' }}>
                    Avisarme automáticamente cuando suban nuevos episodios de mis series o nuevos tomos de libros/manga en seguimiento.
                  </p>
                </div>
                <input
                  type="checkbox"
                  checked={settingNotify}
                  onChange={(e) => setSettingNotify(e.target.checked)}
                  style={{ width: '22px', height: '22px', cursor: 'pointer' }}
                />
              </div>

              <button className="primaryButton" style={{ marginTop: '20px' }}>
                Guardar Cambios de Perfil
              </button>
            </form>

            {/* Formulario de Seguridad y Cierre */}
            <div className="settingsCard">
              <h4>Seguridad y Contraseña</h4>
              <form onSubmit={handleChangePassword}>
                <label className="fieldLabel">Contraseña Actual:</label>
                <input
                  type="password"
                  className="settingsInput"
                  value={currPass}
                  onChange={(e) => setCurrPass(e.target.value)}
                  required
                />

                <label className="fieldLabel">Nueva Contraseña (mín. 8 caracteres):</label>
                <input
                  type="password"
                  className="settingsInput"
                  value={newPass}
                  onChange={(e) => setNewPass(e.target.value)}
                  minLength={8}
                  required
                />

                <button className="primaryButton" style={{ marginTop: '16px' }}>
                  Actualizar Contraseña
                </button>
              </form>

              <hr style={{ borderColor: 'var(--line)', margin: '30px 0' }} />

              <h4>Cerrar Sesión</h4>
              <p style={{ color: 'var(--muted)', fontSize: '0.85rem' }}>
                Finaliza la sesión activa en este navegador. Tus datos seguirán guardados en la nube.
              </p>
              <button
                type="button"
                className="logoutButton"
                onClick={logout}
                style={{ marginTop: '10px', padding: '10px 20px', fontSize: '0.9rem' }}
              >
                Cerrar Sesión de UMT
              </button>
            </div>
          </div>
        </div>
      )}
    </section>
  );
}