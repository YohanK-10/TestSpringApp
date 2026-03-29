"use client";

import { useRouter } from "next/navigation";
import RemoteImage from "@/components/RemoteImage";
import { POSTER_PLACEHOLDER, posterUrl } from "@/lib/types";

interface Props {
  tmdbId: number;
  title: string;
  posterPath: string | null;
  rating?: number;
  releaseDate?: string;
}

export default function MovieCard({
  tmdbId,
  title,
  posterPath,
  rating,
  releaseDate,
}: Props) {
  const router = useRouter();
  const year = releaseDate?.split("-")[0];

  return (
    <button
      type="button"
      onClick={() => router.push(`/movie/${tmdbId}`)}
      className="group h-full text-left"
    >
      <div className="app-surface app-card h-full overflow-hidden border border-slate-700/40 transition duration-300 hover:-translate-y-1 hover:border-amber-400/30 hover:shadow-[0_24px_70px_rgba(0,0,0,0.45)]">
        <div className="relative aspect-[2/3] overflow-hidden rounded-b-none rounded-t-[1.25rem] bg-slate-900">
          <RemoteImage
            src={posterUrl(posterPath, "w342")}
            fallbackSrc={POSTER_PLACEHOLDER}
            alt={title}
            className="h-full w-full object-cover transition duration-500 group-hover:scale-105"
            loading="lazy"
          />

          <div className="absolute inset-x-0 bottom-0 h-28 bg-gradient-to-t from-black via-black/45 to-transparent" />

          <div className="absolute inset-x-0 bottom-0 flex items-end justify-between gap-2 p-3">
            {rating !== undefined && rating > 0 ? (
              <div className="app-pill border-amber-400/20 bg-black/40 text-amber-100 backdrop-blur-md">
                <svg className="h-4 w-4 text-amber-400" fill="currentColor" viewBox="0 0 20 20">
                  <path d="M9.049 2.927c.3-.921 1.603-.921 1.902 0l1.07 3.292a1 1 0 00.95.69h3.462c.969 0 1.371 1.24.588 1.81l-2.8 2.034a1 1 0 00-.364 1.118l1.07 3.292c.3.921-.755 1.688-1.54 1.118l-2.8-2.034a1 1 0 00-1.175 0l-2.8 2.034c-.784.57-1.838-.197-1.539-1.118l1.07-3.292a1 1 0 00-.364-1.118L2.98 8.72c-.783-.57-.38-1.81.588-1.81h3.461a1 1 0 00.951-.69l1.07-3.292z" />
                </svg>
                <span>{rating.toFixed(1)}</span>
              </div>
            ) : (
              <div className="app-pill border-transparent bg-black/35 text-slate-300">Unrated</div>
            )}

            {year && (
              <div className="rounded-full bg-black/38 px-3 py-1 text-xs font-medium text-slate-100 backdrop-blur-md">
                {year}
              </div>
            )}
          </div>
        </div>

        <div className="space-y-2 px-4 py-4">
          <p className="line-clamp-2 text-sm font-semibold text-white transition group-hover:text-amber-200 sm:text-base">
            {title}
          </p>
          <p className="text-xs uppercase tracking-[0.22em] text-slate-500">Movie details</p>
        </div>
      </div>
    </button>
  );
}
