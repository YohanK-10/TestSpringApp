import type {
  AddToWatchlistRequest,
  CreateReviewRequest,
  MovieResponse,
  RecommendationRequest,
  RecommendationResponse,
  ReviewResponse,
  ReviewSummaryResponse,
  SearchResponse,
  SoloRecommendationRequest,
  SoloRecommendationResponse,
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

interface CsrfResponse {
  token: string;
  headerName: string;
}

let csrfState: CsrfResponse | null = null;
let csrfRequest: Promise<CsrfResponse> | null = null;
let refreshRequest: Promise<boolean> | null = null;

async function getCsrfState(forceRefresh = false): Promise<CsrfResponse> {
  if (forceRefresh) {
    csrfState = null;
    csrfRequest = null;
  }
  if (csrfState) return csrfState;
  if (csrfRequest) return csrfRequest;

  csrfRequest = fetch(`${API_BASE}/auth/csrf`, { credentials: "include" })
    .then(async (response) => {
      if (!response.ok) {
        throw new ApiError("AtlasWatch could not initialize request security.", {
          kind: "http",
          status: response.status,
        });
      }
      const state = (await response.json()) as CsrfResponse;
      csrfState = state;
      return state;
    })
    .finally(() => {
      csrfRequest = null;
    });

  return csrfRequest;
}

function isMutation(init?: RequestInit) {
  const method = (init?.method ?? "GET").toUpperCase();
  return !["GET", "HEAD", "OPTIONS"].includes(method);
}

async function buildHeaders(init?: RequestInit, forceCsrf = false): Promise<Headers> {
  const headers = new Headers(init?.headers);
  if (init?.body && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  if (isMutation(init)) {
    const csrf = await getCsrfState(forceCsrf);
    headers.set(csrf.headerName, csrf.token);
  }
  return headers;
}

async function refreshAccessToken(): Promise<boolean> {
  if (refreshRequest) return refreshRequest;

  refreshRequest = (async () => {
    try {
      const headers = await buildHeaders({ method: "POST" });
      let response = await fetch(`${API_BASE}/auth/refresh`, {
        method: "POST",
        credentials: "include",
        headers,
      });

      if (response.status === 403) {
        const retryHeaders = await buildHeaders({ method: "POST" }, true);
        response = await fetch(`${API_BASE}/auth/refresh`, {
          method: "POST",
          credentials: "include",
          headers: retryHeaders,
        });
      }
      return response.ok;
    } catch {
      return false;
    } finally {
      refreshRequest = null;
    }
  })();

  return refreshRequest;
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  if (!API_BASE) {
    throw new ApiError(
      "The frontend API URL is not configured. Set NEXT_PUBLIC_API_URL and try again."
    );
  }

  let response: Response;

  try {
    const headers = await buildHeaders(init);
    response = await fetch(`${API_BASE}${path}`, {
      credentials: "include",
      ...init,
      headers,
    });

    // Spring Security rotates the CSRF token after a successful login. The
    // client may still have the pre-login token cached, so retry a rejected
    // mutation once with a freshly issued token. A genuine authorization
    // failure remains a 403 after this single retry.
    if (response.status === 403 && isMutation(init)) {
      response = await fetch(`${API_BASE}${path}`, {
        credentials: "include",
        ...init,
        headers: await buildHeaders(init, true),
      });
    }

    const canRefresh = response.status === 401 && !path.startsWith("/auth/");
    if (canRefresh && await refreshAccessToken()) {
      response = await fetch(`${API_BASE}${path}`, {
        credentials: "include",
        ...init,
        headers: await buildHeaders(init),
      });
    }
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

export function getReviewSummaryByMovie(tmdbId: number) {
  return request<ReviewSummaryResponse>(`/api/reviews/movie/${tmdbId}/summary`);
}

export async function getMyReviewByMovie(tmdbId: number) {
  try {
    const response = await request<ReviewResponse | undefined>(`/api/reviews/movie/${tmdbId}/mine`);
    return response ?? null;
  } catch (error) {
    if (error instanceof ApiError && (error.status === 401 || error.status === 403 || error.status === 404)) {
      return null;
    }
    throw error;
  }
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

export function getRecommendations(body: RecommendationRequest) {
  return request<RecommendationResponse[]>("/api/recommendations", {
    method: "POST",
    body: JSON.stringify(body),
  });
}

export function getColdStartRecommendations(body: RecommendationRequest) {
  const params = new URLSearchParams();
  body.moods.forEach((mood) => params.append("moods", mood));
  if (body.runtimePreference) {
    params.set("runtimePreference", body.runtimePreference);
  }
  body.releaseEras?.forEach((era) => params.append("releaseEras", era));
  if (body.limit) {
    params.set("limit", String(body.limit));
  }
  if (body.refreshToken) {
    params.set("refreshToken", body.refreshToken);
  }
  body.starterGenres?.forEach((genre) => params.append("starterGenres", genre));
  body.starterKeywords?.forEach((keyword) => params.append("starterKeywords", keyword));
  body.seedTmdbIds?.forEach((tmdbId) => params.append("seedTmdbIds", String(tmdbId)));
  body.seenTmdbIds?.forEach((tmdbId) => params.append("seenTmdbIds", String(tmdbId)));

  const query = params.toString();
  return request<RecommendationResponse[]>(
    `/api/recommendations/cold-start${query ? `?${query}` : ""}`
  );
}

export function getSoloRecommendations(body: SoloRecommendationRequest) {
  return request<SoloRecommendationResponse[]>("/api/recommendations/solo", {
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

export function requestPasswordReset(email: string) {
  return request<void>("/auth/password-reset/request", {
    method: "POST",
    body: JSON.stringify({ email }),
  });
}

export function confirmPasswordReset(email: string, resetCode: string, newPassword: string) {
  return request<void>("/auth/password-reset/confirm", {
    method: "POST",
    body: JSON.stringify({ email, resetCode, newPassword }),
  });
}

export function removeFromWatchlist(id: number) {
  return request<void>(`/api/watchlist/${id}`, { method: "DELETE" });
}

export function logout() {
  return request<void>("/auth/logout", { method: "POST" });
}
