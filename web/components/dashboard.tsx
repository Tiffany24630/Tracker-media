'use client';

import { ChangeEvent, FormEvent, useEffect, useMemo, useRef, useState } from 'react';
import { api, CustomMediaInput, FilterOptions } from '@/lib/api';
import type {
  LibraryEntry,
  LibraryStats,
  MediaItem,
  NotificationItem,
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
  { key: 'music', label: 'Música', icon: '🎵' },
  { key: 'album', label: 'Álbumes', icon: '💿' },
  { key: 'comic', label: 'Cómics', icon: '◆' },
  { key: 'game', label: 'Videojuegos', icon: '🎮' },
];

// Tipos que NO son películas: para ellos se ofrece el filtro de cantidad de episodios/capítulos
const NON_MOVIE_TYPES = new Set(['all', 'anime', 'manga', 'series', 'book', 'music', 'album', 'comic', 'game', 'webtoon', 'novel']);

// Géneros más específicos (agrupados por familia para facilitar la búsqueda)
const GENRE_GROUPS: Array<{ group: string; genres: string[] }> = [
  {
    group: 'Acción y Aventura',
    genres: [
      'Acción', 'Aventura', 'Artes Marciales', 'Superhéroes', 'Espionaje', 'Militar',
      'Wuxia', 'Isekai', 'Mecha', 'Supervivencia', 'Carreras', 'Samurái',
    ],
  },
  {
    group: 'Fantasía y Ciencia Ficción',
    genres: [
      'Fantasía', 'Alta Fantasía', 'Fantasía Oscura', 'Fantasía Urbana', 'Ciencia Ficción',
      'Cyberpunk', 'Distopía', 'Viajes en el Tiempo', 'Espacio', 'Realidad Virtual', 'Steampunk',
    ],
  },
  {
    group: 'Drama y Emoción',
    genres: [
      'Drama', 'Drama Romántico', 'Tragedia', 'Melodrama', 'Recuentos de la vida',
      'Coming of Age', 'Slice of Life', 'Familiar', 'Musical',
    ],
  },
  {
    group: 'Misterio y Suspenso',
    genres: [
      'Misterio', 'Suspense', 'Thriller Psicológico', 'Policial', 'Detectivesco', 'Crimen',
      'Noir', 'Terror', 'Terror Psicológico', 'Gore', 'Sobrenatural', 'Vampiros', 'Zombis',
    ],
  },
  {
    group: 'Romance',
    genres: [
      'Romance', 'Comedia Romántica', 'Romance Escolar', 'Harem', 'Reverse Harem',
      'Yaoi / BL', 'Yuri / GL', 'Triángulo Amoroso',
    ],
  },
  {
    group: 'Comedia y Estilo de Vida',
    genres: [
      'Comedia', 'Comedia Negra', 'Parodia', 'Sátira', 'Gag Humor', 'Gastronomía',
      'Deportes', 'Escolar', 'Idols', 'Ecchi',
    ],
  },
  {
    group: 'Histórico y Cultural',
    genres: [
      'Histórico', 'Época', 'Biográfico', 'Documental', 'Western', 'Guerra', 'Político',
      'Mitología', 'Folclore',
    ],
  },
  {
    group: 'Música (específicos)',
    genres: [
      'Pop', 'Rock', 'Rock Alternativo', 'Indie', 'Metal', 'Punk', 'Hip-Hop / Rap', 'Trap',
      'R&B / Soul', 'Funk', 'Jazz', 'Blues', 'Electrónica', 'EDM', 'House', 'Techno',
      'Reggaetón', 'Latina', 'Salsa', 'Bachata', 'Cumbia', 'K-Pop', 'J-Pop', 'Música Clásica',
      'Banda Sonora', 'Lo-Fi', 'Ambient', 'Country', 'Folk', 'Reggae', 'Gospel',
    ],
  },
];

const ALL_GENRES = GENRE_GROUPS.flatMap((g) => g.genres);

// Avatares rápidos: animales + colores
const ANIMAL_AVATARS = [
  'https://cdn.jsdelivr.net/gh/twitter/twemoji@14.0.2/assets/72x72/1f98a.png',
  'https://cdn.jsdelivr.net/gh/twitter/twemoji@14.0.2/assets/72x72/1f431.png',
  'https://cdn.jsdelivr.net/gh/twitter/twemoji@14.0.2/assets/72x72/1f436.png',
  'https://cdn.jsdelivr.net/gh/twitter/twemoji@14.0.2/assets/72x72/1f43c.png',
  'https://cdn.jsdelivr.net/gh/twitter/twemoji@14.0.2/assets/72x72/1f981.png',
  'https://cdn.jsdelivr.net/gh/twitter/twemoji@14.0.2/assets/72x72/1f42f.png',
  'https://cdn.jsdelivr.net/gh/twitter/twemoji@14.0.2/assets/72x72/1f989.png',
  'https://cdn.jsdelivr.net/gh/twitter/twemoji@14.0.2/assets/72x72/1f42c.png',
  'https://cdn.jsdelivr.net/gh/twitter/twemoji@14.0.2/assets/72x72/1f428.png',
  'https://cdn.jsdelivr.net/gh/twitter/twemoji@14.0.2/assets/72x72/1f43a.png',
  'https://cdn.jsdelivr.net/gh/twitter/twemoji@14.0.2/assets/72x72/1f430.png',
  'https://cdn.jsdelivr.net/gh/twitter/twemoji@14.0.2/assets/72x72/1f99d.png',
];

type CardMedia = MediaItem | SearchResult;

function MediaDetails({
  media,
  userRating,
  notes,
}: {
  media: CardMedia;
  userRating?: number | null;
  notes?: string | null;
}) {
  const facts = [
    media.release_year ? `Año: ${media.release_year}` : 'Año no disponible',
    media.status ? `Estado: ${media.status}` : null,
    media.total_units ? `Unidades: ${media.total_units}` : null,
    media.age_rating ? `Clasificación: ${media.age_rating}` : null,
    media.rating_avg != null ? `Valoración: ${media.rating_avg.toFixed(1)}/10` : null,
    userRating != null ? `Tu nota: ${userRating}/10` : null,
  ].filter(Boolean);

  return (
    <div className="cardDetails">
      {media.creator && <p className="recReason">Autoría / estudio: {media.creator}</p>}
      <p style={{ fontSize: '0.78rem', color: '#a8a5b2' }}>{facts.join(' · ')}</p>
      <p style={{ fontSize: '0.75rem', color: '#bcaadb' }}>
        {media.genres?.length ? media.genres.join(' · ') : 'Género no especificado'}
      </p>
      <p className="synopsis">{media.description?.trim() || 'Sin sinopsis disponible.'}</p>
      {notes?.trim() && <p className="recReason">Notas: {notes}</p>}
    </div>
  );
}

export function Dashboard({ onSessionChange }: { onSessionChange?: (hasSession: boolean) => void } = {}) {
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
  const [addedSearchKeys, setAddedSearchKeys] = useState<Set<string>>(new Set());
  const importFileRef = useRef<HTMLInputElement | null>(null);
  const [importing, setImporting] = useState(false);

  // Estado del backend
  const [backendOffline, setBackendOffline] = useState(false);

  // Filtros Avanzados
  const [showAdvancedFilters, setShowAdvancedFilters] = useState(false);
  const [statusFilter, setStatusFilter] = useState('all');
  const [includeGenres, setIncludeGenres] = useState<string[]>([]);
  const [excludeGenres, setExcludeGenres] = useState<string[]>([]);
  const [selectedYears, setSelectedYears] = useState<number[]>([]);
  const [publicationStatus, setPublicationStatus] = useState<string>('all');
  const [ageRatingFilter, setAgeRatingFilter] = useState<string>('all');
  // Filtro de cantidad de episodios/capítulos (no aplica a películas)
  const [minUnits, setMinUnits] = useState<string>('');
  const [maxUnits, setMaxUnits] = useState<string>('');

  // Buscador
  const [searchQuery, setSearchQuery] = useState('');
  const [searching, setSearching] = useState(false);

  // Estados de edición manual de progreso
  const [editingEntryId, setEditingEntryId] = useState<string | null>(null);
  const [manualProgress, setManualProgress] = useState<number>(0);
  const [manualTotal, setManualTotal] = useState<string>('');

  // Alta manual de títulos
  const [showManualAdd, setShowManualAdd] = useState(false);
  const [manualTitle, setManualTitle] = useState('');
  const [manualType, setManualType] = useState('anime');
  const [manualYear, setManualYear] = useState('');
  const [manualDesc, setManualDesc] = useState('');
  const [manualCover, setManualCover] = useState('');
  const [manualTotalUnits, setManualTotalUnits] = useState('');
  const [manualGenres, setManualGenres] = useState<string[]>([]);
  const [manualAgeRating, setManualAgeRating] = useState('safe');
  const [checkingManual, setCheckingManual] = useState(false);
  // Resultado de la verificación de existencia
  const [existsPrompt, setExistsPrompt] = useState<{ match: MediaItem; message: string } | null>(null);

  // Notificaciones
  const [notifications, setNotifications] = useState<NotificationItem[]>([]);
  const [showNotifications, setShowNotifications] = useState(false);

  // Settings form states
  const [settingName, setSettingName] = useState('');
  const [settingAvatar, setSettingAvatar] = useState('');
  const [settingNotify, setSettingNotify] = useState(true);
  const [currPass, setCurrPass] = useState('');
  const [newPass, setNewPass] = useState('');

  // Mensajería y carga
  const [message, setMessage] = useState<{ text: string; type: 'info' | 'error' | 'success' } | null>(null);
  const [loading, setLoading] = useState(false);

  const messageTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  function notify(text: string, type: 'info' | 'error' | 'success' = 'info') {
    setMessage({ text, type });
    if (messageTimer.current) clearTimeout(messageTimer.current);
    messageTimer.current = setTimeout(() => setMessage(null), 5000);
  }

  useEffect(() => {
    // Este código solo corre en el cliente, nunca en SSR
    if (typeof window === 'undefined') return;
    const savedToken = localStorage.getItem('umt_token');
    if (savedToken) {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setToken(savedToken);
      void loadUserData(savedToken);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Auto-aplicar filtros (con debounce) para mostrar coincidencias en vivo
  useEffect(() => {
    const t = setTimeout(() => {
      void loadCatalog();
      if (searchQuery.trim()) void handleSearch();
      if (token) {
        void api.listLibrary(token, statusFilter, buildFilterOptions()).then(setLibrary).catch(() => undefined);
      }
    }, 350);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedType, statusFilter, includeGenres, excludeGenres, selectedYears, publicationStatus, ageRatingFilter, minUnits, maxUnits]);

  // Mantener "Para ti" sincronizado con la categoría activa.
  useEffect(() => {
    if (!token) return;
    void api.getRecommendations(token, selectedType)
      .then(setRecommendations)
      .catch(() => undefined);
  }, [selectedType, token]);

  async function loadUserData(authToken: string) {
    try {
      const [userData, libData, recsData] = await Promise.all([
        api.getMe(authToken),
        api.listLibrary(authToken, 'all', { mediaType: selectedType }),
        api.getRecommendations(authToken, selectedType).catch(() => []),
      ]);
      setUser(userData);
      setLibrary(libData);
      setRecommendations(recsData);

      setSettingName(userData.display_name);
      setSettingAvatar(userData.avatar_url ?? '');
      setSettingNotify(userData.notify_new_releases ?? true);

      loadNotifications(authToken);
    } catch {
      logout();
    }
  }

  async function loadNotifications(authToken: string) {
    try {
      const notes = await api.listNotifications(authToken);
      setNotifications(notes);
    } catch {
      // silencioso
    }
  }

  function buildFilterOptions(): FilterOptions {
    return {
      mediaType: selectedType,
      includeGenres,
      excludeGenres,
      mediaStatus: publicationStatus,
      years: selectedYears,
      ageRating: ageRatingFilter,
      minUnits: minUnits !== '' ? parseInt(minUnits, 10) : undefined,
      maxUnits: maxUnits !== '' ? parseInt(maxUnits, 10) : undefined,
    };
  }

  async function loadCatalog() {
    try {
      const catalog = await api.listMedia('', buildFilterOptions());
      setItems(catalog);
    } catch (e: unknown) {
      const isNetworkError =
        e instanceof TypeError && (e.message.includes('fetch') || e.message.includes('network'));
      if (isNetworkError) {
        setBackendOffline(true);
      } else {
        setBackendOffline(false);
        console.warn('loadCatalog error:', e);
      }
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
    setSelectedYears([]);
    setPublicationStatus('all');
    setAgeRatingFilter('all');
    setStatusFilter('all');
    setMinUnits('');
    setMaxUnits('');
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
      onSessionChange?.(true);
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
    setRecommendations([]);
    setNotifications([]);
    setAddedSearchKeys(new Set());
    onSessionChange?.(false);
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
      const results = await api.search(searchQuery, buildFilterOptions(), token ?? undefined);
      setSearchResults(results);
      if (results.length === 0) {
        notify('No se encontraron resultados. Puedes agregarlo manualmente con "Agregar título manual".', 'info');
      }
    } catch (err) {
      notify(err instanceof Error ? err.message : 'Error al buscar', 'error');
    } finally {
      setSearching(false);
    }
  }

  // Validar que no se exceda el monto de capítulos/tomos de la DB
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

  // Marcar como terminado rápido (Requerimiento 2: completa todos los episodios/capítulos)
  async function markAsFinished(entry: LibraryEntry) {
    if (!token) return;
    const total = entry.total ?? entry.media.total_units ?? entry.progress;
    try {
      const updated = await api.upsertLibrary(token, entry.media_id, {
        status: 'completed',
        progress: total,
        total,
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
    const total = entry.total ?? entry.media.total_units ?? null;
    // Al marcar como completado, se registran todos los episodios/capítulos disponibles
    const progress = newStatus === 'completed' ? (total ?? entry.progress) : entry.progress;
    try {
      const updated = await api.upsertLibrary(token, entry.media_id, {
        status: newStatus,
        progress,
        rating: entry.rating,
        notes: entry.notes,
        total,
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
      // Marcar el resultado como añadido para evitar duplicados (Requerimiento 1)
      setAddedSearchKeys((prev) => new Set(prev).add(`${result.source}-${result.external_id}`));
      notify(`"${media.title}" importado y guardado.`, 'success');
      refreshUserData();
    } catch (err) {
      notify(err instanceof Error ? err.message : 'Error al importar', 'error');
    }
  }

  // ---------------------------------------------------------------
  // Alta manual de títulos con validación de existencia
  // ---------------------------------------------------------------
  function resetManualForm() {
    setManualTitle('');
    setManualYear('');
    setManualDesc('');
    setManualCover('');
    setManualTotalUnits('');
    setManualGenres([]);
    setManualAgeRating('safe');
    setExistsPrompt(null);
  }

  async function createCustomFromForm() {
    if (!token) {
      notify('Inicia sesión para agregar títulos.', 'error');
      return;
    }
    const payload: CustomMediaInput = {
      title: manualTitle.trim(),
      media_type: manualType,
      description: manualDesc.trim() || null,
      release_year: manualYear ? parseInt(manualYear, 10) : null,
      cover_url: manualCover.trim() || null,
      genres: manualGenres,
      total_units: manualTotalUnits ? parseInt(manualTotalUnits, 10) : null,
      status: 'finished',
      age_rating: manualAgeRating,
      initial_status: 'planned',
    };
    try {
      const entry = await api.createCustomMedia(token, payload);
      setLibrary((prev) => [entry, ...prev.filter((x) => x.media_id !== entry.media_id)]);
      notify(`"${entry.media.title}" agregado manualmente a tu biblioteca.`, 'success');
      resetManualForm();
      setShowManualAdd(false);
      refreshUserData();
    } catch (err) {
      notify(err instanceof Error ? err.message : 'Error al agregar título', 'error');
    }
  }

  async function handleManualSubmit(e: FormEvent) {
    e.preventDefault();
    if (!manualTitle.trim()) {
      notify('Escribe un título para continuar.', 'error');
      return;
    }
    setCheckingManual(true);
    try {
      const res = await api.checkExists(manualTitle.trim(), manualType);
      if (res.exists && res.match) {
        // Existe: preguntar al usuario si es el mismo título
        setExistsPrompt({
          match: res.match,
          message: res.similarity_message ?? 'Se encontró un título similar en la base de datos.',
        });
      } else {
        // No existe: crear directamente con la info del usuario
        await createCustomFromForm();
      }
    } catch (err) {
      notify(err instanceof Error ? err.message : 'Error al verificar el título', 'error');
    } finally {
      setCheckingManual(false);
    }
  }

  // El usuario confirma que el título encontrado SÍ es el que buscaba -> usar el de la DB
  async function confirmUseExisting() {
    if (!existsPrompt) return;
    const match = existsPrompt.match;
    setExistsPrompt(null);
    await addToLibrary(match);
    resetManualForm();
    setShowManualAdd(false);
  }

  // El usuario indica que NO es el mismo -> crear con toda la info ingresada
  async function confirmCreateCustom() {
    setExistsPrompt(null);
    await createCustomFromForm();
  }

  // ---------------------------------------------------------------
  // Notificaciones
  // ---------------------------------------------------------------
  async function openNotifications() {
    if (!token) return;
    setShowNotifications((v) => !v);
    if (!showNotifications) {
      await loadNotifications(token);
    }
  }

  async function markNotificationRead(id: string) {
    if (!token) return;
    try {
      await api.markNotificationRead(token, id);
      setNotifications((prev) => prev.map((n) => (n.id === id ? { ...n, is_read: true } : n)));
    } catch (err) {
      notify(err instanceof Error ? err.message : 'Error al marcar notificación', 'error');
    }
  }

  async function markAllRead() {
    if (!token) return;
    try {
      await api.markAllNotificationsRead(token);
      setNotifications((prev) => prev.map((n) => ({ ...n, is_read: true })));
      notify('Todas las notificaciones marcadas como leídas.', 'success');
    } catch (err) {
      notify(err instanceof Error ? err.message : 'Error al marcar notificaciones', 'error');
    }
  }

  async function sendTestNotification() {
    if (!token) return;
    try {
      const n = await api.testNotification(token);
      setNotifications((prev) => [n, ...prev]);
      notify('Notificación de prueba creada.', 'success');
    } catch (err) {
      notify(err instanceof Error ? err.message : 'Error al crear notificación de prueba', 'error');
    }
  }

  async function checkForUpdates() {
    if (!token) return;
    try {
      const res = await api.checkUpdates(token);
      await loadNotifications(token);
      notify(
        res.new_notifications > 0
          ? `Se encontraron ${res.new_notifications} novedades.`
          : 'No hay novedades por ahora.',
        'info'
      );
    } catch (err) {
      notify(err instanceof Error ? err.message : 'Error al comprobar novedades', 'error');
    }
  }

  // Guardar perfil y settings
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
    if (!token) return;
    try {
      let recs = await api.getRecommendations(
        token,
        selectedType,
        12,
        `${Date.now()}`,
        recommendations.map((item) => item.media.id),
      );
      // Si no hay suficientes sustitutos, conservar una sección útil en vez de vaciarla.
      if (recs.length === 0) {
        recs = await api.getRecommendations(token, selectedType, 12, `${Date.now()}-fallback`);
      }
      setRecommendations(recs);
    } catch (err) {
      notify(err instanceof Error ? err.message : 'No se pudieron actualizar las recomendaciones', 'error');
    }
  }

  // ---------------------------------------------------------------
  // Exportar / Importar biblioteca (CSV / Excel) - Requerimiento 6
  // ---------------------------------------------------------------
  const EXPORT_COLUMNS = [
    'media_id', 'title', 'media_type', 'status', 'progress', 'total',
    'rating', 'notes', 'genres', 'release_year', 'description', 'cover_url',
  ] as const;

  function csvEscape(value: unknown): string {
    const s = value === null || value === undefined ? '' : String(value);
    if (/[",\n;]/.test(s)) return `"${s.replace(/"/g, '""')}"`;
    return s;
  }

  function buildExportRows(): Record<string, unknown>[] {
    return library.map((entry) => ({
      media_id: entry.media_id,
      title: entry.media.title,
      media_type: entry.media.media_type,
      status: entry.status,
      progress: entry.progress,
      total: entry.total ?? entry.media.total_units ?? '',
      rating: entry.rating ?? '',
      notes: entry.notes ?? '',
      genres: (entry.media.genres ?? []).join(' | '),
      release_year: entry.media.release_year ?? '',
      description: entry.media.description ?? '',
      cover_url: entry.media.cover_url ?? '',
    }));
  }

  function downloadBlob(content: string, filename: string, mime: string) {
    const blob = new Blob(['\ufeff' + content], { type: mime });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  }

  function exportLibrary(format: 'csv' | 'excel') {
    const rows = buildExportRows();
    if (rows.length === 0) {
      notify('Tu biblioteca está vacía; no hay nada que exportar.', 'info');
      return;
    }
    const header = EXPORT_COLUMNS.join(',');
    const body = rows.map((r) => EXPORT_COLUMNS.map((c) => csvEscape(r[c])).join(',')).join('\n');
    const stamp = new Date().toISOString().slice(0, 10);
    if (format === 'csv') {
      downloadBlob(`${header}\n${body}`, `biblioteca_umt_${stamp}.csv`, 'text/csv;charset=utf-8');
    } else {
      // Excel: tabla HTML con extensión .xls (Excel/Sheets la abren nativamente)
      const th = EXPORT_COLUMNS.map((c) => `<th>${c}</th>`).join('');
      const trs = rows
        .map(
          (r) =>
            `<tr>${EXPORT_COLUMNS.map((c) => `<td>${String(r[c] ?? '').replace(/</g, '<')}</td>`).join('')}</tr>`
        )
        .join('');
      const table = `<html><head><meta charset="utf-8" /></head><body><table border="1"><thead><tr>${th}</tr></thead><tbody>${trs}</tbody></table></body></html>`;
      downloadBlob(table, `biblioteca_umt_${stamp}.xls`, 'application/vnd.ms-excel');
    }
    notify(`Biblioteca exportada (${rows.length} elementos).`, 'success');
  }

  // Detecta automáticamente el delimitador (tabulador, punto y coma o coma)
  function detectDelimiter(sample: string): string {
    const candidates = ['\t', ';', ','];
    let best = ',';
    let bestCount = -1;
    for (const d of candidates) {
      const count = sample.split(d).length - 1;
      if (count > bestCount) {
        bestCount = count;
        best = d;
      }
    }
    return best;
  }

  function parseDelimited(text: string): string[][] {
    const clean = text.replace(/^\ufeff/, '').replace(/\r\n/g, '\n').replace(/\r/g, '\n');
    const firstLine = clean.split('\n')[0] ?? '';
    const delimiter = detectDelimiter(firstLine);
    const rows: string[][] = [];
    let row: string[] = [];
    let field = '';
    let inQuotes = false;
    for (let i = 0; i < clean.length; i++) {
      const ch = clean[i];
      if (inQuotes) {
        if (ch === '"') {
          if (clean[i + 1] === '"') { field += '"'; i++; }
          else inQuotes = false;
        } else field += ch;
      } else if (ch === '"') {
        inQuotes = true;
      } else if (ch === delimiter) {
        row.push(field); field = '';
      } else if (ch === '\n') {
        row.push(field); field = '';
        rows.push(row); row = [];
      } else {
        field += ch;
      }
    }
    if (field !== '' || row.length > 0) { row.push(field); rows.push(row); }
    return rows.filter((r) => r.some((c) => c.trim() !== ''));
  }

  async function handleImportFile(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (!file || !token) return;
    setImporting(true);
    try {
      const text = await file.text();
      const rows = parseDelimited(text);
      if (rows.length < 2) {
        notify('El archivo está vacío o no tiene datos reconocibles.', 'error');
        return;
      }
      const header = rows[0].map((h) => h.trim().toLowerCase());
      const findCol = (names: string[]) => header.findIndex((h) => names.some((n) => h === n || h.includes(n)));
      const titleCol = findCol(['title', 'título', 'titulo']);
      if (titleCol < 0) {
        notify('No se detectó una columna de título (cabecera "title" o "título").', 'error');
        return;
      }
      const iType = findCol(['media_type', 'type', 'tipo']);
      const iStatus = findCol(['status', 'estado']);
      const iProgress = findCol(['progress', 'progreso']);
      const iTotal = findCol(['total']);
      const iRating = findCol(['rating', 'calificación', 'calificacion']);
      const iNotes = findCol(['notes', 'notas']);
      const iGenres = findCol(['genres', 'géneros', 'generos']);
      const iYear = findCol(['release_year', 'year', 'año', 'anio']);
      const iMediaId = findCol(['media_id']);

      let created = 0;
      let updated = 0;
      for (let r = 1; r < rows.length; r++) {
        const cells = rows[r];
        const title = (cells[titleCol] ?? '').trim();
        if (!title) continue;
        const type = iType >= 0 ? (cells[iType] ?? '').trim() : '';
        const status = iStatus >= 0 && cells[iStatus]?.trim() ? cells[iStatus].trim() : 'planned';
        const progress = iProgress >= 0 ? parseFloat(cells[iProgress] ?? '') || 0 : 0;
        const total = iTotal >= 0 && cells[iTotal]?.trim() ? parseFloat(cells[iTotal]) : null;
        const rating = iRating >= 0 && cells[iRating]?.trim() ? parseFloat(cells[iRating]) : null;
        const notes = iNotes >= 0 ? (cells[iNotes] ?? '').trim() || null : null;
        const year = iYear >= 0 && cells[iYear]?.trim() ? parseInt(cells[iYear], 10) : null;
        const genres =
          iGenres >= 0 && cells[iGenres]?.trim()
            ? cells[iGenres].split('|').map((g) => g.trim()).filter(Boolean)
            : [];
        const mediaId = iMediaId >= 0 ? (cells[iMediaId] ?? '').trim() : '';

        let targetId = mediaId;
        if (!targetId) {
          const res = await api.checkExists(title, type && type !== 'all' ? type : undefined);
          if (res.exists && res.match) {
            targetId = res.match.id;
          } else {
            const entry = await api.createCustomMedia(token, {
              title,
              media_type: type || 'other',
              genres,
              release_year: year,
              total_units: total ?? null,
              initial_status: status,
            });
            await api.upsertLibrary(token, entry.media_id, { status, progress, total, rating, notes });
            created++;
            continue;
          }
        }
        await api.upsertLibrary(token, targetId, { status, progress, total, rating, notes });
        updated++;
      }

      notify(`Importación completada: ${created} creados, ${updated} actualizados.`, 'success');
      const lib = await api.listLibrary(token, 'all', { mediaType: selectedType });
      setLibrary(lib);
    } catch (err) {
      notify(err instanceof Error ? err.message : 'Error al importar el archivo', 'error');
    } finally {
      setImporting(false);
    }
  }

  // Filtrado de la biblioteca (aplica TODOS los filtros activos en vivo)
  const filteredLibrary = useMemo(() => {
    const uMin = minUnits !== '' ? parseInt(minUnits, 10) : null;
    const uMax = maxUnits !== '' ? parseInt(maxUnits, 10) : null;

    return library.filter((entry) => {
      const matchStatus = statusFilter === 'all' || entry.status === statusFilter;
      const matchType = selectedType === 'all' || entry.media.media_type === selectedType;

      const mediaGenres = entry.media.genres?.map((g) => g.toLowerCase()) ?? [];
      const hasIncludes =
        includeGenres.length === 0 ||
        includeGenres.some((ig) => mediaGenres.includes(ig.toLowerCase()));
      const hasExcludes =
        excludeGenres.length > 0 &&
        excludeGenres.some((eg) => mediaGenres.includes(eg.toLowerCase()));

      const year = entry.media.release_year ?? null;
      const matchYear = selectedYears.length === 0 || (year !== null && selectedYears.includes(year));

      const matchPubStatus =
        publicationStatus === 'all' || (entry.media.status ?? '') === publicationStatus;

      const matchAge =
        ageRatingFilter === 'all' || (entry.media.age_rating ?? 'safe') === ageRatingFilter;

      const units = entry.total ?? entry.media.total_units ?? null;
      const matchMinUnits = uMin === null || (units !== null && units >= uMin);
      const matchMaxUnits = uMax === null || (units !== null && units <= uMax);

      return (
        matchStatus &&
        matchType &&
        hasIncludes &&
        !hasExcludes &&
        matchYear &&
        matchPubStatus &&
        matchAge &&
        matchMinUnits &&
        matchMaxUnits
      );
    });
  }, [
    library,
    statusFilter,
    selectedType,
    includeGenres,
    excludeGenres,
    selectedYears,
    publicationStatus,
    ageRatingFilter,
    minUnits,
    maxUnits,
  ]);

  const inLibraryMediaIds = useMemo(() => {
    return new Set(library.map((x) => x.media_id));
  }, [library]);

  const inLibraryExternalKeys = useMemo(() => {
    return new Set(library.flatMap((entry) =>
      (entry.media.external_ids ?? []).map((external) => `${external.provider}-${external.external_id}`)
    ));
  }, [library]);

  function notificationDate(value?: string | null): string {
    if (!value) return '';
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? '' : new Intl.DateTimeFormat('es', {
      dateStyle: 'medium',
      timeStyle: 'short',
    }).format(date);
  }

  const unreadCount = useMemo(() => notifications.filter((n) => !n.is_read).length, [notifications]);

  // Estadísticas calculadas según la sección/tipo seleccionado (Requerimiento 5)
  const typeStats: LibraryStats = useMemo(() => {
    const scoped =
      selectedType === 'all'
        ? library
        : library.filter((e) => e.media.media_type === selectedType);
    const byStatus: Record<string, number> = {};
    for (const e of scoped) byStatus[e.status] = (byStatus[e.status] ?? 0) + 1;
    return {
      total: scoped.length,
      by_status: byStatus,
      completed: byStatus['completed'] ?? 0,
      in_progress: byStatus['in_progress'] ?? 0,
      planned: byStatus['planned'] ?? 0,
      on_hold: byStatus['on_hold'] ?? 0,
      dropped: byStatus['dropped'] ?? 0,
    };
  }, [library, selectedType]);

  const showUnitsFilter = NON_MOVIE_TYPES.has(selectedType) && selectedType !== 'movie';

  return (
    <section className="dashboard shell" id="dashboard">
      {/* Banner de Backend Offline */}
      {backendOffline && (
        <div className="offlineBanner">
          <span>⚠️ El servidor backend no está respondiendo o se está iniciando...</span>
          <button onClick={loadCatalog} className="retryBtn">⟳ Reintentar</button>
        </div>
      )}
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
              <button
                className={`navViewBtn ${showNotifications ? 'activeNavView' : ''}`}
                onClick={openNotifications}
                title="Notificaciones"
              >
                🔔 {unreadCount > 0 ? `(${unreadCount})` : ''}
              </button>
            </>
          )}
        </div>
      </div>

      {/* Panel de Notificaciones */}
      {token && showNotifications && (
        <div className="notificationsPanel">
          <div className="notifHeader">
            <strong>🔔 Notificaciones</strong>
            <div style={{ display: 'flex', gap: '8px' }}>
              <button className="smallActionBtn" onClick={checkForUpdates}>🔄 Comprobar novedades</button>
              <button className="smallActionBtn" onClick={sendTestNotification}>＋ Prueba</button>
              <button className="smallActionBtn" onClick={markAllRead}>✓ Marcar todas</button>
            </div>
          </div>
          {notifications.length === 0 ? (
            <p style={{ color: 'var(--muted)', fontSize: '0.9rem' }}>No tienes notificaciones.</p>
          ) : (
            <ul className="notifList">
              {notifications.map((n) => (
                <li key={n.id} className={`notifItem ${n.is_read ? 'notifRead' : ''}`}>
                  <div>
                    <strong>{n.title}</strong>
                    <p>{n.message}</p>
                    {n.media_title && <span className="notifMedia">🎬 {n.media_title}</span>}
                    {n.created_at && <span className="notifMedia">Actualizado: {notificationDate(n.created_at)}</span>}
                  </div>
                  {!n.is_read && (
                    <button className="smallActionBtn" onClick={() => markNotificationRead(n.id)}>
                      Marcar leída
                    </button>
                  )}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}

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

      {/* Barra de Categorías / Tipos Separados */}
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

      {/* Panel de Estadísticas Rápidas (calculadas por sección, Requerimiento 5) */}
      {token && (
        <div className="statsBar">
          <div className="statItem">
            <span className="statValue">{typeStats.total}</span>
            <span className="statLabel">Total · {MEDIA_CATEGORIES.find((c) => c.key === selectedType)?.label}</span>
          </div>
          <div className="statItem">
            <span className="statValue statActive">{typeStats.in_progress}</span>
            <span className="statLabel">En progreso</span>
          </div>
          <div className="statItem">
            <span className="statValue statDone">{typeStats.completed}</span>
            <span className="statLabel">Completados</span>
          </div>
          <div className="statItem">
            <span className="statValue statPlan">{typeStats.planned}</span>
            <span className="statLabel">Planificados</span>
          </div>
        </div>
      )}

      {/* Filtros Avanzados (con inclusión y exclusión de géneros) */}
      <div className="filterToggleContainer">
        <button
          className="advancedFilterToggleBtn"
          onClick={() => setShowAdvancedFilters(!showAdvancedFilters)}
        >
          {showAdvancedFilters ? '▲ Ocultar Filtros Avanzados' : '▼ Mostrar Filtros Avanzados (Géneros +/-, Años, Estado...)'}
        </button>

        {(includeGenres.length > 0 || excludeGenres.length > 0 || selectedYears.length > 0 || publicationStatus !== 'all' || ageRatingFilter !== 'all' || minUnits || maxUnits) && (
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

          {GENRE_GROUPS.map((group) => (
            <div key={group.group} className="genreGroup">
              <span className="genreGroupTitle">{group.group}</span>
              <div className="genreChipsGrid">
                {group.genres.map((g) => {
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
            </div>
          ))}

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
              <label>Años de lanzamiento (selección múltiple):</label>
              <select
                multiple
                size={6}
                value={selectedYears.map(String)}
                onChange={(event) => setSelectedYears(
                  Array.from(event.currentTarget.selectedOptions, (option) => Number(option.value))
                )}
                aria-label="Años de lanzamiento"
              >
                {Array.from({ length: new Date().getFullYear() - 1899 }, (_, index) => new Date().getFullYear() - index).map((year) => (
                  <option key={year} value={year}>{year}</option>
                ))}
              </select>
              <small>Usa Ctrl/Cmd para escoger varios años.</small>
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

            {/* Filtro de cantidad de episodios/capítulos (no aplica a películas) */}
            {showUnitsFilter && (
              <div className="filterField">
                <label>Cantidad de Episodios / Capítulos:</label>
                <div style={{ display: 'flex', gap: '6px' }}>
                  <input
                    type="number"
                    min="0"
                    placeholder="Mínimo"
                    value={minUnits}
                    onChange={(e) => setMinUnits(e.target.value)}
                    style={{ width: '110px' }}
                  />
                  <input
                    type="number"
                    min="0"
                    placeholder="Máximo"
                    value={maxUnits}
                    onChange={(e) => setMaxUnits(e.target.value)}
                    style={{ width: '110px' }}
                  />
                </div>
              </div>
            )}
          </div>
          <button
            type="button"
            className="primaryButton"
            onClick={() => {
              void loadCatalog();
              if (searchQuery.trim()) void handleSearch();
              setShowAdvancedFilters(false);
            }}
          >
            Aplicar filtros
          </button>
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

            <div className="filterGroup" style={{ display: 'flex', gap: '8px', flexWrap: 'wrap', alignItems: 'center' }}>
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
              <button type="button" className="smallActionBtn" onClick={() => exportLibrary('csv')}>
                ⬇️ Exportar CSV
              </button>
              <button type="button" className="smallActionBtn" onClick={() => exportLibrary('excel')}>
                ⬇️ Exportar Excel
              </button>
              <button
                type="button"
                className="smallActionBtn"
                onClick={() => importFileRef.current?.click()}
                disabled={importing}
              >
                {importing ? '⏳ Importando...' : '️ Importar CSV/Excel'}
              </button>
              <input
                ref={importFileRef}
                type="file"
                accept=".csv,.txt,.xls,.xlsx,text/csv"
                style={{ display: 'none' }}
                onChange={handleImportFile}
              />
            </div>
          </div>

          {filteredLibrary.length === 0 ? (
            <div className="emptyState">
              <span>📚</span>
              <h3>No tienes medios en esta lista</h3>
              <p>Cambia de categoría arriba o ve a la sección <strong>Explorar</strong> para buscar y agregar contenido.</p>
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
                    <MediaDetails media={entry.media} userRating={entry.rating} notes={entry.notes} />

                    {/* Barra de Progreso con Control de Límite y Edición Manual */}
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
          {/* Sección de Recomendaciones Personalizadas */}
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
                {recommendations.map((rec) => (
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
                    <MediaDetails media={rec.media} />
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
          <div className="subHeader">
            <h3>Explorador Global ({MEDIA_CATEGORIES.find((c) => c.key === selectedType)?.label})</h3>
            <button
              className="advancedFilterToggleBtn"
              onClick={() => setShowManualAdd((v) => !v)}
            >
              {showManualAdd ? '▲ Cerrar alta manual' : '＋ Agregar título manual'}
            </button>
          </div>

          {/* Alta manual de títulos con validación */}
          {showManualAdd && (
            <div className="manualAddPanel">
              <h4>➕ Agregar un título que no encontraste</h4>
              <p className="lead" style={{ fontSize: '0.85rem', marginTop: 0 }}>
                Verificaremos si ya existe en la base de datos. Si existe, te preguntaremos si es el mismo título.
              </p>
              <form onSubmit={handleManualSubmit} className="manualAddForm">
                <div className="manualAddRow">
                  <div className="filterField" style={{ flex: 2 }}>
                    <label>Título *</label>
                    <input
                      value={manualTitle}
                      onChange={(e) => setManualTitle(e.target.value)}
                      placeholder="Ej: Mi obra favorita"
                      required
                    />
                  </div>
                  <div className="filterField">
                    <label>Tipo *</label>
                    <select value={manualType} onChange={(e) => setManualType(e.target.value)}>
                      <option value="anime">Anime</option>
                      <option value="manga">Manga / Manhwa</option>
                      <option value="movie">Película</option>
                      <option value="series">Serie</option>
                      <option value="book">Libro</option>
                      <option value="music">Música</option>
                      <option value="album">Álbum</option>
                      <option value="other">Otro</option>
                    </select>
                  </div>
                  <div className="filterField">
                    <label>Año</label>
                    <input
                      type="number"
                      value={manualYear}
                      onChange={(e) => setManualYear(e.target.value)}
                      placeholder="2024"
                    />
                  </div>
                </div>

                <div className="manualAddRow">
                  <div className="filterField" style={{ flex: 2 }}>
                    <label>Descripción</label>
                    <input
                      value={manualDesc}
                      onChange={(e) => setManualDesc(e.target.value)}
                      placeholder="Sinopsis breve (opcional)"
                    />
                  </div>
                  <div className="filterField" style={{ flex: 2 }}>
                    <label>URL de portada</label>
                    <input
                      value={manualCover}
                      onChange={(e) => setManualCover(e.target.value)}
                      placeholder="https://..."
                    />
                  </div>
                </div>

                <div className="manualAddRow">
                  <div className="filterField">
                    <label>Total de episodios/capítulos</label>
                    <input
                      type="number"
                      min="0"
                      value={manualTotalUnits}
                      onChange={(e) => setManualTotalUnits(e.target.value)}
                      placeholder="Ej: 24"
                    />
                  </div>
                  <div className="filterField">
                    <label>Clasificación</label>
                    <select value={manualAgeRating} onChange={(e) => setManualAgeRating(e.target.value)}>
                      <option value="safe">Todo Público</option>
                      <option value="adult">Adultos (18+)</option>
                    </select>
                  </div>
                </div>

                <div className="filterField">
                  <label>Géneros (haz clic para seleccionar)</label>
                  <div className="genreChipsGrid" style={{ maxHeight: '160px', overflowY: 'auto' }}>
                    {ALL_GENRES.map((g) => {
                      const sel = manualGenres.includes(g);
                      return (
                        <button
                          key={g}
                          type="button"
                          className={`genreChip ${sel ? 'genreInclude' : ''}`}
                          onClick={() =>
                            setManualGenres((prev) =>
                              prev.includes(g) ? prev.filter((x) => x !== g) : [...prev, g]
                            )
                          }
                        >
                          {sel ? '✓ ' : ''}
                          {g}
                        </button>
                      );
                    })}
                  </div>
                </div>

                <button className="primaryButton" disabled={checkingManual} style={{ marginTop: '14px' }}>
                  {checkingManual ? 'Verificando...' : 'Verificar y agregar'}
                </button>
              </form>
            </div>
          )}

          {/* Diálogo de confirmación de existencia */}
          {existsPrompt && (
            <div className="existsDialog">
              <h4>⚠️ Posible título existente</h4>
              <p>{existsPrompt.message}</p>
              <div className="existsMatch">
                {existsPrompt.match.cover_url && (
                  <img src={existsPrompt.match.cover_url} alt={existsPrompt.match.title} />
                )}
                <div>
                  <strong>{existsPrompt.match.title}</strong>
                  <p>
                    {existsPrompt.match.media_type} · {existsPrompt.match.release_year ?? 'Año desc.'}
                  </p>
                  {existsPrompt.match.genres && existsPrompt.match.genres.length > 0 && (
                    <p style={{ fontSize: '0.8rem' }}>{existsPrompt.match.genres.join(', ')}</p>
                  )}
                </div>
              </div>
              <p className="existsQuestion">
                ¿El título que intentas agregar es este mismo? Si dices <strong>Sí</strong>, se usará el de la base de datos.
                Si dices <strong>No</strong>, se creará con toda la información que ingresaste.
              </p>
              <div style={{ display: 'flex', gap: '10px' }}>
                <button className="primaryButton" onClick={confirmUseExisting}>Sí, usar el de la base de datos</button>
                <button className="cancelBtn" onClick={confirmCreateCustom}>No, crear con mis datos</button>
              </div>
            </div>
          )}

          <form className="searchBar" onSubmit={handleSearch}>
            <input
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder="Busca por título (ej: Jujutsu Kaisen, Interstellar, Bad Bunny)..."
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
                  <MediaDetails media={r} />
                  <button
                    className="addBtn"
                    type="button"
                    onClick={() => importAndAdd(r)}
                    disabled={r.in_library || inLibraryExternalKeys.has(`${r.source}-${r.external_id}`) || addedSearchKeys.has(`${r.source}-${r.external_id}`)}
                  >
                    {r.in_library || inLibraryExternalKeys.has(`${r.source}-${r.external_id}`) || addedSearchKeys.has(`${r.source}-${r.external_id}`)
                      ? '✓ En biblioteca'
                      : '＋ Añadir a mi lista'}
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
                  <MediaDetails media={m} />
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
                  <label className="fieldLabel">Avatares de Animales:</label>
                  <div className="presetAvatars">
                    {ANIMAL_AVATARS.map((avUrl, i) => (
                      <img
                        key={`animal-${i}`}
                        src={avUrl}
                        alt="Avatar animal"
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

              {/* Switch de notificaciones */}
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

            <div className="settingsCard">
              <h4>Seguridad y Contraseña</h4>
              <form onSubmit={handleChangePassword}>
                <label className="fieldLabel">Contraseña Actual:</label>
                <input type="password" className="settingsInput" value={currPass} onChange={(e) => setCurrPass(e.target.value)} required />
                <label className="fieldLabel">Nueva Contraseña (mín. 8 caracteres):</label>
                <input type="password" className="settingsInput" value={newPass} onChange={(e) => setNewPass(e.target.value)} minLength={8} required />
                <button className="primaryButton" style={{ marginTop: '16px' }}>Actualizar Contraseña</button>
              </form>

              <hr style={{ borderColor: 'var(--line)', margin: '30px 0' }} />

              <h4>Cerrar Sesión</h4>
              <button type="button" className="logoutButton" onClick={logout} style={{ padding: '10px 20px', fontSize: '0.9rem' }}>Cerrar Sesión de UMT</button>
            </div>
          </div>
        </div>
      )}
    </section>
  );
}
