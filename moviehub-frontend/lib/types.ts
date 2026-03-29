export interface MovieResponse {
  tmdbId: number;
  movieTitle: string;
  movieOverview: string;
  releaseDate: string;
  posterPath: string | null;
  backdropPath: string | null;
  rating: number;
  runtime: number | null;
  popularity: number;
  genres: string[];
}

export interface ReviewResponse {
  id: number;
  tmdbId: number;
  movieTitle: string;
  username: string;
  rating: number;
  reviewText: string;
  containsSpoilers: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface WatchlistResponse {
  id: number;
  tmdbId: number;
  movieTitle: string;
  posterPath: string | null;
  status: WatchlistStatus;
  addedAt: string;
}

export type WatchlistStatus = "PLAN_TO_WATCH" | "WATCHING" | "WATCHED";

export interface GenreResponse {
  tmdbId: number;
  name: string;
}

export interface SearchResponse {
  page: number;
  results: TmdbMovie[];
  total_pages: number;
  total_results: number;
}

export interface TmdbMovie {
  id: number;
  title: string;
  overview: string;
  popularity: number;
  poster_path: string | null;
  backdrop_path: string | null;
  release_date: string;
  vote_average: number;
  genre_ids: number[];
}

export interface CreateReviewRequest {
  tmdbId: number;
  rating: number;
  reviewText: string;
  containsSpoilers: boolean;
}

export interface AddToWatchlistRequest {
  tmdbId: number;
  status?: WatchlistStatus;
}

export const TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p";
export const POSTER_PLACEHOLDER = "/posters/placeholder.svg";
export const BACKDROP_PLACEHOLDER = "/posters/backdrop-placeholder.svg";

export function posterUrl(
  path: string | null,
  size: "w185" | "w342" | "w500" = "w500"
): string {
  if (!path) return POSTER_PLACEHOLDER;
  return `${TMDB_IMAGE_BASE}/${size}${path}`;
}

export function backdropUrl(
  path: string | null,
  size: "w780" | "w1280" | "original" = "w1280"
): string {
  if (!path) return BACKDROP_PLACEHOLDER;
  return `${TMDB_IMAGE_BASE}/${size}${path}`;
}
