import type {
  LibraryEntry,
  LibraryStats,
  MediaItem,
  NotificationItem,
  ProfileUpdate,
  RecommendationItem,
  SearchResult,
  UserProfile,
} from './types';

const API_URL = process.env.NEXT_PUBLIC_API_URL ?? 'http://localhost:8000/api/v1';

async function request<T>(path: string, token?: string, init?: RequestInit): Promise<T> {
  const h = new Headers(init?.headers);
  h.set('Accept', 'application/json');
  if (init?.body) h.set('Content-Type', 'application/json');
  if (token) h.set('Authorization', `Bearer ${token}`);

  const r = await fetch(`${API_URL}${path}`, { ...init, headers: h });
  if (!r.ok) {
    const b = (await r.json().catch(() => null)) as
      | { error?: { message?: string }; detail?: string }
      | null;
    throw new Error(b?.error?.message ?? b?.detail ?? `Error en la petición (${r.status})`);
  }
  return r.status === 204 ? (undefined as T) : ((await r.json()) as T);
}

export interface FilterOptions {
  mediaType?: string;
  mediaStatus?: string;
  includeGenres?: string[];
  excludeGenres?: string[];
  yearFrom?: number | string;
  yearTo?: number | string;
  years?: Array<number | string>;
  ageRating?: string;
  minUnits?: number | string;
  maxUnits?: number | string;
}

export interface CustomMediaInput {
  title: string;
  media_type: string;
  description?: string | null;
  release_year?: number | null;
  cover_url?: string | null;
  genres?: string[];
  total_units?: number | null;
  status?: string;
  age_rating?: string;
  initial_status?: string;
}

function applyFilters(params: URLSearchParams, filters: FilterOptions) {
  if (filters.mediaType && filters.mediaType !== 'all') params.set('media_type', filters.mediaType);
  if (filters.mediaStatus && filters.mediaStatus !== 'all') params.set('media_status', filters.mediaStatus);
  if (filters.yearFrom) params.set('year_from', String(filters.yearFrom));
  if (filters.yearTo) params.set('year_to', String(filters.yearTo));
  filters.years?.forEach((year) => params.append('years', String(year)));
  if (filters.ageRating && filters.ageRating !== 'all') params.set('age_rating', filters.ageRating);
  if (filters.minUnits !== undefined && filters.minUnits !== '') params.set('min_units', String(filters.minUnits));
  if (filters.maxUnits !== undefined && filters.maxUnits !== '') params.set('max_units', String(filters.maxUnits));
  filters.includeGenres?.forEach((g) => params.append('include_genres', g));
  filters.excludeGenres?.forEach((g) => params.append('exclude_genres', g));
}

export const api = {
  register: (email: string, displayName: string, password: string) =>
    request<{ access_token: string }>('/auth/register', undefined, {
      method: 'POST',
      body: JSON.stringify({ email, display_name: displayName, password }),
    }),

  login: (email: string, password: string) =>
    request<{ access_token: string }>('/auth/login', undefined, {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    }),

  getMe: (token: string) =>
    request<UserProfile>('/auth/me', token),

  updateProfile: (token: string, data: ProfileUpdate) =>
    request<UserProfile>('/auth/profile', token, {
      method: 'PUT',
      body: JSON.stringify(data),
    }),

  changePassword: (token: string, currentPassword: string, newPassword: string) =>
    request<{ status: string; message: string }>('/auth/change-password', token, {
      method: 'POST',
      body: JSON.stringify({ current_password: currentPassword, new_password: newPassword }),
    }),

  getRecommendations: (token: string, mediaType?: string, limit = 12, refresh?: string, excludeIds: string[] = []) => {
    const params = new URLSearchParams();
    if (mediaType && mediaType !== 'all') params.set('media_type', mediaType);
    params.set('limit', String(limit));
    if (refresh) params.set('refresh', refresh);
    if (excludeIds.length) params.set('exclude_ids', excludeIds.join(','));
    return request<RecommendationItem[]>(`/recommendations?${params.toString()}`, token);
  },

  listMedia: (query = '', filters: FilterOptions = {}) => {
    const params = new URLSearchParams();
    if (query) params.set('query', query);
    applyFilters(params, filters);

    const qs = params.toString();
    return request<MediaItem[]>(`/media${qs ? `?${qs}` : ''}`);
  },

  search: (query: string, filters: FilterOptions = {}, token?: string) => {
    const params = new URLSearchParams({ query });
    applyFilters(params, filters);

    return request<SearchResult[]>(`/search?${params.toString()}`, token);
  },

  checkExists: (title: string, mediaType?: string) => {
    const params = new URLSearchParams({ title });
    if (mediaType && mediaType !== 'all') params.set('media_type', mediaType);
    return request<{ exists: boolean; match: MediaItem | null; similarity_message: string | null }>(
      `/media/check-exists?${params.toString()}`
    );
  },

  createCustomMedia: (token: string, data: CustomMediaInput) =>
    request<LibraryEntry>('/media/custom', token, {
      method: 'POST',
      body: JSON.stringify(data),
    }),

  listLibrary: (token: string, status?: string, filters: FilterOptions = {}) => {
    const params = new URLSearchParams();
    if (status && status !== 'all') params.set('status', status);
    applyFilters(params, filters);

    const qs = params.toString();
    return request<LibraryEntry[]>(`/library${qs ? `?${qs}` : ''}`, token);
  },

  upsertLibrary: (
    token: string,
    mediaId: string,
    data: {
      status?: string;
      progress?: number;
      total?: number | null;
      rating?: number | null;
      notes?: string | null;
    }
  ) =>
    request<LibraryEntry>(`/library/${mediaId}`, token, {
      method: 'PUT',
      body: JSON.stringify({
        status: data.status ?? 'planned',
        progress: data.progress ?? 0,
        total: data.total ?? null,
        rating: data.rating ?? null,
        notes: data.notes ?? null,
      }),
    }),

  track: (token: string, id: string) =>
    request<LibraryEntry>(`/library/${id}`, token, {
      method: 'PUT',
      body: JSON.stringify({ status: 'planned', progress: 0 }),
    }),

  updateProgress: (token: string, mediaId: string, value: number, unit = 'episode') =>
    request<{ progress: number; total: number | null; status: string; unit: string; source: string }>(
      `/library/${mediaId}/progress`,
      token,
      {
        method: 'POST',
        body: JSON.stringify({ value, unit, source: 'manual' }),
      }
    ),

  removeFromLibrary: (token: string, mediaId: string) =>
    request<void>(`/library/${mediaId}`, token, {
      method: 'DELETE',
    }),

  importResult: (token: string, item: SearchResult) =>
    request<MediaItem>('/media/import', token, {
      method: 'POST',
      body: JSON.stringify(item),
    }),

  getStats: (token: string) =>
    request<LibraryStats>('/stats', token),

  // Notificaciones
  listNotifications: (token: string) =>
    request<NotificationItem[]>('/notifications', token),

  markNotificationRead: (token: string, id: string) =>
    request<{ status: string }>(`/notifications/${id}/read`, token, { method: 'POST' }),

  markAllNotificationsRead: (token: string) =>
    request<{ status: string }>('/notifications/read-all', token, { method: 'POST' }),

  testNotification: (token: string) =>
    request<NotificationItem>('/notifications/test', token, { method: 'POST' }),

  checkUpdates: (token: string) =>
    request<{ status: string; new_notifications: number }>('/notifications/check-updates', token, {
      method: 'POST',
    }),
};
