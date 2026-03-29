"use client";

interface Props {
  title: string;
  description: string;
  tone?: "default" | "error" | "success";
  actionLabel?: string;
  onAction?: () => void;
  secondaryLabel?: string;
  onSecondaryAction?: () => void;
  compact?: boolean;
}

function StatusIcon({ tone }: { tone: Props["tone"] }) {
  if (tone === "error") {
    return (
      <svg className="h-6 w-6 text-rose-300" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.8}>
        <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v4m0 4h.01M10.29 3.86l-7.54 13.5A1 1 0 003.62 19h16.76a1 1 0 00.87-1.64l-7.54-13.5a1 1 0 00-1.74 0z" />
      </svg>
    );
  }

  if (tone === "success") {
    return (
      <svg className="h-6 w-6 text-emerald-300" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.8}>
        <path strokeLinecap="round" strokeLinejoin="round" d="M9 12.75l2.25 2.25L15 9.75M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
      </svg>
    );
  }

  return (
    <svg className="h-6 w-6 text-amber-300" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.8}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M11.25 6h2.25m-6 12h9a2.25 2.25 0 002.25-2.25V8.25A2.25 2.25 0 0016.5 6h-9A2.25 2.25 0 005.25 8.25v7.5A2.25 2.25 0 007.5 18z" />
    </svg>
  );
}

export default function StatusPanel({
  title,
  description,
  tone = "default",
  actionLabel,
  onAction,
  secondaryLabel,
  onSecondaryAction,
  compact = false,
}: Props) {
  return (
    <div
      data-tone={tone}
      className={`status-panel ${compact ? "p-5" : "p-6 sm:p-8"} text-left`}
    >
      <div className={`flex ${compact ? "gap-3" : "gap-4"} items-start`}>
        <div className="mt-0.5 flex h-11 w-11 shrink-0 items-center justify-center rounded-2xl bg-white/6">
          <StatusIcon tone={tone} />
        </div>
        <div className="min-w-0 flex-1">
          <h2 className={`${compact ? "text-lg" : "text-2xl"} font-semibold text-white`}>
            {title}
          </h2>
          <p className="mt-2 max-w-2xl text-sm leading-6 text-slate-300">
            {description}
          </p>

          {(actionLabel || secondaryLabel) && (
            <div className="mt-5 flex flex-wrap gap-3">
              {actionLabel && onAction && (
                <button onClick={onAction} className="btn-primary">
                  {actionLabel}
                </button>
              )}
              {secondaryLabel && onSecondaryAction && (
                <button onClick={onSecondaryAction} className="btn-secondary">
                  {secondaryLabel}
                </button>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
