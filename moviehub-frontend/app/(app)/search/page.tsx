"use client";

import { Suspense, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import MovieCard from "@/components/MovieCard";
import StatusPanel from "@/components/StatusPanel";
import { SearchSkeleton } from "@/components/Skeletons";
import { ApiError, searchMovies } from "@/lib/api";
import type { TmdbMovie } from "@/lib/types";

export default function SearchPage() {
  return (
    <Suspense fallback={<SearchSkeleton />}>
      <SearchContent />
    </Suspense>
  );
}

function getSearchErrorCopy(error: unknown) {
  if (error instanceof ApiError && error.kind === "network") {
    return {
      title: "Search can't reach the backend",
      description:
        "AtlasWatch could not connect to the API, so search results never came back. Start the backend and try the query again.",
    };
  }

  if (error instanceof ApiError && error.status && error.status >= 500) {
    return {
      title: "Search is temporarily unavailable",
      description:
        "The backend responded with an internal error while loading search results. Give it another try in a moment.",
    };
  }

  return {
    title: "We couldn't load search results",
    description:
      "The request did not complete successfully. Try again or adjust the query.",
  };
}

function SearchContent() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const query = (searchParams.get("q") ?? "").trim();

  const [results, setResults] = useState<TmdbMovie[]>([]);
  const [page, setPage] = useState(1);
  const [totalPages, setTotalPages] = useState(1);
  const [totalResults, setTotalResults] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [retryToken, setRetryToken] = useState(0);

  useEffect(() => {
    setPage(1);
  }, [query]);

  useEffect(() => {
    if (!query) {
      setResults([]);
      setTotalPages(1);
      setTotalResults(0);
      setError(null);
      setLoading(false);
      return;
    }

    let active = true;
    setLoading(true);
    setError(null);

    void searchMovies(query, page)
      .then((data) => {
        if (!active) return;
        setResults(data.results ?? []);
        setTotalPages(Math.max(1, data.total_pages ?? 1));
        setTotalResults(data.total_results ?? data.results?.length ?? 0);
      })
      .catch((loadError) => {
        if (!active) return;
        setResults([]);
        setTotalPages(1);
        setTotalResults(0);
        setError(loadError);
      })
      .finally(() => {
        if (active) {
          setLoading(false);
        }
      });

    return () => {
      active = false;
    };
  }, [page, query, retryToken]);

  if (!query) {
    return (
      <div className="app-page">
        <StatusPanel
          title="Start with a movie title"
          description="Use the search bar in the navigation to look up a movie, actor, or title fragment. Results from TMDB will appear here."
          actionLabel="Browse homepage"
          onAction={() => router.push("/homepage")}
          secondaryLabel="Open watchlist"
          onSecondaryAction={() => router.push("/watchlist")}
        />
      </div>
    );
  }

  if (loading) {
    return <SearchSkeleton />;
  }

  return (
    <div className="app-page space-y-8">
      <div className="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <p className="text-xs uppercase tracking-[0.26em] text-amber-300/85">Search</p>
          <h1 className="app-section-title mt-2">
            Results for &ldquo;{query}&rdquo;
          </h1>
          <p className="app-copy-muted mt-2 text-sm">
            {loading
              ? "Searching TMDB and your cached movie catalog..."
              : `${totalResults} result${totalResults === 1 ? "" : "s"} found`}
          </p>
        </div>

        {!loading && results.length > 0 && (
          <div className="rounded-full border border-slate-700/40 bg-white/5 px-4 py-2 text-sm text-slate-300">
            Page {page} of {totalPages}
          </div>
        )}
      </div>

      {error ? (
        <StatusPanel
          {...getSearchErrorCopy(error)}
          tone="error"
          actionLabel="Retry search"
          onAction={() => setRetryToken((current) => current + 1)}
          secondaryLabel="Browse homepage"
          onSecondaryAction={() => router.push("/homepage")}
        />
      ) : results.length === 0 ? (
        <StatusPanel
          title={`No results for "${query}"`}
          description="TMDB didn't return any movies for that search. Try a broader title, a different spelling, or another release."
          actionLabel="Search something else"
          onAction={() => router.push("/homepage")}
          secondaryLabel="Open watchlist"
          onSecondaryAction={() => router.push("/watchlist")}
        />
      ) : (
        <>
          <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5">
            {results.map((movie) => (
              <MovieCard
                key={movie.id}
                tmdbId={movie.id}
                title={movie.title}
                posterPath={movie.poster_path}
                rating={movie.vote_average}
                releaseDate={movie.release_date}
              />
            ))}
          </div>

          {totalPages > 1 && (
            <div className="app-surface app-card flex flex-col gap-4 rounded-[1.5rem] p-4 sm:flex-row sm:items-center sm:justify-between">
              <div>
                <p className="text-sm font-semibold text-white">Keep exploring</p>
                <p className="mt-1 text-sm text-slate-400">
                  Move through the result pages without losing your current search query.
                </p>
              </div>

              <div className="flex items-center gap-3">
                <button
                  type="button"
                  onClick={() => setPage((current) => Math.max(1, current - 1))}
                  disabled={page === 1}
                  className="btn-secondary"
                >
                  Previous
                </button>
                <span className="text-sm text-slate-300">
                  Page {page} of {totalPages}
                </span>
                <button
                  type="button"
                  onClick={() => setPage((current) => Math.min(totalPages, current + 1))}
                  disabled={page === totalPages}
                  className="btn-secondary"
                >
                  Next
                </button>
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
}
