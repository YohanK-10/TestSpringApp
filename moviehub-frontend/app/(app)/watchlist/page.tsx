"use client";

import { useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import FeedbackBanner from "@/components/FeedbackBanner";
import RemoteImage from "@/components/RemoteImage";
import StatusPanel from "@/components/StatusPanel";
import { WatchlistSkeleton } from "@/components/Skeletons";
import {
  ApiError,
  getErrorMessage,
  getWatchlist,
  removeFromWatchlist,
  updateWatchlistStatus,
} from "@/lib/api";
import {
  POSTER_PLACEHOLDER,
  posterUrl,
  type WatchlistResponse,
  type WatchlistStatus,
} from "@/lib/types";

const TABS: { label: string; value: WatchlistStatus | "ALL" }[] = [
  { label: "All", value: "ALL" },
  { label: "Plan to watch", value: "PLAN_TO_WATCH" },
  { label: "Watching", value: "WATCHING" },
  { label: "Watched", value: "WATCHED" },
];

const STATUS_OPTIONS: { label: string; value: WatchlistStatus }[] = [
  { label: "Plan to watch", value: "PLAN_TO_WATCH" },
  { label: "Watching", value: "WATCHING" },
  { label: "Watched", value: "WATCHED" },
];

const STATUS_COLORS: Record<WatchlistStatus, string> = {
  PLAN_TO_WATCH: "border-blue-400/18 bg-blue-500/10 text-blue-100",
  WATCHING: "border-amber-400/18 bg-amber-500/10 text-amber-100",
  WATCHED: "border-emerald-400/18 bg-emerald-500/10 text-emerald-100",
};

interface FeedbackState {
  tone: "success" | "error" | "info";
  title: string;
  message: string;
}

function getWatchlistErrorCopy(error: unknown) {
  if (error instanceof ApiError && (error.status === 401 || error.status === 403)) {
    return {
      title: "Sign in to view your watchlist",
      description:
        "The watchlist endpoint requires an authenticated user. Log in again and then reopen this page.",
      actionLabel: "Go to login",
      action: "/login",
    };
  }

  if (error instanceof ApiError && error.kind === "network") {
    return {
      title: "Watchlist can't reach the backend",
      description:
        "The frontend could not connect to the API, so your watchlist never loaded. Make sure the backend is running and try again.",
      actionLabel: "Retry",
      action: "retry",
    };
  }

  return {
    title: "We couldn't load your watchlist",
    description:
      "The request completed with an unexpected error. Retry the request or head back to the homepage.",
    actionLabel: "Retry",
    action: "retry",
  };
}

export default function WatchlistPage() {
  const [items, setItems] = useState<WatchlistResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [activeTab, setActiveTab] = useState<WatchlistStatus | "ALL">("ALL");
  const [error, setError] = useState<unknown>(null);
  const [pendingItemId, setPendingItemId] = useState<number | null>(null);
  const [feedback, setFeedback] = useState<FeedbackState | null>(null);
  const router = useRouter();

  const loadWatchlist = async () => {
    setLoading(true);
    setError(null);

    try {
      const data = await getWatchlist();
      setItems(data);
    } catch (loadError) {
      setItems([]);
      setError(loadError);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void loadWatchlist();
  }, []);

  useEffect(() => {
    if (!feedback) return;
    const id = window.setTimeout(() => setFeedback(null), 4200);
    return () => window.clearTimeout(id);
  }, [feedback]);

  const filtered = useMemo(
    () => (activeTab === "ALL" ? items : items.filter((item) => item.status === activeTab)),
    [activeTab, items]
  );

  const handleStatusChange = async (id: number, status: WatchlistStatus) => {
    setPendingItemId(id);

    try {
      const updated = await updateWatchlistStatus(id, status);
      setItems((current) => current.map((item) => (item.id === id ? updated : item)));
      setFeedback({
        tone: "success",
        title: "Watchlist updated",
        message: `This movie is now marked as ${STATUS_OPTIONS.find((option) => option.value === status)?.label?.toLowerCase()}.`,
      });
    } catch (updateError) {
      setFeedback({
        tone: "error",
        title: "Status update failed",
        message: getErrorMessage(updateError, "The watchlist status could not be updated."),
      });
    } finally {
      setPendingItemId(null);
    }
  };

  const handleRemove = async (id: number) => {
    setPendingItemId(id);

    try {
      await removeFromWatchlist(id);
      setItems((current) => current.filter((item) => item.id !== id));
      setFeedback({
        tone: "success",
        title: "Removed from watchlist",
        message: "The movie has been removed from your saved titles.",
      });
    } catch (removeError) {
      setFeedback({
        tone: "error",
        title: "Remove failed",
        message: getErrorMessage(removeError, "The movie could not be removed from your watchlist."),
      });
    } finally {
      setPendingItemId(null);
    }
  };

  const formatDate = (iso: string) =>
    new Date(iso).toLocaleDateString("en-US", {
      year: "numeric",
      month: "short",
      day: "numeric",
    });

  if (loading) {
    return <WatchlistSkeleton />;
  }

  if (error) {
    const copy = getWatchlistErrorCopy(error);
    return (
      <div className="app-page">
        <StatusPanel
          title={copy.title}
          description={copy.description}
          tone="error"
          actionLabel={copy.actionLabel}
          onAction={() => {
            if (copy.action === "retry") {
              void loadWatchlist();
            } else {
              router.push(copy.action);
            }
          }}
          secondaryLabel="Browse homepage"
          onSecondaryAction={() => router.push("/homepage")}
        />
      </div>
    );
  }

  return (
    <div className="app-page space-y-8">
      <div className="flex flex-col gap-4 lg:flex-row lg:items-end lg:justify-between">
        <div>
          <p className="text-xs uppercase tracking-[0.26em] text-amber-300/85">Watchlist</p>
          <h1 className="app-section-title mt-2">Your saved movies</h1>
          <p className="app-copy-muted mt-2 text-sm">
            Track what you plan to watch, what you are currently watching, and what you have already finished.
          </p>
        </div>

        <div className="rounded-[1.2rem] border border-slate-700/35 bg-white/5 px-4 py-3 text-sm text-slate-300">
          {items.length} movie{items.length === 1 ? "" : "s"} saved
        </div>
      </div>

      {feedback && (
        <FeedbackBanner
          tone={feedback.tone}
          title={feedback.title}
          message={feedback.message}
          onDismiss={() => setFeedback(null)}
        />
      )}

      <div className="flex flex-wrap gap-3">
        {TABS.map((tab) => {
          const count =
            tab.value === "ALL"
              ? items.length
              : items.filter((item) => item.status === tab.value).length;

          return (
            <button
              key={tab.value}
              type="button"
              onClick={() => setActiveTab(tab.value)}
              className={`rounded-full px-4 py-2.5 text-sm font-semibold transition ${
                activeTab === tab.value
                  ? "bg-amber-400/14 text-amber-100"
                  : "border border-slate-700/35 bg-white/4 text-slate-300 hover:bg-white/8 hover:text-white"
              }`}
            >
              {tab.label}
              <span className="ml-1.5 text-slate-400">({count})</span>
            </button>
          );
        })}
      </div>

      {items.length === 0 ? (
        <StatusPanel
          title="Your watchlist is empty"
          description="Start adding movies from the homepage or search results so you can track what to watch next."
          actionLabel="Discover movies"
          onAction={() => router.push("/homepage")}
          secondaryLabel="Search titles"
          onSecondaryAction={() => router.push("/search")}
        />
      ) : filtered.length === 0 ? (
        <StatusPanel
          title="Nothing in this section yet"
          description={`You do have saved movies, but none of them are marked as ${TABS.find((tab) => tab.value === activeTab)?.label.toLowerCase()}.`}
          actionLabel="Show all"
          onAction={() => setActiveTab("ALL")}
        />
      ) : (
        <div className="space-y-4">
          {filtered.map((item) => {
            const isPending = pendingItemId === item.id;

            return (
              <article
                key={item.id}
                className="app-surface app-card grid gap-5 p-4 sm:grid-cols-[110px,1fr] sm:p-5"
              >
                <button
                  type="button"
                  onClick={() => router.push(`/movie/${item.tmdbId}`)}
                  className="mx-auto w-[110px] sm:mx-0"
                >
                  <RemoteImage
                    src={posterUrl(item.posterPath, "w185")}
                    fallbackSrc={POSTER_PLACEHOLDER}
                    alt={item.movieTitle}
                    className="aspect-[2/3] w-full rounded-[1rem] object-cover"
                    loading="lazy"
                  />
                </button>

                <div className="min-w-0 space-y-4">
                  <div className="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between">
                    <div>
                      <button
                        type="button"
                        onClick={() => router.push(`/movie/${item.tmdbId}`)}
                        className="text-left"
                      >
                        <h2 className="text-lg font-semibold text-white transition hover:text-amber-200">
                          {item.movieTitle}
                        </h2>
                      </button>
                      <p className="mt-2 text-sm text-slate-400">
                        Added on {formatDate(item.addedAt)}
                      </p>
                    </div>

                    <span className={`app-pill ${STATUS_COLORS[item.status]}`}>
                      {STATUS_OPTIONS.find((statusOption) => statusOption.value === item.status)?.label}
                    </span>
                  </div>

                  <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                    <select
                      value={item.status}
                      onChange={(event) =>
                        void handleStatusChange(item.id, event.target.value as WatchlistStatus)
                      }
                      disabled={isPending}
                      className="field-select max-w-xs"
                    >
                      {STATUS_OPTIONS.map((option) => (
                        <option key={option.value} value={option.value} className="bg-slate-900">
                          {option.label}
                        </option>
                      ))}
                    </select>

                    <div className="flex flex-wrap gap-3">
                      <button
                        type="button"
                        onClick={() => router.push(`/movie/${item.tmdbId}`)}
                        className="btn-secondary"
                      >
                        Open movie
                      </button>
                      <button
                        type="button"
                        onClick={() => void handleRemove(item.id)}
                        disabled={isPending}
                        className="btn-danger"
                      >
                        Remove
                      </button>
                    </div>
                  </div>
                </div>
              </article>
            );
          })}
        </div>
      )}
    </div>
  );
}
