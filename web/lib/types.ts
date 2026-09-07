export type MediaType =
  | "movie"
  | "series"
  | "anime"
  | "manga"
  | "webtoon"
  | "book"
  | "novel"
  | "music"
  | "other";

export type TrackingStatus =
  | "planned"
  | "in_progress"
  | "completed"
  | "on_hold"
  | "dropped";

export interface MediaItem {
  id: string;
  media_type: MediaType;
  title: string;
  original_title: string | null;
  description: string | null;
  release_year: number | null;
  cover_url: string | null;
  provider: string | null;
  provider_id: string | null;
  metadata: Record<string, unknown>;
  created_at: string;
  updated_at: string;
}

export interface LibraryEntry {
  id: string;
  media_id: string;
  status: TrackingStatus;
  progress: number;
  rating: number | null;
  notes: string | null;
  media: MediaItem;
  created_at: string;
  updated_at: string;
}
