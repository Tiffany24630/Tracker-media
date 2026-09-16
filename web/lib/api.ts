import type {
  LibraryEntry,
  LibraryStats,
  MediaItem,
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
    const b = (await r.json().catch(() => null)) as any;
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
  ageRating?: string;
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

  getRecommendations: (token: string, mediaType?: string, limit = 12) => {
    const params = new URLSearchParams();
    if (mediaType && mediaType !== 'all') params.set('media_type', mediaType);
    params.set('limit', String(limit));
    return request<RecommendationItem[]>(`/recommendations?${params.toString()}`, token);
  },

  listMedia: (query = '', filters: FilterOptions = {}) => {
    const params = new URLSearchParams();
    if (query) params.set('query', query);
    if (filters.mediaType && filters.mediaType !== 'all') params.set('media_type', filters.mediaType);
    if (filters.mediaStatus && filters.mediaStatus !== 'all') params.set('media_status', filters.mediaStatus);
    if (filters.yearFrom) params.set('year_from', String(filters.yearFrom));
    if (filters.yearTo) params.set('year_to', String(filters.yearTo));
    if (filters.ageRating && filters.ageRating !== 'all') params.set('age_rating', filters.ageRating);
    filters.includeGenres?.forEach((g) => params.append('include_genres', g));
    filters.excludeGenres?.forEach((g) => params.append('exclude_genres', g));

    const qs = params.toString();
    return request<MediaItem[]>(`/media${qs ? `?${qs}` : ''}`);
  },

  search: (query: string, filters: FilterOptions = {}) => {
    const params = new URLSearchParams({ query });
    if (filters.mediaType && filters.mediaType !== 'all') params.set('media_type', filters.mediaType);
    if (filters.mediaStatus && filters.mediaStatus !== 'all') params.set('media_status', filters.mediaStatus);
    if (filters.yearFrom) params.set('year_from', String(filters.yearFrom));
    if (filters.yearTo) params.set('year_to', String(filters.yearTo));
    if (filters.ageRating && filters.ageRating !== 'all') params.set('age_rating', filters.ageRating);
    filters.includeGenres?.forEach((g) => params.append('include_genres', g));
    filters.excludeGenres?.forEach((g) => params.append('exclude_genres', g));

    return request<SearchResult[]>(`/search?${params.toString()}`);
  },

  listLibrary: (token: string, status?: string, filters: FilterOptions = {}) => {
    const params = new URLSearchParams();
    if (status && status !== 'all') params.set('status', status);
    if (filters.mediaType && filters.mediaType !== 'all') params.set('media_type', filters.mediaType);
    filters.includeGenres?.forEach((g) => params.append('include_genres', g));
    filters.excludeGenres?.forEach((g) => params.append('exclude_genres', g));

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
};
