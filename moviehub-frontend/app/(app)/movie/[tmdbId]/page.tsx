"use client";

import { useEffect, useMemo, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import FeedbackBanner from "@/components/FeedbackBanner";
import RemoteImage from "@/components/RemoteImage";
import StarRating from "@/components/StarRating";
import StatusPanel from "@/components/StatusPanel";
import { MovieDetailsSkeleton } from "@/components/Skeletons";
import {
  addToWatchlist,
  ApiError,
  createReview,
  getErrorMessage,
  getMovieDetails,
  getReviewsByMovie,
} from "@/lib/api";
import {
  BACKDROP_PLACEHOLDER,
  POSTER_PLACEHOLDER,
  backdropUrl,
  posterUrl,
  type MovieResponse,
  type ReviewResponse,
  type WatchlistStatus,
} from "@/lib/types";

type FeedbackTone = "success" | "error" | "info";

interface FeedbackState {
  tone: FeedbackTone;
  title: string;
  message: string;
}

function getMovieErrorCopy(error: unknown) {
  if (error instanceof ApiError && error.kind === "network") {
    return {
      title: "Movie details can't reach the backend",
      description:
        "The AtlasWatch frontend could not connect to your API, so this movie page never finished loading. Make sure the backend is running and try again.",
    };
  }

  if (error instanceof ApiError && error.status === 404) {
    return {
      title: "This movie could not be found",
      description:
        "The details endpoint returned a real not-found response for this TMDB id. The homepage link may be stale, or the movie has not been cached locally yet.",
    };
  }

  if (error instanceof ApiError && error.status && error.status >= 500) {
    return {
      title: "The movie details endpoint failed",
      description:
        "The backend responded with a server error while loading this movie. This is different from a not-found case, and it usually means the backend logs are the next place to inspect.",
    };
  }

  return {
    title: "We couldn't load this movie",
    description:
      "Something unexpected stopped the movie details request from completing. Try again, or head back to the homepage.",
  };
}

function getReviewErrorCopy(error: unknown) {
  if (error instanceof ApiError && error.kind === "network") {
    return "Reviews are unavailable because the frontend could not reach the backend API.";
  }

  if (error instanceof ApiError && (error.status === 401 || error.status === 403)) {
    return "Reviews are unavailable because your session is not authenticated for this action. Sign in again and retry.";
  }

  if (error instanceof ApiError && error.status && error.status >= 500) {
    return "Reviews are temporarily unavailable because the backend returned an internal error.";
  }

  return "Reviews could not be loaded right now.";
}

export default function MovieDetailPage() {
  const { tmdbId } = useParams<{ tmdbId: string }>();
  const router = useRouter();
  const id = Number(tmdbId);

  const [movie, setMovie] = useState<MovieResponse | null>(null);
  const [movieLoading, setMovieLoading] = useState(true);
  const [movieError, setMovieError] = useState<unknown>(null);

  const [reviews, setReviews] = useState<ReviewResponse[]>([]);
  const [reviewsLoading, setReviewsLoading] = useState(true);
  const [reviewsError, setReviewsError] = useState<unknown>(null);
  const [showSpoilers, setShowSpoilers] = useState<Set<number>>(new Set());

  const [showReviewForm, setShowReviewForm] = useState(false);
  const [reviewRating, setReviewRating] = useState(0);
  const [reviewText, setReviewText] = useState("");
  const [reviewSpoiler, setReviewSpoiler] = useState(false);
  const [reviewError, setReviewError] = useState("");
  const [submittingReview, setSubmittingReview] = useState(false);

  const [watchlistAdded, setWatchlistAdded] = useState(false);
  const [watchlistLoading, setWatchlistLoading] = useState(false);
  const [feedback, setFeedback] = useState<FeedbackState | null>(null);

  useEffect(() => {
    if (!feedback) return;

    const id = window.setTimeout(() => setFeedback(null), 4200);
    return () => window.clearTimeout(id);
  }, [feedback]);

  useEffect(() => {
    if (!Number.isFinite(id)) {
      setMovie(null);
      setMovieError(new ApiError("This movie id is invalid.", { kind: "http", status: 404 }));
      setMovieLoading(false);
      setReviews([]);
      setReviewsLoading(false);
      setReviewsError(null);
      return;
    }

    let active = true;
    setMovieLoading(true);
    setMovieError(null);
    setReviewsLoading(true);
    setReviewsError(null);
    setWatchlistAdded(false);
    setFeedback(null);
    setReviewError("");
    setShowSpoilers(new Set());

    void getMovieDetails(id)
      .then((data) => {
        if (!active) return;
        setMovie(data);
      })
      .catch((error) => {
        if (!active) return;
        setMovie(null);
        setMovieError(error);
      })
      .finally(() => {
        if (active) {
          setMovieLoading(false);
        }
      });

    void getReviewsByMovie(id)
      .then((data) => {
        if (!active) return;
        setReviews(data);
      })
      .catch((error) => {
        if (!active) return;
        setReviews([]);
        setReviewsError(error);
      })
      .finally(() => {
        if (active) {
          setReviewsLoading(false);
        }
      });

    return () => {
      active = false;
    };
  }, [id]);

  const handleAddToWatchlist = async (status: WatchlistStatus) => {
    setWatchlistLoading(true);

    try {
      await addToWatchlist({ tmdbId: id, status });
      setWatchlistAdded(true);
      setFeedback({
        tone: "success",
        title: status === "WATCHED" ? "Marked as watched" : "Added to watchlist",
        message:
          status === "WATCHED"
            ? "This movie is now in your watched list."
            : "You can manage the movie later from your watchlist page.",
      });
    } catch (error) {
      const message =
        error instanceof ApiError && (error.status === 401 || error.status === 403)
          ? "Your session is not authenticated for watchlist changes. Sign in again and retry."
          : getErrorMessage(error, "The movie was not added to your watchlist.");

      if (error instanceof ApiError && error.status === 409) {
        setWatchlistAdded(true);
        setFeedback({
          tone: "info",
          title: "Already in your watchlist",
          message: "AtlasWatch already has this movie saved in your watchlist.",
        });
      } else {
        setFeedback({
          tone: "error",
          title: "Couldn't update watchlist",
          message,
        });
      }
    } finally {
      setWatchlistLoading(false);
    }
  };

  const handleSubmitReview = async (event: React.FormEvent) => {
    event.preventDefault();

    if (reviewRating === 0) {
      setReviewError("Select a rating before submitting your review.");
      return;
    }

    setSubmittingReview(true);
    setReviewError("");

    try {
      const newReview = await createReview({
        tmdbId: id,
        rating: reviewRating,
        reviewText: reviewText.trim(),
        containsSpoilers: reviewSpoiler,
      });

      setReviews((current) => [newReview, ...current]);
      setShowReviewForm(false);
      setReviewRating(0);
      setReviewText("");
      setReviewSpoiler(false);
      setFeedback({
        tone: "success",
        title: "Review submitted",
        message: "Your review is now visible on the movie page.",
      });
    } catch (error) {
      const message =
        error instanceof ApiError && (error.status === 401 || error.status === 403)
          ? "Your session is not authenticated for review submission. Sign in again and retry."
          : error instanceof ApiError && error.status === 409
            ? "You've already reviewed this movie. Update or remove the existing review from the backend before creating another one."
            : getErrorMessage(error, "The review could not be submitted.");

      setReviewError(message);
      setFeedback({
        tone: "error",
        title: "Review not submitted",
        message,
      });
    } finally {
      setSubmittingReview(false);
    }
  };

  const toggleSpoiler = (reviewId: number) => {
    setShowSpoilers((current) => {
      const next = new Set(current);
      if (next.has(reviewId)) {
        next.delete(reviewId);
      } else {
        next.add(reviewId);
      }
      return next;
    });
  };

  const formatDate = (value: string) =>
    new Date(value).toLocaleDateString("en-US", {
      year: "numeric",
      month: "short",
      day: "numeric",
    });

  const movieFacts = useMemo(() => {
    if (!movie) return [];

    const hours = movie.runtime ? Math.floor(movie.runtime / 60) : 0;
    const mins = movie.runtime ? movie.runtime % 60 : 0;

    return [
      { label: "Release year", value: movie.releaseDate ? movie.releaseDate.split("-")[0] : "Unknown" },
      { label: "Runtime", value: movie.runtime ? (hours > 0 ? `${hours}h ${mins}m` : `${mins}m`) : "Unknown" },
      { label: "Popularity", value: movie.popularity ? movie.popularity.toFixed(1) : "N/A" },
    ];
  }, [movie]);

  if (movieLoading) {
    return <MovieDetailsSkeleton />;
  }

  if (movieError || !movie) {
    const copy = getMovieErrorCopy(movieError);
    return (
      <div className="app-page">
        <StatusPanel
          title={copy.title}
          description={copy.description}
          tone="error"
          actionLabel="Go back"
          onAction={() => router.back()}
          secondaryLabel="Browse homepage"
          onSecondaryAction={() => router.push("/homepage")}
        />
      </div>
    );
  }

  return (
    <div className="pb-12">
      <section className="relative overflow-hidden border-b border-slate-800/60">
        <RemoteImage
          src={backdropUrl(movie.backdropPath)}
          fallbackSrc={BACKDROP_PLACEHOLDER}
          alt={`${movie.movieTitle} backdrop`}
          className="absolute inset-0 h-full w-full object-cover"
        />
        <div className="absolute inset-0 bg-gradient-to-b from-slate-950/55 via-slate-950/58 to-slate-950" />
        <div className="absolute inset-0 bg-[radial-gradient(circle_at_top_left,rgba(245,158,11,0.18),transparent_28%)]" />

        <div className="app-page relative z-10 pt-8">
          <button type="button" onClick={() => router.back()} className="btn-ghost mb-6 !px-0 text-sm text-slate-300">
            <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
              <path strokeLinecap="round" strokeLinejoin="round" d="M15 19l-7-7 7-7" />
            </svg>
            Back
          </button>

          <div className="grid gap-8 pb-10 md:grid-cols-[220px,1fr] lg:grid-cols-[280px,1fr]">
            <div className="mx-auto w-full max-w-[280px] md:mx-0">
              <div className="app-surface app-card overflow-hidden p-2">
                <RemoteImage
                  src={posterUrl(movie.posterPath)}
                  fallbackSrc={POSTER_PLACEHOLDER}
                  alt={movie.movieTitle}
                  className="aspect-[2/3] w-full rounded-[1.2rem] object-cover"
                />
              </div>
            </div>

            <div className="space-y-6">
              <div>
                <p className="text-xs uppercase tracking-[0.26em] text-amber-300/85">Movie details</p>
                <h1 className="app-title mt-3 max-w-4xl">{movie.movieTitle}</h1>
              </div>

              <div className="flex flex-wrap gap-2">
                {movie.rating > 0 && (
                  <span className="app-pill border-amber-400/20 bg-amber-400/10 text-amber-100">
                    <svg className="h-4 w-4 text-amber-400" fill="currentColor" viewBox="0 0 20 20">
                      <path d="M9.049 2.927c.3-.921 1.603-.921 1.902 0l1.07 3.292a1 1 0 00.95.69h3.462c.969 0 1.371 1.24.588 1.81l-2.8 2.034a1 1 0 00-.364 1.118l1.07 3.292c.3.921-.755 1.688-1.54 1.118l-2.8-2.034a1 1 0 00-1.175 0l-2.8 2.034c-.784.57-1.838-.197-1.539-1.118l1.07-3.292a1 1 0 00-.364-1.118L2.98 8.72c-.783-.57-.38-1.81.588-1.81h3.461a1 1 0 00.951-.69l1.07-3.292z" />
                    </svg>
                    {movie.rating.toFixed(1)} / 10
                  </span>
                )}
                {movieFacts.map((fact) => (
                  <span key={fact.label} className="app-pill">
                    {fact.label}: {fact.value}
                  </span>
                ))}
              </div>

              {movie.genres.length > 0 && (
                <div className="flex flex-wrap gap-2">
                  {movie.genres.map((genre) => (
                    <span
                      key={genre}
                      className="rounded-full border border-cyan-400/15 bg-cyan-400/8 px-3 py-1.5 text-xs font-medium text-cyan-100"
                    >
                      {genre}
                    </span>
                  ))}
                </div>
              )}

              <p className="max-w-3xl text-sm leading-7 text-slate-200 sm:text-base">
                {movie.movieOverview || "No overview was provided for this title."}
              </p>

              {feedback && (
                <FeedbackBanner
                  tone={feedback.tone}
                  title={feedback.title}
                  message={feedback.message}
                  onDismiss={() => setFeedback(null)}
                />
              )}

              <div className="flex flex-wrap gap-3">
                {!watchlistAdded ? (
                  <>
                    <button
                      type="button"
                      onClick={() => void handleAddToWatchlist("PLAN_TO_WATCH")}
                      disabled={watchlistLoading}
                      className="btn-primary"
                    >
                      <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                        <path strokeLinecap="round" strokeLinejoin="round" d="M12 4v16m8-8H4" />
                      </svg>
                      Add to watchlist
                    </button>
                    <button
                      type="button"
                      onClick={() => void handleAddToWatchlist("WATCHED")}
                      disabled={watchlistLoading}
                      className="btn-secondary"
                    >
                      <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                        <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
                      </svg>
                      Mark as watched
                    </button>
                  </>
                ) : (
                  <span className="app-pill border-emerald-400/20 bg-emerald-500/10 px-4 py-2 text-emerald-100">
                    Saved in your watchlist
                  </span>
                )}

                <button
                  type="button"
                  onClick={() => setShowReviewForm((current) => !current)}
                  className="btn-secondary"
                >
                  <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                    <path strokeLinecap="round" strokeLinejoin="round" d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z" />
                  </svg>
                  {showReviewForm ? "Hide review form" : "Write a review"}
                </button>
              </div>
            </div>
          </div>
        </div>
      </section>

      <section className="app-page grid gap-6 xl:grid-cols-[1.35fr,0.95fr]">
        <div className="space-y-6">
          <div className="app-surface app-card p-5 sm:p-6">
            <div className="mb-5 flex flex-col gap-2 sm:flex-row sm:items-end sm:justify-between">
              <div>
                <h2 className="app-section-title text-[1.5rem]">Community reviews</h2>
                <p className="app-copy-muted mt-2 text-sm">
                  {reviewsLoading
                    ? "Loading reactions from other viewers..."
                    : `${reviews.length} review${reviews.length === 1 ? "" : "s"} on this movie`}
                </p>
              </div>
            </div>

            {reviewsLoading ? (
              <div className="space-y-4">
                <div className="skeleton-block h-28 rounded-[1.2rem]" />
                <div className="skeleton-block h-28 rounded-[1.2rem]" />
              </div>
            ) : reviewsError ? (
              <StatusPanel
                compact
                tone="error"
                title="Reviews unavailable"
                description={getReviewErrorCopy(reviewsError)}
                actionLabel="Retry page"
                onAction={() => router.refresh()}
              />
            ) : reviews.length === 0 ? (
              <StatusPanel
                compact
                title="No reviews yet"
                description="This movie has no reviews yet. Be the first person to leave an impression."
                actionLabel="Write the first review"
                onAction={() => setShowReviewForm(true)}
              />
            ) : (
              <div className="space-y-4">
                {reviews.map((review) => {
                  const spoilerHidden =
                    review.containsSpoilers && !showSpoilers.has(review.id);

                  return (
                    <article
                      key={review.id}
                      className="rounded-[1.2rem] border border-slate-700/35 bg-slate-900/55 p-4 sm:p-5"
                    >
                      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
                        <div className="flex items-center gap-3">
                          <div className="flex h-10 w-10 items-center justify-center rounded-full bg-gradient-to-br from-amber-400 to-orange-500 text-sm font-bold text-slate-950">
                            {review.username[0]?.toUpperCase()}
                          </div>
                          <div>
                            <p className="font-semibold text-white">{review.username}</p>
                            <p className="text-xs text-slate-400">{formatDate(review.createdAt)}</p>
                          </div>
                        </div>

                        <div className="app-pill border-amber-400/20 bg-black/25 text-amber-100">
                          <svg className="h-4 w-4 text-amber-400" fill="currentColor" viewBox="0 0 20 20">
                            <path d="M9.049 2.927c.3-.921 1.603-.921 1.902 0l1.07 3.292a1 1 0 00.95.69h3.462c.969 0 1.371 1.24.588 1.81l-2.8 2.034a1 1 0 00-.364 1.118l1.07 3.292c.3.921-.755 1.688-1.54 1.118l-2.8-2.034a1 1 0 00-1.175 0l-2.8 2.034c-.784.57-1.838-.197-1.539-1.118l1.07-3.292a1 1 0 00-.364-1.118L2.98 8.72c-.783-.57-.38-1.81.588-1.81h3.461a1 1 0 00.951-.69l1.07-3.292z" />
                          </svg>
                          {review.rating}/10
                        </div>
                      </div>

                      <div className="mt-4">
                        {spoilerHidden ? (
                          <button
                            type="button"
                            onClick={() => toggleSpoiler(review.id)}
                            className="btn-ghost !px-0 text-sm text-amber-200"
                          >
                            Reveal spoiler review
                          </button>
                        ) : (
                          <p className="text-sm leading-7 text-slate-200">
                            {review.reviewText || "No written review was provided."}
                          </p>
                        )}
                      </div>
                    </article>
                  );
                })}
              </div>
            )}
          </div>
        </div>

        <aside className="space-y-6">
          <div className="app-surface app-card p-5 sm:p-6">
            <h2 className="text-xl font-semibold text-white">Your take</h2>
            <p className="app-copy-muted mt-2 text-sm">
              Leave a rating, write a quick reaction, and optionally mark spoilers before posting.
            </p>

            {!showReviewForm ? (
              <div className="mt-5 space-y-4 rounded-[1.2rem] border border-slate-700/35 bg-white/4 p-4">
                <p className="text-sm leading-7 text-slate-300">
                  Open the review form when you are ready. Your rating and notes stay on this page until you submit.
                </p>
                <button type="button" onClick={() => setShowReviewForm(true)} className="btn-primary">
                  Start a review
                </button>
              </div>
            ) : (
              <form onSubmit={handleSubmitReview} className="mt-5 space-y-4">
                <div>
                  <label className="mb-2 block text-sm font-medium text-slate-300">Rating</label>
                  <div className="flex flex-wrap items-center gap-3">
                    <StarRating value={reviewRating} max={10} onChange={setReviewRating} size="lg" />
                    <span className="text-sm text-slate-400">
                      {reviewRating > 0 ? `${reviewRating}/10` : "Tap a star to rate"}
                    </span>
                  </div>
                </div>

                <div>
                  <label className="mb-2 block text-sm font-medium text-slate-300">Review</label>
                  <textarea
                    value={reviewText}
                    onChange={(event) => setReviewText(event.target.value)}
                    rows={5}
                    placeholder="What worked, what didn't, and would you recommend it?"
                    className="field-textarea"
                  />
                </div>

                <label className="flex items-center gap-3 rounded-[1rem] border border-slate-700/35 bg-white/3 px-4 py-3 text-sm text-slate-300">
                  <input
                    type="checkbox"
                    checked={reviewSpoiler}
                    onChange={(event) => setReviewSpoiler(event.target.checked)}
                    className="h-4 w-4 rounded border-slate-500 bg-slate-900 text-amber-500 focus:ring-amber-400"
                  />
                  Mark this review as containing spoilers
                </label>

                {reviewError && (
                  <p className="rounded-[1rem] border border-rose-400/18 bg-rose-500/8 px-4 py-3 text-sm text-rose-200">
                    {reviewError}
                  </p>
                )}

                <div className="flex flex-wrap gap-3">
                  <button
                    type="submit"
                    disabled={submittingReview}
                    className="btn-primary"
                  >
                    {submittingReview ? "Submitting..." : "Submit review"}
                  </button>
                  <button
                    type="button"
                    onClick={() => {
                      setShowReviewForm(false);
                      setReviewError("");
                    }}
                    className="btn-secondary"
                  >
                    Hide form
                  </button>
                </div>
              </form>
            )}
          </div>

          <div className="app-surface app-card p-5 sm:p-6">
            <h2 className="text-xl font-semibold text-white">Quick facts</h2>
            <div className="mt-5 space-y-3">
              {movieFacts.map((fact) => (
                <div
                  key={fact.label}
                  className="flex items-center justify-between rounded-[1rem] border border-slate-700/35 bg-white/4 px-4 py-3"
                >
                  <span className="text-sm text-slate-400">{fact.label}</span>
                  <span className="text-sm font-semibold text-white">{fact.value}</span>
                </div>
              ))}
            </div>
          </div>
        </aside>
      </section>
    </div>
  );
}
