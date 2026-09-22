export type MediaType =
  | 'movie'
  | 'series'
  | 'anime'
  | 'manga'
  | 'webtoon'
  | 'book'
  | 'novel'
  | 'music'
  | 'album'
  | 'other';

export type TrackingStatus =
  | 'planned'
  | 'in_progress'
  | 'completed'
  | 'on_hold'
  | 'dropped';

export interface MediaItem {
  id: string;
  media_type: MediaType | string;
  title: string;
  original_title?: string | null;
  description: string | null;
  release_year: number | null;
  status?: string;
  original_language?: string | null;
  cover_url: string | null;
  genres?: string[];
  external_ids?: Array<{ provider: string; external_id: string; url?: string | null }>;
  metadata?: Record<string, unknown>;
  total_units?: number | null;
  age_rating?: string | null;
  creator?: string | null;
}

export interface LibraryEntry {
  id: string;
  media_id: string;
  status: TrackingStatus | string;
  progress: number;
  total: number | null;
  rating: number | null;
  notes: string | null;
  source?: string;
  media: MediaItem;
  created_at?: string;
  updated_at?: string;
}

export interface SearchResult {
  source: string;
  external_id: string;
  media_type: string;
  title: string;
  description: string | null;
  release_year: number | null;
  cover_url: string | null;
  status?: string | null;
  genres?: string[];
  age_rating?: string | null;
  total_units?: number | null;
  creator?: string | null;
  in_library?: boolean;
}

export interface UserProfile {
  id: string;
  email: string;
  display_name: string;
  avatar_url?: string | null;
  notify_new_releases: boolean;
  notification_settings: Record<string, unknown>;
}

export interface ProfileUpdate {
  display_name?: string;
  avatar_url?: string;
  notify_new_releases?: boolean;
  notification_settings?: Record<string, unknown>;
}

export interface RecommendationItem {
  media: MediaItem;
  score: number;
  reason: string;
  matching_genres: string[];
}

export interface LibraryStats {
  total: number;
  by_status: Record<string, number>;
  completed: number;
  in_progress: number;
  planned: number;
  on_hold: number;
  dropped: number;
}

export interface NotificationItem {
  id: string;
  title: string;
  message: string;
  media_id?: string | null;
  media_title?: string | null;
  is_read: boolean;
  created_at?: string | null;
}
