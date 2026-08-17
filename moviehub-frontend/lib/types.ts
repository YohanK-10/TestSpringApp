export interface MovieResponse {
  tmdbId: number;
  movieTitle: string;
  movieOverview: string;
  releaseDate: string;
  posterPath: string | null;
  backdropPath: string | null;
  rating: number;
  voteCount: number | null;
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
  reviewText: string | null;
  hasReviewText: boolean;
  containsSpoilers: boolean;
  edited: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface ReviewSummaryResponse {
  averageRating: number | null;
  totalRatings: number;
  writtenReviewCount: number;
  ratingDistribution: Record<number, number>;
}

export interface WatchlistResponse {
  id: number;
  tmdbId: number;
  movieTitle: string;
  posterPath: string | null;
  status: WatchlistStatus;
  addedAt: string;
}

export type WatchlistStatus = "PLAN_TO_WATCH" | "WATCHED";

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
  vote_count: number;
  genre_ids: number[];
}

export interface CreateReviewRequest {
  tmdbId: number;
  rating: number;
  reviewText?: string;
  containsSpoilers?: boolean;
}

export interface AddToWatchlistRequest {
  tmdbId: number;
  status?: WatchlistStatus;
}

export type RecommendationMood =
  | "any"
  | "comforting"
  | "funny"
  | "tense"
  | "dark"
  | "emotional"
  | "thoughtful"
  | "adventurous"
  | "cozy"
  | "romantic"
  | "eerie"
  | "hopeful"
  | "bittersweet"
  | "mind-bending"
  | "inspiring";

export type RecommendationRuntimePreference = "any" | "short" | "medium" | "long";
export type RecommendationReleaseEra =
  | "any"
  | "pre-1980"
  | "1980s"
  | "1990s"
  | "2000s"
  | "2010s"
  | "2020s";

export interface RecommendationRequest {
  moods: RecommendationMood[];
  runtimePreference: RecommendationRuntimePreference;
  releaseEras: RecommendationReleaseEra[];
  limit?: number;
  refreshToken?: string;
  starterGenres?: string[];
  starterKeywords?: string[];
  seedTmdbIds?: number[];
  seenTmdbIds?: number[];
}

export interface RecommendationResponse {
  tmdbId: number;
  movieTitle: string;
  movieOverview: string;
  releaseDate: string | null;
  posterPath: string | null;
  backdropPath: string | null;
  rating: number | null;
  voteCount: number | null;
  runtime: number | null;
  popularity: number | null;
  genres: string[];
  onWatchlist: boolean;
  watchlistStatus: WatchlistStatus | null;
  reasons: string[];
}

export type SoloRecommendationMood = RecommendationMood;
export type SoloRuntimePreference = RecommendationRuntimePreference;
export type SoloRecommendationRequest = RecommendationRequest;
export interface SoloRecommendationResponse {
  tmdbId: number;
  movieTitle: string;
  movieOverview: string;
  releaseDate: string | null;
  posterPath: string | null;
  backdropPath: string | null;
  rating: number | null;
  voteCount: number | null;
  runtime: number | null;
  popularity: number | null;
  genres: string[];
  watchlistStatus: WatchlistStatus;
  addedAt: string;
  score: number;
  reasons: string[];
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
