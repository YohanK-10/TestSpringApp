"use client";

interface Props {
  title: string;
  message: string;
  tone?: "success" | "error" | "info";
  onDismiss?: () => void;
}

export default function FeedbackBanner({
  title,
  message,
  tone = "info",
  onDismiss,
}: Props) {
  return (
    <div data-tone={tone} className="feedback-banner">
      <div className="flex items-start justify-between gap-4">
        <div>
          <p className="text-sm font-semibold text-white">{title}</p>
          <p className="mt-1 text-sm text-slate-300">{message}</p>
        </div>
        {onDismiss && (
          <button
            type="button"
            onClick={onDismiss}
            className="btn-ghost !rounded-full !px-2.5 !py-2 text-slate-400 hover:text-white"
            aria-label="Dismiss message"
          >
            <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
              <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        )}
      </div>
    </div>
  );
}
