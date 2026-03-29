"use client";

function Block({ className }: { className: string }) {
  return <div className={`skeleton-block ${className}`} />;
}

export function HomePageSkeleton() {
  return (
    <div className="app-page space-y-10">
      <div className="app-surface app-card overflow-hidden p-4 sm:p-6">
        <div className="grid min-h-[420px] gap-8 lg:grid-cols-[1.1fr,0.9fr]">
          <div className="flex flex-col justify-end space-y-4 py-4 sm:py-8">
            <Block className="h-4 w-28 rounded-full" />
            <Block className="h-14 w-3/4" />
            <Block className="h-5 w-52" />
            <Block className="h-4 w-full max-w-xl" />
            <Block className="h-4 w-11/12 max-w-lg" />
            <div className="flex gap-3 pt-3">
              <Block className="h-12 w-40 rounded-full" />
              <Block className="h-12 w-32 rounded-full" />
            </div>
          </div>
          <Block className="min-h-[280px] rounded-[1.6rem]" />
        </div>
      </div>

      <div className="flex items-end justify-between gap-4">
        <div className="space-y-3">
          <Block className="h-7 w-44" />
          <Block className="h-4 w-72" />
        </div>
        <Block className="hidden h-10 w-28 rounded-full md:block" />
      </div>

      <GridSkeleton count={12} />
    </div>
  );
}

export function SearchSkeleton() {
  return (
    <div className="app-page space-y-8">
      <div className="space-y-3">
        <Block className="h-8 w-64" />
        <Block className="h-4 w-44" />
      </div>
      <GridSkeleton count={12} />
    </div>
  );
}

export function MovieDetailsSkeleton() {
  return (
    <div className="pb-16">
      <Block className="h-[42vh] min-h-[280px] w-full rounded-none" />
      <div className="app-page -mt-20 relative z-10 space-y-8">
        <div className="app-surface app-card p-5 sm:p-7">
          <div className="grid gap-6 md:grid-cols-[220px,1fr]">
            <Block className="aspect-[2/3] w-full max-w-[220px] rounded-[1.4rem]" />
            <div className="space-y-4 pt-2">
              <Block className="h-12 w-3/4" />
              <Block className="h-5 w-52" />
              <div className="flex flex-wrap gap-2">
                <Block className="h-8 w-24 rounded-full" />
                <Block className="h-8 w-28 rounded-full" />
                <Block className="h-8 w-20 rounded-full" />
              </div>
              <Block className="h-4 w-full" />
              <Block className="h-4 w-11/12" />
              <Block className="h-4 w-4/5" />
              <div className="flex flex-wrap gap-3 pt-3">
                <Block className="h-12 w-44 rounded-full" />
                <Block className="h-12 w-36 rounded-full" />
                <Block className="h-12 w-36 rounded-full" />
              </div>
            </div>
          </div>
        </div>

        <div className="grid gap-6 xl:grid-cols-[1.45fr,0.95fr]">
          <div className="space-y-5">
            <Block className="h-7 w-36" />
            <Block className="h-28 w-full rounded-[1.4rem]" />
            <Block className="h-28 w-full rounded-[1.4rem]" />
          </div>
          <div className="space-y-5">
            <Block className="h-7 w-44" />
            <Block className="h-64 w-full rounded-[1.4rem]" />
          </div>
        </div>
      </div>
    </div>
  );
}

export function WatchlistSkeleton() {
  return (
    <div className="app-page space-y-8">
      <div className="space-y-3">
        <Block className="h-8 w-52" />
        <Block className="h-4 w-28" />
      </div>

      <div className="flex flex-wrap gap-3">
        <Block className="h-11 w-24 rounded-full" />
        <Block className="h-11 w-40 rounded-full" />
        <Block className="h-11 w-28 rounded-full" />
        <Block className="h-11 w-28 rounded-full" />
      </div>

      <div className="space-y-4">
        {Array.from({ length: 4 }, (_, index) => (
          <div key={index} className="app-surface app-card grid gap-4 p-4 sm:grid-cols-[92px,1fr] sm:p-5">
            <Block className="aspect-[2/3] w-[92px] rounded-[1rem]" />
            <div className="space-y-4">
              <Block className="h-6 w-2/5" />
              <div className="flex flex-wrap gap-3">
                <Block className="h-8 w-28 rounded-full" />
                <Block className="h-4 w-32" />
              </div>
              <div className="flex flex-wrap gap-3">
                <Block className="h-11 w-36 rounded-[1rem]" />
                <Block className="h-11 w-11 rounded-full" />
              </div>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

export function GridSkeleton({ count = 6 }: { count?: number }) {
  return (
    <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5">
      {Array.from({ length: count }, (_, index) => (
        <div key={index} className="space-y-3">
          <Block className="aspect-[2/3] w-full rounded-[1.35rem]" />
          <Block className="h-4 w-5/6" />
          <Block className="h-3 w-24" />
        </div>
      ))}
    </div>
  );
}
