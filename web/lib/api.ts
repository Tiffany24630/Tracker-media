import type { LibraryEntry, MediaItem } from "./types";

const API_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8000/api/v1";

async function request<T>(path: string, token?: string, init?: RequestInit): Promise<T> {
  const headers = new Headers(init?.headers);
  headers.set("Accept", "application/json");
  if (init?.body) headers.set("Content-Type", "application/json");
  if (token) headers.set("Authorization", `Bearer ${token}`);
  const response = await fetch(`${API_URL}${path}`, { ...init, headers });
  if (!response.ok) {
    const body = (await response.json().catch(() => null)) as { detail?: string } | null;
    throw new Error(body?.detail ?? `Request failed (${response.status})`);
  }
  return response.status === 204 ? (undefined as T) : ((await response.json()) as T);
}

export const api = {
  register: (email: string, displayName: string, password: string) =>
    request<{ access_token: string }>("/auth/register", undefined, {
      method: "POST",
      body: JSON.stringify({ email, display_name: displayName, password }),
    }),
  listMedia: (query = "") =>
    request<MediaItem[]>(`/media?query=${encodeURIComponent(query)}`),
  listLibrary: (token: string) => request<LibraryEntry[]>("/library", token),
  track: (token: string, mediaId: string) =>
    request<LibraryEntry>(`/library/${mediaId}`, token, {
      method: "PUT",
      body: JSON.stringify({ status: "planned", progress: 0 }),
    }),
};
