import type {
  AddToWatchlistRequest,
  CreateReviewRequest,
  MovieResponse,
  ReviewResponse,
  SearchResponse,
  WatchlistResponse,
  WatchlistStatus,
} from "./types";

const rawApiBase = process.env.NEXT_PUBLIC_API_URL;

export const API_BASE = rawApiBase?.replace(/\/$/, "") ?? "";

export type ApiErrorKind = "http" | "network" | "unknown";

interface ApiErrorOptions {
  status?: number;
  kind?: ApiErrorKind;
  rawMessage?: string;
}

export class ApiError extends Error {
  status?: number;
  kind: ApiErrorKind;
  rawMessage?: string;

  constructor(message: string, options: ApiErrorOptions = {}) {
    super(message);
    this.name = "ApiError";
    this.status = options.status;
    this.kind = options.kind ?? "unknown";
    this.rawMessage = options.rawMessage;
  }
}

export function isApiError(error: unknown): error is ApiError {
  return error instanceof ApiError;
}

export function getErrorMessage(
  error: unknown,
  fallback = "Something went wrong. Please try again."
) {
  if (error instanceof ApiError) {
    return error.message;
  }
  if (error instanceof Error && error.message) {
    return error.message;
  }
  return fallback;
}

function normalizeServerMessage(text: string, status: number, statusText: string) {
  const trimmed = text.trim();

  if (!trimmed || trimmed === String(status)) {
    if (status >= 500) return "The server hit an error while processing your request.";
    if (status === 404) return "The requested resource was not found.";
    if (status === 401) return "You need to sign in to continue.";
    if (status === 403) return "You do not have permission for this action. Sign in again and retry.";
    return `${status} ${statusText}`.trim();
  }

  try {
    const parsed = JSON.parse(trimmed) as { message?: string; error?: string };
    return parsed.message ?? parsed.error ?? trimmed;
  } catch {
    if (trimmed === "Forbidden" && status === 403) {
      return "You do not have permission for this action. Sign in again and retry.";
    }
    return trimmed;
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  if (!API_BASE) {
    throw new ApiError(
      "The frontend API URL is not configured. Set NEXT_PUBLIC_API_URL and try again."
    );
  }

  let response: Response;

  try {
    response = await fetch(`${API_BASE}${path}`, {
      credentials: "include",
      ...init,
      headers: {
        "Content-Type": "application/json",
        ...init?.headers,
      },
    });
  } catch (error) {
    throw new ApiError("We couldn't reach the AtlasWatch API. Check your connection and try again.", {
      kind: "network",
      rawMessage: error instanceof Error ? error.message : undefined,
    });
  }

  if (!response.ok) {
    const text = await response.text();
    throw new ApiError(
      normalizeServerMessage(text, response.status, response.statusText),
      {
        kind: "http",
        status: response.status,
        rawMessage: text,
      }
    );
  }

  if (response.status === 204) {
    return undefined as T;
  }

  const contentType = response.headers.get("content-type") ?? "";
  if (!contentType.includes("application/json")) {
    return undefined as T;
  }

  return response.json();
}

export function searchMovies(query: string, page = 1) {
  return request<SearchResponse>(
    `/api/movies/search?query=${encodeURIComponent(query)}&page=${page}`
  );
}

export function getTrending() {
  return request<SearchResponse>("/api/movies/trending");
}

export function getMovieDetails(tmdbId: number) {
  return request<MovieResponse>(`/api/movies/${tmdbId}`);
}

export function getReviewsByMovie(tmdbId: number) {
  return request<ReviewResponse[]>(`/api/reviews/movie/${tmdbId}`);
}

export function createReview(body: CreateReviewRequest) {
  return request<ReviewResponse>("/api/reviews", {
    method: "POST",
    body: JSON.stringify(body),
  });
}

export function updateReview(reviewId: number, body: CreateReviewRequest) {
  return request<ReviewResponse>(`/api/reviews/${reviewId}`, {
    method: "PUT",
    body: JSON.stringify(body),
  });
}

export function deleteReview(reviewId: number) {
  return request<void>(`/api/reviews/${reviewId}`, { method: "DELETE" });
}

export function getWatchlist() {
  return request<WatchlistResponse[]>("/api/watchlist");
}

export function addToWatchlist(body: AddToWatchlistRequest) {
  return request<WatchlistResponse>("/api/watchlist", {
    method: "POST",
    body: JSON.stringify(body),
  });
}

export function updateWatchlistStatus(id: number, status: WatchlistStatus) {
  return request<WatchlistResponse>(`/api/watchlist/${id}/status`, {
    method: "PUT",
    body: JSON.stringify({ status }),
  });
}

export function login(loginInfo: string, password: string) {
  return request<void>("/auth/login", {
    method: "POST",
    body: JSON.stringify({ loginInfo, password }),
  });
}

export function register(email: string, username: string, password: string) {
  return request<void>("/auth/register", {
    method: "POST",
    body: JSON.stringify({ email, username, password }),
  });
}

export function verifyEmail(email: string, verificationCode: string) {
  return request<void>("/auth/verify", {
    method: "POST",
    body: JSON.stringify({ email, verificationCode }),
  });
}

export function resendVerificationCode(email: string) {
  return request<void>("/auth/resend", {
    method: "POST",
    body: JSON.stringify({ email }),
  });
}

export function removeFromWatchlist(id: number) {
  return request<void>(`/api/watchlist/${id}`, { method: "DELETE" });
}

export function logout() {
  return request<void>("/auth/logout", { method: "POST" });
}
