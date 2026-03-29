"use client";

import { useState } from "react";

interface Props {
  value: number;
  max?: number;
  onChange?: (rating: number) => void;
  size?: "sm" | "md" | "lg";
}

const SIZES = { sm: "w-4 h-4", md: "w-5 h-5", lg: "w-6 h-6" };

export default function StarRating({ value, max = 5, onChange, size = "md" }: Props) {
  const [hover, setHover] = useState(0);
  const interactive = !!onChange;
  const display = hover || value;

  return (
    <div className="flex items-center gap-0.5">
      {Array.from({ length: max }, (_, i) => {
        const star = i + 1;
        const filled = star <= display;

        return (
          <button
            key={i}
            type="button"
            disabled={!interactive}
            onClick={() => onChange?.(star)}
            onMouseEnter={() => interactive && setHover(star)}
            onMouseLeave={() => interactive && setHover(0)}
            className={`${interactive ? "cursor-pointer" : "cursor-default"} transition-colors`}
          >
            <svg
              className={`${SIZES[size]} ${filled ? "text-amber-500" : "text-gray-600"}`}
              fill="currentColor"
              viewBox="0 0 20 20"
            >
              <path d="M9.049 2.927c.3-.921 1.603-.921 1.902 0l1.07 3.292a1 1 0 00.95.69h3.462c.969 0 1.371 1.24.588 1.81l-2.8 2.034a1 1 0 00-.364 1.118l1.07 3.292c.3.921-.755 1.688-1.54 1.118l-2.8-2.034a1 1 0 00-1.175 0l-2.8 2.034c-.784.57-1.838-.197-1.539-1.118l1.07-3.292a1 1 0 00-.364-1.118L2.98 8.72c-.783-.57-.38-1.81.588-1.81h3.461a1 1 0 00.951-.69l1.07-3.292z" />
            </svg>
          </button>
        );
      })}
    </div>
  );
}
