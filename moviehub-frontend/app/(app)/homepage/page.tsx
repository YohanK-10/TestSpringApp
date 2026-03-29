"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import MovieCard from "@/components/MovieCard";
import RemoteImage from "@/components/RemoteImage";
import StatusPanel from "@/components/StatusPanel";
import { HomePageSkeleton } from "@/components/Skeletons";
import { ApiError, getTrending } from "@/lib/api";
import { BACKDROP_PLACEHOLDER, backdropUrl, type TmdbMovie } from "@/lib/types";

function getHomepageErrorCopy(error: unknown) {
  if (error instanceof ApiError && error.kind === "network") {
    return {
      title: "AtlasWatch can't reach the API right now",
      description:
        "Trending movies are loaded from the backend service, and that service looks unreachable at the moment. Make sure the backend is running and try again.",
    };
  }

  if (error instanceof ApiError && error.status && error.status >= 500) {
    return {
      title: "Trending movies are temporarily unavailable",
      description:
        "The backend responded, but it hit an internal error while loading the trending feed. Try again in a moment.",
    };
  }

  return {
    title: "We couldn't load the homepage",
    description:
      "Something went wrong while loading the trending feed. Refresh the page or try again.",
  };
}

export default function HomePage() {
  const [trending, setTrending] = useState<TmdbMovie[]>([]);
  const [heroIdx, setHeroIdx] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);
  const router = useRouter();

  const loadTrending = async () => {
    setLoading(true);
    setError(null);

    try {
      const data = await getTrending();
      setTrending(data.results ?? []);
      setHeroIdx(0);
    } catch (loadError) {
      setTrending([]);
      setError(loadError);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void loadTrending();
  }, []);

  const heroMovies = trending.slice(0, 5);

  useEffect(() => {
    if (heroMovies.length === 0) return;

    const id = window.setInterval(() => {
      setHeroIdx((current) => (current + 1) % heroMovies.length);
    }, 8000);

    return () => window.clearInterval(id);
  }, [heroMovies.length]);

  const hero = heroMovies[heroIdx];

  if (loading) {
    return <HomePageSkeleton />;
  }

  if (error) {
    const copy = getHomepageErrorCopy(error);
    return (
      <div className="app-page">
        <StatusPanel
          title={copy.title}
          description={copy.description}
          tone="error"
          actionLabel="Retry"
          onAction={() => void loadTrending()}
          secondaryLabel="Go to search"
          onSecondaryAction={() => router.push("/search")}
        />
      </div>
    );
  }

  if (trending.length === 0) {
    return (
      <div className="app-page">
        <StatusPanel
          title="No trending movies yet"
          description="The trending feed came back empty. Try again, or use search to find a title directly."
          actionLabel="Try again"
          onAction={() => void loadTrending()}
          secondaryLabel="Search movies"
          onSecondaryAction={() => router.push("/search")}
        />
      </div>
    );
  }

  return (
    <div className="space-y-10 pb-6">
      {hero && (
        <section className="app-page pb-0">
          <div className="app-surface app-card relative overflow-hidden">
            <div className="grid min-h-[430px] gap-8 lg:min-h-[540px] lg:grid-cols-[1.05fr,0.95fr] lg:items-stretch">
              <div className="relative z-10 flex flex-col justify-end px-5 py-6 sm:px-8 sm:py-8 lg:px-10 lg:py-10">
                <div className="app-pill mb-4 w-fit border-amber-400/20 bg-amber-400/10 text-amber-100">
                  Trending today
                </div>
                <h1 className="app-title max-w-3xl">{hero.title}</h1>

                <div className="mt-5 flex flex-wrap items-center gap-3 text-sm text-slate-300">
                  {hero.vote_average > 0 && (
                    <span className="app-pill border-amber-300/18 bg-black/25 text-amber-100">
                      <svg className="h-4 w-4 text-amber-400" fill="currentColor" viewBox="0 0 20 20">
                        <path d="M9.049 2.927c.3-.921 1.603-.921 1.902 0l1.07 3.292a1 1 0 00.95.69h3.462c.969 0 1.371 1.24.588 1.81l-2.8 2.034a1 1 0 00-.364 1.118l1.07 3.292c.3.921-.755 1.688-1.54 1.118l-2.8-2.034a1 1 0 00-1.175 0l-2.8 2.034c-.784.57-1.838-.197-1.539-1.118l1.07-3.292a1 1 0 00-.364-1.118L2.98 8.72c-.783-.57-.38-1.81.588-1.81h3.461a1 1 0 00.951-.69l1.07-3.292z" />
                      </svg>
                      {hero.vote_average.toFixed(1)} rating
                    </span>
                  )}
                  {hero.release_date && (
                    <span className="app-pill bg-black/25">{hero.release_date.split("-")[0]}</span>
                  )}
                  <span className="app-pill bg-black/25">{trending.length} picks loaded</span>
                </div>

                <p className="app-copy-soft mt-6 max-w-2xl text-sm leading-7 sm:text-base">
                  {hero.overview || "Explore one of today's trending releases and jump straight into the details, reviews, and your watchlist."}
                </p>

                <div className="mt-7 flex flex-wrap gap-3">
                  <button
                    type="button"
                    onClick={() => router.push(`/movie/${hero.id}`)}
                    className="btn-primary"
                  >
                    View details
                  </button>
                  <button
                    type="button"
                    onClick={() => router.push(`/search?q=${encodeURIComponent(hero.title)}`)}
                    className="btn-secondary"
                  >
                    Find similar titles
                  </button>
                </div>

                <div className="mt-8 flex flex-wrap gap-2">
                  {heroMovies.map((movie, index) => (
                    <button
                      key={movie.id}
                      type="button"
                      onClick={() => setHeroIdx(index)}
                      className={`rounded-full px-3 py-1.5 text-xs font-semibold transition ${
                        index === heroIdx
                          ? "bg-amber-400/16 text-amber-100"
                          : "bg-white/6 text-slate-400 hover:bg-white/10 hover:text-white"
                      }`}
                    >
                      {movie.title}
                    </button>
                  ))}
                </div>
              </div>

              <div className="relative min-h-[320px] self-stretch overflow-hidden rounded-[1.6rem] border border-white/8 bg-slate-950/50 lg:min-h-[500px]">
                <RemoteImage
                  src={backdropUrl(hero.backdrop_path)}
                  fallbackSrc={BACKDROP_PLACEHOLDER}
                  alt={`${hero.title} backdrop`}
                  className="absolute inset-0 h-full w-full object-cover"
                />
                <div className="absolute inset-0 bg-gradient-to-t from-slate-950 via-slate-950/18 to-transparent lg:bg-gradient-to-l lg:from-transparent lg:via-black/5 lg:to-slate-950/65" />
                <div className="absolute bottom-0 left-0 right-0 flex items-center justify-between gap-3 p-5 sm:p-7">
                  <div className="rounded-[1.2rem] border border-white/10 bg-black/45 px-4 py-3 backdrop-blur-md">
                    <p className="text-[0.68rem] uppercase tracking-[0.22em] text-slate-400">Featured pick</p>
                    <p className="mt-1 text-base font-semibold text-white">{hero.title}</p>
                  </div>
                  <div className="hidden rounded-[1.2rem] border border-white/10 bg-black/45 px-4 py-3 text-right backdrop-blur-md sm:block">
                    <p className="text-[0.68rem] uppercase tracking-[0.22em] text-slate-400">AtlasWatch</p>
                    <p className="mt-1 text-sm text-slate-200">Search, review, and track your next watch.</p>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </section>
      )}

      <section className="app-page">
        <div className="mb-6 flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
          <div>
            <h2 className="app-section-title">Trending today</h2>
            <p className="app-copy-muted mt-2 max-w-2xl text-sm">
              Fresh titles from TMDB, ready to open, review, and add to your watchlist.
            </p>
          </div>
          <button type="button" onClick={() => void loadTrending()} className="btn-secondary w-fit">
            Refresh feed
          </button>
        </div>

        <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5">
          {trending.map((movie) => (
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
      </section>
    </div>
  );
}
