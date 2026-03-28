"use client";

import { useEffect, useRef, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import {
  getMovieDetails,
  getReviewsByMovie,
  createReview,
  deleteReview,
  addToWatchlist,
} from "@/lib/api";
import { backdropUrl, posterUrl } from "@/lib/types";
import type { MovieResponse, ReviewResponse, WatchlistStatus } from "@/lib/types";
import StarRating from "@/components/StarRating";

export default function MovieDetailPage() {
  const { tmdbId } = useParams<{ tmdbId: string }>();
  const router = useRouter();
  const id = Number(tmdbId);
  const loadedMovieIdRef = useRef<number | null>(null);

  const [movie, setMovie] = useState<MovieResponse | null>(null);
  const [reviews, setReviews] = useState<ReviewResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [showSpoilers, setShowSpoilers] = useState<Set<number>>(new Set());

  // Review form
  const [showReviewForm, setShowReviewForm] = useState(false);
  const [reviewRating, setReviewRating] = useState(0);
  const [reviewText, setReviewText] = useState("");
  const [reviewSpoiler, setReviewSpoiler] = useState(false);
  const [reviewError, setReviewError] = useState("");
  const [submitting, setSubmitting] = useState(false);

  // Watchlist
  const [watchlistAdded, setWatchlistAdded] = useState(false);
  const [watchlistLoading, setWatchlistLoading] = useState(false);

  useEffect(() => {
    if (!id) return;
    let active = true;
    setLoading(true);
    Promise.allSettled([getMovieDetails(id), getReviewsByMovie(id)])
      .then(([movieResult, reviewsResult]) => {
        if (!active) return;

        if (movieResult.status === "fulfilled") {
          loadedMovieIdRef.current = id;
          setMovie(movieResult.value);
        } else if (loadedMovieIdRef.current !== id) {
          setMovie(null);
        }

        setReviews(reviewsResult.status === "fulfilled" ? reviewsResult.value : []);
      })
      .finally(() => {
        if (active) {
          setLoading(false);
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
    } catch {
      // may already be in watchlist
      setWatchlistAdded(true);
    } finally {
      setWatchlistLoading(false);
    }
  };

  const handleSubmitReview = async (e: React.FormEvent) => {
    e.preventDefault();
    if (reviewRating === 0) {
      setReviewError("Please select a rating.");
      return;
    }
    setSubmitting(true);
    setReviewError("");
    try {
      const newReview = await createReview({
        tmdbId: id,
        rating: reviewRating,
        reviewText,
        containsSpoilers: reviewSpoiler,
      });
      setReviews((prev) => [newReview, ...prev]);
      setShowReviewForm(false);
      setReviewRating(0);
      setReviewText("");
      setReviewSpoiler(false);
    } catch (err: unknown) {
      setReviewError(err instanceof Error ? err.message : "Failed to submit review");
    } finally {
      setSubmitting(false);
    }
  };

  const handleDeleteReview = async (reviewId: number) => {
    try {
      await deleteReview(reviewId);
      setReviews((prev) => prev.filter((r) => r.id !== reviewId));
    } catch {
      // ignore
    }
  };

  const toggleSpoiler = (id: number) => {
    setShowSpoilers((prev) => {
      const next = new Set(prev);
      next.has(id) ? next.delete(id) : next.add(id);
      return next;
    });
  };

  const formatDate = (iso: string) =>
    new Date(iso).toLocaleDateString("en-US", { year: "numeric", month: "short", day: "numeric" });

  if (loading) {
    return (
      <div className="flex items-center justify-center h-[60vh]">
        <div className="w-8 h-8 border-2 border-amber-500 border-t-transparent rounded-full animate-spin" />
      </div>
    );
  }

  if (!movie) {
    return (
      <div className="max-w-7xl mx-auto px-4 py-20 text-center">
        <p className="text-gray-400 text-lg">Movie not found.</p>
        <button onClick={() => router.back()} className="mt-4 text-amber-500 hover:underline">
          Go back
        </button>
      </div>
    );
  }

  const hours = movie.runtime ? Math.floor(movie.runtime / 60) : 0;
  const mins = movie.runtime ? movie.runtime % 60 : 0;

  return (
    <>
      {/* ── Backdrop Hero ───────────────────────────────────── */}
      <section className="relative h-[50vh] min-h-[340px] overflow-hidden">
        {movie.backdropPath ? (
          <img
            src={backdropUrl(movie.backdropPath)}
            alt=""
            className="w-full h-full object-cover"
          />
        ) : (
          <div className="w-full h-full bg-gray-900" />
        )}
        <div className="absolute inset-0 bg-gradient-to-t from-black via-black/60 to-transparent" />
        <div className="absolute inset-0 bg-gradient-to-r from-black/70 via-transparent to-transparent" />
      </section>

      {/* ── Movie Info ──────────────────────────────────────── */}
      <section className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 -mt-40 relative z-10">
        <div className="flex flex-col md:flex-row gap-8">
          {/* Poster */}
          <div className="shrink-0 w-48 sm:w-56">
            <img
              src={posterUrl(movie.posterPath)}
              alt={movie.movieTitle}
              className="w-full rounded-lg shadow-2xl"
            />
          </div>

          {/* Details */}
          <div className="flex-1 pt-4">
            <h1 className="text-3xl sm:text-4xl font-bold mb-3">{movie.movieTitle}</h1>

            <div className="flex flex-wrap items-center gap-3 text-sm text-gray-400 mb-4">
              {movie.rating > 0 && (
                <span className="flex items-center gap-1 text-amber-500 font-medium">
                  <svg className="w-4 h-4" fill="currentColor" viewBox="0 0 20 20">
                    <path d="M9.049 2.927c.3-.921 1.603-.921 1.902 0l1.07 3.292a1 1 0 00.95.69h3.462c.969 0 1.371 1.24.588 1.81l-2.8 2.034a1 1 0 00-.364 1.118l1.07 3.292c.3.921-.755 1.688-1.54 1.118l-2.8-2.034a1 1 0 00-1.175 0l-2.8 2.034c-.784.57-1.838-.197-1.539-1.118l1.07-3.292a1 1 0 00-.364-1.118L2.98 8.72c-.783-.57-.38-1.81.588-1.81h3.461a1 1 0 00.951-.69l1.07-3.292z" />
                  </svg>
                  {movie.rating.toFixed(1)}
                </span>
              )}
              {movie.releaseDate && <span>{movie.releaseDate.split("-")[0]}</span>}
              {movie.runtime && movie.runtime > 0 && (
                <span>{hours > 0 ? `${hours}h ${mins}m` : `${mins}m`}</span>
              )}
            </div>

            {/* Genres */}
            {movie.genres.length > 0 && (
              <div className="flex flex-wrap gap-2 mb-6">
                {movie.genres.map((g) => (
                  <span
                    key={g}
                    className="px-3 py-1 rounded-full bg-white/10 text-xs text-gray-300"
                  >
                    {g}
                  </span>
                ))}
              </div>
            )}

            {/* Overview */}
            <p className="text-gray-300 leading-relaxed mb-8 max-w-3xl">
              {movie.movieOverview}
            </p>

            {/* Action buttons */}
            <div className="flex flex-wrap gap-3">
              {!watchlistAdded ? (
                <>
                  <button
                    onClick={() => handleAddToWatchlist("PLAN_TO_WATCH")}
                    disabled={watchlistLoading}
                    className="flex items-center gap-2 px-5 py-2.5 bg-amber-500 hover:bg-amber-600 text-black font-semibold rounded-lg transition disabled:opacity-50"
                  >
                    <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                      <path strokeLinecap="round" strokeLinejoin="round" d="M12 4v16m8-8H4" />
                    </svg>
                    Add to Watchlist
                  </button>
                  <button
                    onClick={() => handleAddToWatchlist("WATCHED")}
                    disabled={watchlistLoading}
                    className="flex items-center gap-2 px-5 py-2.5 bg-white/10 hover:bg-white/20 rounded-lg transition disabled:opacity-50"
                  >
                    <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                      <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
                    </svg>
                    Mark as Watched
                  </button>
                </>
              ) : (
                <span className="flex items-center gap-2 px-5 py-2.5 bg-emerald-500/20 text-emerald-400 rounded-lg text-sm font-medium">
                  <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                    <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
                  </svg>
                  Added to Watchlist
                </span>
              )}
              <button
                onClick={() => setShowReviewForm(!showReviewForm)}
                className="flex items-center gap-2 px-5 py-2.5 bg-white/10 hover:bg-white/20 rounded-lg transition"
              >
                <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                  <path strokeLinecap="round" strokeLinejoin="round" d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z" />
                </svg>
                Write a Review
              </button>
            </div>
          </div>
        </div>
      </section>

      {/* ── Review Form ─────────────────────────────────────── */}
      {showReviewForm && (
        <section className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 mt-10">
          <form
            onSubmit={handleSubmitReview}
            className="bg-white/5 border border-white/10 rounded-xl p-6 max-w-2xl"
          >
            <h3 className="text-lg font-semibold mb-4">Your Review</h3>

            <div className="mb-4">
              <label className="block text-sm text-gray-400 mb-2">Rating</label>
              <StarRating value={reviewRating} max={10} onChange={setReviewRating} size="lg" />
              {reviewRating > 0 && (
                <span className="text-sm text-gray-400 ml-2">{reviewRating}/10</span>
              )}
            </div>

            <div className="mb-4">
              <label className="block text-sm text-gray-400 mb-2">Review</label>
              <textarea
                value={reviewText}
                onChange={(e) => setReviewText(e.target.value)}
                rows={4}
                placeholder="What did you think of this movie?"
                className="w-full rounded-lg bg-white/5 border border-white/10 px-4 py-3 text-sm text-white placeholder-gray-500 focus:outline-none focus:ring-2 focus:ring-amber-500/50 resize-none"
              />
            </div>

            <label className="flex items-center gap-2 mb-6 cursor-pointer">
              <input
                type="checkbox"
                checked={reviewSpoiler}
                onChange={(e) => setReviewSpoiler(e.target.checked)}
                className="w-4 h-4 rounded bg-white/10 border-white/20 text-amber-500 focus:ring-amber-500"
              />
              <span className="text-sm text-gray-400">Contains spoilers</span>
            </label>

            {reviewError && <p className="text-red-500 text-sm mb-4">{reviewError}</p>}

            <div className="flex gap-3">
              <button
                type="submit"
                disabled={submitting}
                className="px-5 py-2.5 bg-amber-500 hover:bg-amber-600 text-black font-semibold rounded-lg transition disabled:opacity-50"
              >
                {submitting ? "Submitting..." : "Submit Review"}
              </button>
              <button
                type="button"
                onClick={() => setShowReviewForm(false)}
                className="px-5 py-2.5 bg-white/10 hover:bg-white/20 rounded-lg transition"
              >
                Cancel
              </button>
            </div>
          </form>
        </section>
      )}

      {/* ── Reviews List ────────────────────────────────────── */}
      <section className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-12">
        <h2 className="text-xl font-bold mb-6">
          Reviews {reviews.length > 0 && <span className="text-gray-500 font-normal">({reviews.length})</span>}
        </h2>

        {reviews.length === 0 ? (
          <p className="text-gray-500">No reviews yet. Be the first to review this movie!</p>
        ) : (
          <div className="space-y-6 max-w-3xl">
            {reviews.map((review) => {
              const spoilerHidden = review.containsSpoilers && !showSpoilers.has(review.id);

              return (
                <div
                  key={review.id}
                  className="bg-white/5 border border-white/10 rounded-xl p-5"
                >
                  <div className="flex items-center justify-between mb-3">
                    <div className="flex items-center gap-3">
                      {/* Avatar placeholder */}
                      <div className="w-8 h-8 rounded-full bg-gradient-to-br from-amber-500 to-orange-600 flex items-center justify-center text-xs font-bold text-black">
                        {review.username[0].toUpperCase()}
                      </div>
                      <div>
                        <span className="font-medium text-sm">{review.username}</span>
                        <span className="text-gray-500 text-xs ml-2">
                          {formatDate(review.createdAt)}
                        </span>
                      </div>
                    </div>
                    <div className="flex items-center gap-1 text-amber-500 text-sm font-medium">
                      <svg className="w-4 h-4" fill="currentColor" viewBox="0 0 20 20">
                        <path d="M9.049 2.927c.3-.921 1.603-.921 1.902 0l1.07 3.292a1 1 0 00.95.69h3.462c.969 0 1.371 1.24.588 1.81l-2.8 2.034a1 1 0 00-.364 1.118l1.07 3.292c.3.921-.755 1.688-1.54 1.118l-2.8-2.034a1 1 0 00-1.175 0l-2.8 2.034c-.784.57-1.838-.197-1.539-1.118l1.07-3.292a1 1 0 00-.364-1.118L2.98 8.72c-.783-.57-.38-1.81.588-1.81h3.461a1 1 0 00.951-.69l1.07-3.292z" />
                      </svg>
                      {review.rating}/10
                    </div>
                  </div>

                  {spoilerHidden ? (
                    <button
                      onClick={() => toggleSpoiler(review.id)}
                      className="text-sm text-amber-500 hover:text-amber-400 flex items-center gap-1"
                    >
                      <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                        <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v2m0 4h.01M12 3a9 9 0 100 18 9 9 0 000-18z" />
                      </svg>
                      This review contains spoilers. Click to reveal.
                    </button>
                  ) : (
                    <p className="text-gray-300 text-sm leading-relaxed">{review.reviewText}</p>
                  )}
                </div>
              );
            })}
          </div>
        )}
      </section>
    </>
  );
}
