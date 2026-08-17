"use client";

import { useCallback, useDeferredValue, useEffect, useMemo, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/components/AuthProvider";
import FeedbackBanner from "@/components/FeedbackBanner";
import RemoteImage from "@/components/RemoteImage";
import StatusPanel from "@/components/StatusPanel";
import {
  ApiError,
  getColdStartRecommendations,
  getErrorMessage,
  getRecommendations,
} from "@/lib/api";
import {
  POSTER_PLACEHOLDER,
  posterUrl,
  type RecommendationMood,
  type RecommendationReleaseEra,
  type RecommendationResponse,
  type RecommendationRuntimePreference,
} from "@/lib/types";

const STARTER_GENRE_OPTIONS = [
  "action",
  "adventure",
  "animation",
  "comedy",
  "crime",
  "drama",
  "fantasy",
  "horror",
  "mystery",
  "romance",
  "science fiction",
  "thriller",
] as const;

const STARTER_KEYWORD_OPTIONS = [
  "coming of age",
  "courtroom",
  "found family",
  "heist",
  "mind game",
  "mystery box",
  "psychological",
  "revenge",
  "road trip",
  "slow burn",
  "space",
  "survival",
  "time travel",
] as const;

const MAX_SELECTED_MOODS = 5;

const MOOD_OPTIONS: { value: RecommendationMood; label: string; description: string }[] = [
  { value: "any", label: "Open to anything", description: "No mood bias, just strong all-around picks." },
  { value: "comforting", label: "Comforting", description: "Warm, easy, low-friction choices." },
  { value: "cozy", label: "Cozy", description: "Soft, familiar, low-stakes comfort." },
  { value: "funny", label: "Funny", description: "Lighter picks with playful energy." },
  { value: "tense", label: "Tense", description: "Suspense, momentum, and edge-of-seat energy." },
  { value: "dark", label: "Dark", description: "Heavier, sharper, moodier stories." },
  { value: "emotional", label: "Emotional", description: "Character-driven and feeling-forward." },
  { value: "thoughtful", label: "Thoughtful", description: "Ideas, reflection, and slower payoff." },
  { value: "adventurous", label: "Adventurous", description: "Escapist, bigger-scale, and propulsive." },
  { value: "romantic", label: "Romantic", description: "Connection, chemistry, and yearning." },
  { value: "hopeful", label: "Hopeful", description: "A more uplifting or restorative finish." },
  { value: "bittersweet", label: "Bittersweet", description: "Tender, wistful, and mixed-emotion stories." },
  { value: "mind-bending", label: "Mind-bending", description: "Twisty ideas and reality-shifting plots." },
  { value: "eerie", label: "Eerie", description: "Unsettling atmosphere without full chaos." },
  { value: "inspiring", label: "Inspiring", description: "A lift, a push, or a sense of momentum." },
];

const RUNTIME_OPTIONS: { value: RecommendationRuntimePreference; label: string; description: string }[] = [
  { value: "any", label: "Any length", description: "Runtime is not part of the decision." },
  { value: "short", label: "Short", description: "Best when you want a lower-commitment watch." },
  { value: "medium", label: "Medium", description: "A balanced pick for a standard movie session." },
  { value: "long", label: "Long", description: "For when you want a bigger, more immersive watch." },
];

const RELEASE_ERA_OPTIONS: { value: RecommendationReleaseEra; label: string }[] = [
  { value: "any", label: "Any era" },
  { value: "pre-1980", label: "Before 1980" },
  { value: "1980s", label: "1980s" },
  { value: "1990s", label: "1990s" },
  { value: "2000s", label: "2000s" },
  { value: "2010s", label: "2010s" },
  { value: "2020s", label: "2020s" },
];

const PICK_FOR_ME_SESSION_KEY = "atlaswatch:pick-for-me:v1";

interface PersistedPickForMeState {
  authenticated: boolean;
  selectedMoods: RecommendationMood[];
  runtimePreference: RecommendationRuntimePreference;
  selectedReleaseEras: RecommendationReleaseEra[];
  limit: number;
  starterGenres: string[];
  starterKeywords: string[];
  seedMovieInput: string;
  results: RecommendationResponse[];
  refreshToken?: string;
  seenTmdbIds: number[];
}

function readPersistedState(authenticated: boolean): PersistedPickForMeState | null {
  try {
    const raw = window.sessionStorage.getItem(PICK_FOR_ME_SESSION_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<PersistedPickForMeState>;
    if (
      parsed.authenticated !== authenticated ||
      !Array.isArray(parsed.selectedMoods) ||
      !Array.isArray(parsed.selectedReleaseEras) ||
      !Array.isArray(parsed.results) ||
      typeof parsed.runtimePreference !== "string" ||
      typeof parsed.limit !== "number"
    ) {
      return null;
    }
    return {
      authenticated,
      selectedMoods: parsed.selectedMoods,
      runtimePreference: parsed.runtimePreference as RecommendationRuntimePreference,
      selectedReleaseEras: parsed.selectedReleaseEras,
      limit: parsed.limit,
      starterGenres: Array.isArray(parsed.starterGenres) ? parsed.starterGenres : [],
      starterKeywords: Array.isArray(parsed.starterKeywords) ? parsed.starterKeywords : [],
      seedMovieInput: typeof parsed.seedMovieInput === "string" ? parsed.seedMovieInput : "",
      results: parsed.results,
      refreshToken: parsed.refreshToken,
      seenTmdbIds: Array.isArray(parsed.seenTmdbIds)
        ? parsed.seenTmdbIds.filter((id): id is number => Number.isInteger(id) && id > 0).slice(-50)
        : parsed.results.map((result) => result.tmdbId).slice(-50),
    };
  } catch {
    return null;
  }
}

function persistState(state: PersistedPickForMeState) {
  try {
    window.sessionStorage.setItem(PICK_FOR_ME_SESSION_KEY, JSON.stringify(state));
  } catch {
    // Recommendation persistence is a navigation convenience, not a request prerequisite.
  }
}

function createRefreshToken() {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }

  return `refresh-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}

function parseSeedTmdbIds(value: string) {
  const ids = value.match(/\d+/g) ?? [];

  return Array.from(
    new Set(
      ids
        .map((id) => Number(id))
        .filter((id) => Number.isInteger(id) && id > 0)
    )
  ).slice(0, 5);
}

function formatStarterLabel(value: string) {
  return value
    .split(" ")
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}

function getRecommendationErrorCopy(error: unknown) {
  if (error instanceof ApiError && (error.status === 401 || error.status === 403)) {
    return {
      title: "Sign in again to personalize your picks",
      description:
        "AtlasWatch could not read your account session, so it could not fold in your ratings, reviews, and watchlist state.",
      actionLabel: "Go to login",
      action: "/login",
    };
  }

  if (error instanceof ApiError && error.kind === "network") {
    return {
      title: "We couldn't reach the recommendation service",
      description:
        "AtlasWatch could not connect to the backend just now, so the recommendation pass never finished. Try again in a moment.",
      actionLabel: "Try again",
      action: "retry",
    };
  }

  return {
    title: "We couldn't generate recommendations right now",
    description:
      "The recommendation request did not complete successfully. Adjust the filters or retry the request.",
    actionLabel: "Try again",
    action: "retry",
  };
}

export default function PickForMePage() {
  const router = useRouter();
  const { isAuthenticated } = useAuth();
  const [selectedMoods, setSelectedMoods] = useState<RecommendationMood[]>(["any"]);
  const [runtimePreference, setRuntimePreference] = useState<RecommendationRuntimePreference>("any");
  const [selectedReleaseEras, setSelectedReleaseEras] = useState<RecommendationReleaseEra[]>(["any"]);
  const [limit, setLimit] = useState(5);
  const [starterGenres, setStarterGenres] = useState<string[]>([]);
  const [starterKeywords, setStarterKeywords] = useState<string[]>([]);
  const [seedMovieInput, setSeedMovieInput] = useState("");
  const [results, setResults] = useState<RecommendationResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);
  const [lastRefreshToken, setLastRefreshToken] = useState<string | undefined>(undefined);
  const [feedback, setFeedback] = useState<{ tone: "success" | "error" | "info"; title: string; message: string } | null>(null);
  const [sessionStateReady, setSessionStateReady] = useState(false);
  const skipRestoredAutoLoad = useRef(false);
  const sessionSeenTmdbIds = useRef<number[]>([]);
  const deferredSeedMovieInput = useDeferredValue(seedMovieInput);
  const seedTmdbIds = useMemo(() => parseSeedTmdbIds(deferredSeedMovieInput), [deferredSeedMovieInput]);

  useEffect(() => {
    const persisted = readPersistedState(isAuthenticated);
    if (persisted) {
      setSelectedMoods(persisted.selectedMoods);
      setRuntimePreference(persisted.runtimePreference);
      setSelectedReleaseEras(persisted.selectedReleaseEras);
      setLimit(persisted.limit);
      setStarterGenres(persisted.starterGenres);
      setStarterKeywords(persisted.starterKeywords);
      setSeedMovieInput(persisted.seedMovieInput);
      setResults(persisted.results);
      setLastRefreshToken(persisted.refreshToken);
      sessionSeenTmdbIds.current = persisted.seenTmdbIds;
      setLoading(false);
      skipRestoredAutoLoad.current = true;
    }
    setSessionStateReady(true);
  }, [isAuthenticated]);

  const activeMoods = useMemo(
    () => MOOD_OPTIONS.filter((option) => selectedMoods.includes(option.value)),
    [selectedMoods]
  );
  const activeRuntime = useMemo(
    () => RUNTIME_OPTIONS.find((option) => option.value === runtimePreference) ?? RUNTIME_OPTIONS[0],
    [runtimePreference]
  );
  const activeReleaseEras = useMemo(
    () => RELEASE_ERA_OPTIONS.filter((option) => selectedReleaseEras.includes(option.value)),
    [selectedReleaseEras]
  );
  const activeStarterGenres = useMemo(
    () => STARTER_GENRE_OPTIONS.filter((genre) => starterGenres.includes(genre)),
    [starterGenres]
  );
  const activeStarterKeywords = useMemo(
    () => STARTER_KEYWORD_OPTIONS.filter((keyword) => starterKeywords.includes(keyword)),
    [starterKeywords]
  );
  const selectionSummary = useMemo(() => {
    const moodSummary =
      activeMoods.length === 1
        ? activeMoods[0]?.label ?? "Open to anything"
        : activeMoods.map((option) => option.label).join(", ");
    const runtimeSummary =
      activeRuntime.value === "any"
        ? "any runtime"
        : `${activeRuntime.label.toLowerCase()} runtime`;
    const eraSummary =
      activeReleaseEras.length === 1 && activeReleaseEras[0]?.value === "any"
        ? "any era"
        : activeReleaseEras.map((option) => option.label).join(" or ");

    return `${moodSummary} with ${runtimeSummary}, from ${eraSummary}`;
  }, [activeMoods, activeReleaseEras, activeRuntime]);

  const resultSummary = useMemo(() => {
    if (results.length === 0) {
      return `AtlasWatch is still looking for strong candidates for ${selectionSummary}.`;
    }

    if (results.length === limit) {
      return `AtlasWatch found the full ${limit}-pick shortlist for ${selectionSummary}.`;
    }

    return `AtlasWatch found ${results.length} pick${results.length === 1 ? "" : "s"} for ${selectionSummary}.`;
  }, [limit, results.length, selectionSummary]);

  const loadRecommendations = useCallback(
    async (options?: {
      moods?: RecommendationMood[];
      runtimePreference?: RecommendationRuntimePreference;
      releaseEras?: RecommendationReleaseEra[];
      limit?: number;
      starterGenres?: string[];
      starterKeywords?: string[];
      seedTmdbIds?: number[];
      seedMovieInput?: string;
      refreshToken?: string;
      seenTmdbIds?: number[];
    }) => {
      setLoading(true);
      setError(null);

      const request = {
        moods: options?.moods ?? selectedMoods,
        runtimePreference: options?.runtimePreference ?? runtimePreference,
        releaseEras: options?.releaseEras ?? selectedReleaseEras,
        limit: options?.limit ?? limit,
        refreshToken: options?.refreshToken,
        starterGenres: isAuthenticated ? undefined : options?.starterGenres ?? starterGenres,
        starterKeywords: isAuthenticated ? undefined : options?.starterKeywords ?? starterKeywords,
        seedTmdbIds: isAuthenticated ? undefined : options?.seedTmdbIds ?? seedTmdbIds,
        seenTmdbIds: options?.seenTmdbIds ?? [],
      };
      setLastRefreshToken(request.refreshToken);

      try {
        const data = isAuthenticated
          ? await getRecommendations(request)
          : await getColdStartRecommendations(request);
        const nextSeenTmdbIds = Array.from(new Set([
          ...(request.seenTmdbIds ?? []),
          ...data.map((recommendation) => recommendation.tmdbId),
        ])).slice(-50);
        sessionSeenTmdbIds.current = nextSeenTmdbIds;
        setResults(data);
        persistState({
          authenticated: isAuthenticated,
          selectedMoods: request.moods,
          runtimePreference: request.runtimePreference,
          selectedReleaseEras: request.releaseEras,
          limit: request.limit,
          starterGenres: request.starterGenres ?? [],
          starterKeywords: request.starterKeywords ?? [],
          seedMovieInput: options?.seedMovieInput ?? seedMovieInput,
          results: data,
          refreshToken: request.refreshToken,
          seenTmdbIds: nextSeenTmdbIds,
        });
        setFeedback(null);
      } catch (loadError) {
        setResults([]);
        setError(loadError);
        setFeedback({
          tone: "error",
          title: "Recommendation request failed",
          message: getErrorMessage(loadError, "AtlasWatch couldn't finish building your shortlist."),
        });
      } finally {
        setLoading(false);
      }
    },
    [isAuthenticated, limit, runtimePreference, seedMovieInput, seedTmdbIds, selectedMoods, selectedReleaseEras, starterGenres, starterKeywords]
  );

  useEffect(() => {
    if (!sessionStateReady) return;
    if (skipRestoredAutoLoad.current) {
      skipRestoredAutoLoad.current = false;
      return;
    }
    void loadRecommendations({
      moods: selectedMoods,
      runtimePreference,
      releaseEras: selectedReleaseEras,
      limit,
      starterGenres,
      starterKeywords,
      seedTmdbIds,
      seedMovieInput,
      seenTmdbIds: [],
    });
  }, [isAuthenticated, limit, loadRecommendations, runtimePreference, seedMovieInput, seedTmdbIds, selectedMoods, selectedReleaseEras, sessionStateReady, starterGenres, starterKeywords]);

  useEffect(() => {
    if (!feedback) return;
    const id = window.setTimeout(() => setFeedback(null), 4200);
    return () => window.clearTimeout(id);
  }, [feedback]);

  const resetFilters = () => {
    setSelectedMoods(["any"]);
    setRuntimePreference("any");
    setSelectedReleaseEras(["any"]);
    setLimit(5);
    setStarterGenres([]);
    setStarterKeywords([]);
    setSeedMovieInput("");
    setLastRefreshToken(undefined);
    sessionSeenTmdbIds.current = [];
    void loadRecommendations({
      moods: ["any"],
      runtimePreference: "any",
      releaseEras: ["any"],
      limit: 5,
      starterGenres: [],
      starterKeywords: [],
      seedTmdbIds: [],
      seedMovieInput: "",
      seenTmdbIds: [],
    });
  };

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    await loadRecommendations({
      moods: selectedMoods,
      runtimePreference,
      releaseEras: selectedReleaseEras,
      limit,
      starterGenres,
      starterKeywords,
      seedTmdbIds,
      seedMovieInput,
      refreshToken: createRefreshToken(),
      seenTmdbIds: sessionSeenTmdbIds.current,
    });
  };

  const toggleMood = (value: RecommendationMood) => {
    setSelectedMoods((current) => {
      if (value === "any") {
        return ["any"];
      }

      const withoutAny = current.filter((item) => item !== "any");
      if (withoutAny.includes(value)) {
        const next = withoutAny.filter((item) => item !== value);
        return next.length > 0 ? next : ["any"];
      }

      // Mirrors the server-side cap. Blending more than this is not a
      // meaningful request and the API rejects it.
      if (withoutAny.length >= MAX_SELECTED_MOODS) {
        return current;
      }

      return [...withoutAny, value];
    });
  };

  const toggleReleaseEra = (value: RecommendationReleaseEra) => {
    setSelectedReleaseEras((current) => {
      if (value === "any") {
        return ["any"];
      }

      const withoutAny = current.filter((item) => item !== "any");
      if (withoutAny.includes(value)) {
        const next = withoutAny.filter((item) => item !== value);
        return next.length > 0 ? next : ["any"];
      }
      return [...withoutAny, value];
    });
  };

  const toggleStarterGenre = (genre: string) => {
    setStarterGenres((current) => {
      if (current.includes(genre)) {
        return current.filter((item) => item !== genre);
      }
      if (current.length >= 5) {
        return current;
      }
      return [...current, genre];
    });
  };

  const toggleStarterKeyword = (keyword: string) => {
    setStarterKeywords((current) => {
      if (current.includes(keyword)) {
        return current.filter((item) => item !== keyword);
      }
      if (current.length >= 10) {
        return current;
      }
      return [...current, keyword];
    });
  };

  const formatRuntime = (runtime: number | null) => {
    if (!runtime) return "Unknown runtime";
    const hours = Math.floor(runtime / 60);
    const mins = runtime % 60;
    return hours > 0 ? `${hours}h ${mins}m` : `${mins}m`;
  };

  const loadingTitle = isAuthenticated
    ? "Building your personalized shortlist"
    : "Scanning the wider catalog for strong fits";

  const loadingDescription = isAuthenticated
    ? `AtlasWatch is ranking up to ${limit} picks for ${selectionSummary}, while blending in your ratings, reviews, and watchlist signals.`
    : `AtlasWatch is pulling up to ${limit} wider-catalog picks for ${selectionSummary}.${activeStarterGenres.length > 0 || activeStarterKeywords.length > 0 || seedTmdbIds.length > 0 ? " Your starter hints are being folded into this cold-start pass." : " Sign in anytime to fold your personal taste into the ranking."}`;

  const topPick = results[0];
  const remainingPicks = results.slice(1);

  if (error && !loading) {
    const copy = getRecommendationErrorCopy(error);
    return (
      <div className="app-page space-y-6">
        <StatusPanel
          title={copy.title}
          description={copy.description}
          tone="error"
          actionLabel={copy.actionLabel}
          onAction={() => {
            if (copy.action === "retry") {
              void loadRecommendations({
                refreshToken: lastRefreshToken,
                seenTmdbIds: sessionSeenTmdbIds.current,
              });
            } else {
              router.push(copy.action);
            }
          }}
          secondaryLabel="Browse movies"
          onSecondaryAction={() => router.push("/homepage")}
        />
      </div>
    );
  }

  return (
    <div className="app-page space-y-8">
      <section className="app-surface app-card overflow-hidden p-6 sm:p-8">
        <div className="grid gap-8 lg:grid-cols-[1.05fr,0.95fr]">
          <div>
            <p className="text-xs uppercase tracking-[0.26em] text-amber-300/85">Recommended for you</p>
            <h1 className="app-title mt-3 max-w-3xl">Tell AtlasWatch what feels right today.</h1>
            <p className="app-copy-soft mt-4 max-w-2xl text-sm leading-7 sm:text-base">
              AtlasWatch narrows the wider movie catalog into a short, explainable list. When you are signed in,
              it also folds in your ratings, reviews, and watchlist signals before ranking the final picks.
            </p>

            <div className="mt-5 flex flex-wrap gap-2">
              <span
                className={`app-pill ${
                  isAuthenticated
                    ? "border-emerald-400/18 bg-emerald-400/10 text-emerald-100"
                    : "border-cyan-400/18 bg-cyan-400/10 text-cyan-100"
                }`}
              >
                {isAuthenticated ? "Personalized mode" : "Preview mode"}
              </span>
              <span className="app-pill bg-black/20">{activeRuntime.label}</span>
              <span className="app-pill bg-black/20">
                {activeReleaseEras.map((option) => option.label).join(" / ")}
              </span>
              {activeMoods.slice(0, 3).map((option) => (
                <span key={option.value} className="app-pill bg-black/20">
                  {option.label}
                </span>
              ))}
              {!isAuthenticated && activeStarterGenres.slice(0, 2).map((genre) => (
                <span key={genre} className="app-pill border-cyan-400/15 bg-cyan-400/8 text-cyan-100">
                  {formatStarterLabel(genre)}
                </span>
              ))}
              {!isAuthenticated && activeStarterKeywords.slice(0, 2).map((keyword) => (
                <span key={keyword} className="app-pill border-cyan-400/15 bg-cyan-400/8 text-cyan-100">
                  {formatStarterLabel(keyword)}
                </span>
              ))}
            </div>

            <div className="mt-6 grid gap-3 sm:grid-cols-3">
              <div className="rounded-[1.2rem] border border-slate-700/35 bg-white/5 p-4">
                <p className="text-sm font-semibold text-white">Current vibe</p>
                <p className="mt-2 text-sm text-slate-300">
                  {activeMoods.map((option) => option.label).join(", ")}
                </p>
                <p className="mt-1 text-sm text-slate-400">
                  {activeMoods.length === 1
                    ? activeMoods[0]?.description
                    : "AtlasWatch blends all selected moods into one recommendation pass."}
                </p>
              </div>
              <div className="rounded-[1.2rem] border border-slate-700/35 bg-white/5 p-4">
                <p className="text-sm font-semibold text-white">Runtime lens</p>
                <p className="mt-2 text-sm text-slate-300">{activeRuntime.label}</p>
                <p className="mt-1 text-sm text-slate-400">{activeRuntime.description}</p>
              </div>
              <div className="rounded-[1.2rem] border border-slate-700/35 bg-white/5 p-4">
                <p className="text-sm font-semibold text-white">Release era</p>
                <p className="mt-2 text-sm text-slate-300">
                  {activeReleaseEras.map((option) => option.label).join(", ")}
                </p>
                <p className="mt-1 text-sm text-slate-400">
                  {selectedReleaseEras.includes("any")
                    ? "Release year is not part of the decision."
                    : "Only movies from the selected periods are eligible."}
                </p>
              </div>
            </div>

            {isAuthenticated ? (
              <div className="mt-5 rounded-[1.2rem] border border-emerald-400/20 bg-emerald-400/8 p-4">
                <p className="text-sm font-semibold text-emerald-100">Personalized ranking is active</p>
                <p className="mt-2 text-sm text-emerald-50/85">
                  AtlasWatch is blending your saved watchlist, review history, and past ratings before it orders the final shortlist.
                </p>
              </div>
            ) : (
              <div className="mt-5 rounded-[1.2rem] border border-cyan-400/20 bg-cyan-400/8 p-4">
                <p className="text-sm font-semibold text-cyan-100">Browsing without an account</p>
                <p className="mt-2 text-sm text-cyan-50/85">
                  You are seeing wider-catalog picks. Sign in if you want AtlasWatch to blend in your own ratings,
                  reviews, and watchlist taste signals.
                </p>
                <p className="mt-2 text-sm text-cyan-50/70">
                  You can also give AtlasWatch a few starter genres, themes, or seed movies below to make the first shortlist less generic.
                </p>
                <div className="mt-4 flex flex-wrap gap-3">
                  <button type="button" onClick={() => router.push("/login")} className="btn-secondary">
                    Sign in
                  </button>
                  <button type="button" onClick={() => router.push("/register")} className="btn-secondary">
                    Create account
                  </button>
                </div>
              </div>
            )}
          </div>

          <form onSubmit={handleSubmit} className="rounded-[1.5rem] border border-slate-700/35 bg-white/4 p-5 sm:p-6">
            <div>
              <p className="text-sm font-semibold text-white">1. What kind of vibe are you after?</p>
              <p className="mt-2 text-sm text-slate-400">Choose one or more. AtlasWatch will blend them together.</p>
              <div className="mt-4 flex flex-wrap gap-2">
                {MOOD_OPTIONS.map((option) => (
                  <button
                    key={option.value}
                    type="button"
                    onClick={() => toggleMood(option.value)}
                    className={`rounded-full px-4 py-2 text-sm font-semibold transition ${
                      selectedMoods.includes(option.value)
                        ? "bg-amber-400/14 text-amber-100"
                        : "border border-slate-700/35 bg-white/5 text-slate-300 hover:bg-white/8 hover:text-white"
                    }`}
                  >
                    {option.label}
                  </button>
                ))}
              </div>
            </div>

            <div className="mt-6">
              <p className="text-sm font-semibold text-white">2. How much time do you have?</p>
              <div className="mt-4 grid gap-2 sm:grid-cols-2">
                {RUNTIME_OPTIONS.map((option) => (
                  <button
                    key={option.value}
                    type="button"
                    onClick={() => setRuntimePreference(option.value)}
                    className={`rounded-[1rem] border px-4 py-3 text-left transition ${
                      runtimePreference === option.value
                        ? "border-amber-400/28 bg-amber-400/8 text-amber-100"
                        : "border-slate-700/35 bg-white/5 text-slate-300 hover:bg-white/8 hover:text-white"
                    }`}
                  >
                    <p className="text-sm font-semibold">{option.label}</p>
                    <p className="mt-1 text-sm text-slate-400">{option.description}</p>
                  </button>
                ))}
              </div>
            </div>

            <div className="mt-6">
              <p className="text-sm font-semibold text-white">3. Which release eras are you open to?</p>
              <p className="mt-2 text-sm text-slate-400">Optional. Choose one or more decades; AtlasWatch will treat them as a strict filter.</p>
              <div className="mt-4 flex flex-wrap gap-2">
                {RELEASE_ERA_OPTIONS.map((option) => (
                  <button
                    key={option.value}
                    type="button"
                    onClick={() => toggleReleaseEra(option.value)}
                    aria-pressed={selectedReleaseEras.includes(option.value)}
                    className={`rounded-full px-4 py-2 text-sm font-semibold transition ${
                      selectedReleaseEras.includes(option.value)
                        ? "bg-amber-400/14 text-amber-100"
                        : "border border-slate-700/35 bg-white/5 text-slate-300 hover:bg-white/8 hover:text-white"
                    }`}
                  >
                    {option.label}
                  </button>
                ))}
              </div>
            </div>

            <div className="mt-6">
              <label className="mb-2 block text-sm font-semibold text-white" htmlFor="result-limit">
                4. How many picks should AtlasWatch return?
              </label>
              <select
                id="result-limit"
                value={limit}
                onChange={(event) => setLimit(Number(event.target.value))}
                className="field-select max-w-xs"
              >
                {[3, 5, 7, 10].map((option) => (
                  <option key={option} value={option} className="bg-slate-900">
                    {option} picks
                  </option>
                ))}
              </select>
              <p className="mt-2 text-sm text-slate-400">{resultSummary}</p>
            </div>

            {!isAuthenticated && (
              <div className="mt-6 space-y-6">
                <div>
                  <p className="text-sm font-semibold text-white">5. Give AtlasWatch a few starter genres</p>
                  <p className="mt-2 text-sm text-slate-400">
                    Optional. Pick up to five so the first shortlist has a stronger starting shape.
                  </p>
                  <div className="mt-4 flex flex-wrap gap-2">
                    {STARTER_GENRE_OPTIONS.map((genre) => (
                      <button
                        key={genre}
                        type="button"
                        onClick={() => toggleStarterGenre(genre)}
                        className={`rounded-full px-4 py-2 text-sm font-semibold transition ${
                          starterGenres.includes(genre)
                            ? "border border-cyan-400/30 bg-cyan-400/14 text-cyan-100"
                            : "border border-slate-700/35 bg-white/5 text-slate-300 hover:bg-white/8 hover:text-white"
                        }`}
                      >
                        {formatStarterLabel(genre)}
                      </button>
                    ))}
                  </div>
                  <p className="mt-2 text-sm text-slate-500">{starterGenres.length}/5 selected</p>
                </div>

                <div>
                  <p className="text-sm font-semibold text-white">6. Add a few story themes</p>
                  <p className="mt-2 text-sm text-slate-400">
                    Optional. These help the cold-start ranking distinguish between broad genres.
                  </p>
                  <div className="mt-4 flex flex-wrap gap-2">
                    {STARTER_KEYWORD_OPTIONS.map((keyword) => (
                      <button
                        key={keyword}
                        type="button"
                        onClick={() => toggleStarterKeyword(keyword)}
                        className={`rounded-full px-4 py-2 text-sm font-semibold transition ${
                          starterKeywords.includes(keyword)
                            ? "border border-cyan-400/30 bg-cyan-400/14 text-cyan-100"
                            : "border border-slate-700/35 bg-white/5 text-slate-300 hover:bg-white/8 hover:text-white"
                        }`}
                      >
                        {formatStarterLabel(keyword)}
                      </button>
                    ))}
                  </div>
                  <p className="mt-2 text-sm text-slate-500">{starterKeywords.length}/10 selected</p>
                </div>

                <div>
                  <label className="mb-2 block text-sm font-semibold text-white" htmlFor="starter-seed-movies">
                    7. Seed a few movies you already know
                  </label>
                  <input
                    id="starter-seed-movies"
                    type="text"
                    value={seedMovieInput}
                    onChange={(event) => setSeedMovieInput(event.target.value)}
                    placeholder="Paste TMDB IDs or AtlasWatch movie links, separated by commas"
                    className="field-input"
                  />
                  <p className="mt-2 text-sm text-slate-400">
                    Optional. AtlasWatch will read up to five TMDB IDs from this field.
                  </p>
                  {seedTmdbIds.length > 0 && (
                    <p className="mt-2 text-sm text-cyan-100">
                      Using seed movies: {seedTmdbIds.join(", ")}
                    </p>
                  )}
                </div>
              </div>
            )}

            <div className="mt-8 flex flex-wrap gap-3">
              <button type="submit" disabled={loading} className="btn-primary">
                {loading ? "Refreshing shortlist..." : "Try another mix"}
              </button>
              <button
                type="button"
                onClick={resetFilters}
                disabled={loading}
                className="btn-secondary"
              >
                Reset filters
              </button>
            </div>
          </form>
        </div>
      </section>

      {feedback && (
        <FeedbackBanner
          tone={feedback.tone}
          title={feedback.title}
          message={feedback.message}
          onDismiss={() => setFeedback(null)}
        />
      )}

      {loading ? (
        <section className="space-y-4">
          <StatusPanel
            title={loadingTitle}
            description={loadingDescription}
            tone="default"
          />
          <div className="skeleton-block h-72 rounded-[1.5rem]" />
          <div className="grid gap-4 lg:grid-cols-2">
            <div className="skeleton-block h-52 rounded-[1.5rem]" />
            <div className="skeleton-block h-52 rounded-[1.5rem]" />
          </div>
        </section>
      ) : results.length === 0 ? (
        <StatusPanel
          title="No strong matches for this filter mix yet"
          description={`AtlasWatch could not find a confident shortlist for ${selectionSummary}. Try broadening the vibe mix, loosening the runtime lens, or resetting the filters for a wider pass.`}
          actionLabel="Reset filters"
          onAction={resetFilters}
          secondaryLabel={isAuthenticated ? "Browse movies" : "Sign in"}
          onSecondaryAction={() => router.push(isAuthenticated ? "/homepage" : "/login")}
        />
      ) : (
        <section className="space-y-6">
          <div className="app-surface app-card p-5 sm:p-6">
            <div className="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
              <div>
                <p className="text-xs uppercase tracking-[0.26em] text-amber-300/85">Shortlist ready</p>
                <h2 className="app-section-title mt-2">
                  {results.length} pick{results.length === 1 ? "" : "s"} for {selectionSummary}
                </h2>
                <p className="app-copy-muted mt-2 text-sm">
                  {isAuthenticated
                    ? "These results are ranked using the wider catalog plus your ratings, reviews, and watchlist signals."
                    : activeStarterGenres.length > 0 || activeStarterKeywords.length > 0 || seedTmdbIds.length > 0
                      ? "These results come from the wider catalog, guided by your starter genres, themes, and seed-movie hints."
                      : "These results come from the wider catalog. Sign in to blend your own watch history and saved taste into the ranking."}
                </p>
              </div>
              <div className="flex flex-wrap gap-2">
                <span
                  className={`app-pill ${
                    isAuthenticated
                      ? "border-emerald-400/18 bg-emerald-400/10 text-emerald-100"
                      : "border-cyan-400/18 bg-cyan-400/10 text-cyan-100"
                  }`}
                >
                  {isAuthenticated ? "Personalized" : "Preview"}
                </span>
                <span className="app-pill bg-black/20">{activeRuntime.label}</span>
                {activeMoods.slice(0, 2).map((option) => (
                  <span key={option.value} className="app-pill bg-black/20">
                    {option.label}
                  </span>
                ))}
                {!isAuthenticated && activeStarterGenres.slice(0, 1).map((genre) => (
                  <span key={genre} className="app-pill border-cyan-400/15 bg-cyan-400/8 text-cyan-100">
                    {formatStarterLabel(genre)}
                  </span>
                ))}
                {!isAuthenticated && activeStarterKeywords.slice(0, 1).map((keyword) => (
                  <span key={keyword} className="app-pill border-cyan-400/15 bg-cyan-400/8 text-cyan-100">
                    {formatStarterLabel(keyword)}
                  </span>
                ))}
              </div>
            </div>
          </div>

          {topPick && (
            <article className="app-surface app-card overflow-hidden">
              <div className="grid gap-6 p-5 sm:p-6 lg:grid-cols-[220px,1fr] lg:items-start">
                <button
                  type="button"
                  onClick={() => router.push(`/movie/${topPick.tmdbId}`)}
                  className="mx-auto w-[210px] max-w-full lg:mx-0"
                >
                  <RemoteImage
                    src={posterUrl(topPick.posterPath, "w342")}
                    fallbackSrc={POSTER_PLACEHOLDER}
                    alt={topPick.movieTitle}
                    className="aspect-[2/3] w-full rounded-[1.2rem] object-cover"
                    loading="lazy"
                  />
                </button>

                <div className="space-y-5">
                  <div className="flex flex-col gap-3 xl:flex-row xl:items-start xl:justify-between">
                    <div>
                      <p className="text-xs uppercase tracking-[0.26em] text-amber-300/85">Top pick</p>
                      <h2 className="app-section-title mt-2">{topPick.movieTitle}</h2>
                      <p className="app-copy-muted mt-2 text-sm">
                        {topPick.onWatchlist
                          ? "Already saved in your watchlist."
                          : isAuthenticated
                            ? "Picked from the wider catalog using your current filters and taste signals."
                            : "Picked from the wider catalog while AtlasWatch learns your taste."}
                      </p>
                    </div>
                    {topPick.onWatchlist && (
                      <span className="app-pill border-amber-400/18 bg-amber-400/10 text-amber-100">
                        On your watchlist
                      </span>
                    )}
                  </div>

                  <p className="text-sm leading-7 text-slate-200 sm:text-base">
                    {topPick.movieOverview || "No overview is available for this movie yet."}
                  </p>

                  <div className="flex flex-wrap gap-2">
                    {topPick.rating && topPick.rating > 0 && (
                      <span className="app-pill border-amber-400/18 bg-black/20 text-amber-100">
                        {topPick.rating.toFixed(1)} / 10
                      </span>
                    )}
                    <span className="app-pill bg-black/20">{formatRuntime(topPick.runtime)}</span>
                    {topPick.releaseDate && (
                      <span className="app-pill bg-black/20">
                        {topPick.releaseDate.split("-")[0]}
                      </span>
                    )}
                    {topPick.genres.map((genre) => (
                      <span
                        key={genre}
                        className="rounded-full border border-cyan-400/15 bg-cyan-400/8 px-3 py-1.5 text-xs font-medium text-cyan-100"
                      >
                        {genre}
                      </span>
                    ))}
                  </div>

                  <div className="rounded-[1.2rem] border border-slate-700/35 bg-white/4 p-4">
                    <p className="text-sm font-semibold text-white">Why this made the shortlist</p>
                    <div className="mt-3 space-y-2">
                      {topPick.reasons.map((reason) => (
                        <div key={reason} className="flex gap-3 text-sm text-slate-300">
                          <span className="mt-1 h-2 w-2 shrink-0 rounded-full bg-amber-400" />
                          <span>{reason}</span>
                        </div>
                      ))}
                    </div>
                  </div>

                  <div className="flex flex-wrap gap-3">
                    <button
                      type="button"
                      onClick={() => router.push(`/movie/${topPick.tmdbId}`)}
                      className="btn-primary"
                    >
                      Open movie
                    </button>
                    <button
                      type="button"
                      onClick={() => router.push(topPick.onWatchlist && isAuthenticated ? "/watchlist" : "/homepage")}
                      className="btn-secondary"
                    >
                      {topPick.onWatchlist && isAuthenticated ? "Open watchlist" : "Browse more movies"}
                    </button>
                  </div>
                </div>
              </div>
            </article>
          )}

          {remainingPicks.length > 0 && (
            <div className="grid gap-4 xl:grid-cols-2">
              {remainingPicks.map((pick) => (
                <article key={pick.tmdbId} className="app-surface app-card p-4 sm:p-5">
                  <div className="flex gap-4">
                    <button
                      type="button"
                      onClick={() => router.push(`/movie/${pick.tmdbId}`)}
                      className="shrink-0"
                    >
                      <RemoteImage
                        src={posterUrl(pick.posterPath, "w185")}
                        fallbackSrc={POSTER_PLACEHOLDER}
                        alt={pick.movieTitle}
                        className="aspect-[2/3] w-24 rounded-[1rem] object-cover"
                        loading="lazy"
                      />
                    </button>

                    <div className="min-w-0 flex-1">
                      <div>
                        <button
                          type="button"
                          onClick={() => router.push(`/movie/${pick.tmdbId}`)}
                          className="text-left"
                        >
                          <h3 className="text-lg font-semibold text-white transition hover:text-amber-200">
                            {pick.movieTitle}
                          </h3>
                        </button>
                        <div className="mt-2 flex flex-wrap gap-2">
                          {pick.onWatchlist && (
                            <span className="app-pill border-amber-400/18 bg-amber-400/10 text-amber-100">
                              On your watchlist
                            </span>
                          )}
                          {pick.releaseDate && (
                            <span className="app-pill bg-black/20">
                              {pick.releaseDate.split("-")[0]}
                            </span>
                          )}
                        </div>
                      </div>

                      <div className="mt-3 flex flex-wrap gap-2">
                        <span className="app-pill bg-black/20">{formatRuntime(pick.runtime)}</span>
                        {pick.genres.slice(0, 3).map((genre) => (
                          <span
                            key={genre}
                            className="rounded-full border border-cyan-400/15 bg-cyan-400/8 px-3 py-1.5 text-xs font-medium text-cyan-100"
                          >
                            {genre}
                          </span>
                        ))}
                      </div>

                      <div className="mt-4 space-y-2">
                        <p className="text-sm font-semibold text-white">Why it works</p>
                        {pick.reasons.map((reason) => (
                          <div key={reason} className="flex gap-3 text-sm text-slate-300">
                            <span className="mt-1 h-2 w-2 shrink-0 rounded-full bg-amber-400" />
                            <span>{reason}</span>
                          </div>
                        ))}
                      </div>
                    </div>
                  </div>
                </article>
              ))}
            </div>
          )}
        </section>
      )}
    </div>
  );
}
