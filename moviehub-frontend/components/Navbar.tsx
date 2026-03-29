"use client";

import { useEffect, useState } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { logout } from "@/lib/api";

const NAV_LINKS = [
  { href: "/homepage", label: "Home" },
  { href: "/watchlist", label: "Watchlist" },
];

export default function Navbar() {
  const [query, setQuery] = useState("");
  const [mobileOpen, setMobileOpen] = useState(false);
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();

  useEffect(() => {
    setQuery(searchParams.get("q") ?? "");
  }, [searchParams]);

  const handleSearch = (event: React.FormEvent) => {
    event.preventDefault();
    const trimmed = query.trim();
    if (!trimmed) return;
    router.push(`/search?q=${encodeURIComponent(trimmed)}`);
    setMobileOpen(false);
  };

  const handleLogout = async () => {
    try {
      await logout();
    } catch {
      // Even if the API fails, the safest UX is to move the user to login.
    }
    router.push("/login");
  };

  return (
    <nav className="sticky top-0 z-50 border-b border-slate-700/40 bg-slate-950/74 backdrop-blur-xl">
      <div className="mx-auto flex w-full max-w-7xl items-center justify-between gap-3 px-4 py-3 sm:px-6 lg:px-8">
        <button
          type="button"
          onClick={() => router.push("/homepage")}
          className="flex shrink-0 items-center gap-3 text-left"
        >
          <div className="rounded-2xl border border-white/8 bg-white/4 p-2.5">
            <svg width="26" height="26" viewBox="0 0 256 256" xmlns="http://www.w3.org/2000/svg">
              <defs>
                <linearGradient id="navGrad" x1="0%" y1="0%" x2="100%" y2="100%">
                  <stop offset="0%" stopColor="#f59e0b" />
                  <stop offset="35%" stopColor="#f97316" />
                  <stop offset="75%" stopColor="#fb7185" />
                </linearGradient>
              </defs>
              <path
                fill="url(#navGrad)"
                d="M 65 42 L 56 55 L 56 165 L 59 172 L 68 179 L 78 180 L 81 199 L 87 203 L 95 203 L 101 198 L 103 193 L 103 158 L 107 156 L 110 160 L 110 178 L 112 182 L 118 186 L 129 184 L 133 178 L 133 156 L 138 153 L 140 155 L 141 184 L 148 190 L 160 188 L 164 181 L 164 133 L 184 118 L 187 110 L 184 100 L 178 94 L 150 79 L 143 79 L 135 87 L 133 109 L 128 107 L 127 97 L 122 91 L 113 90 L 107 93 L 104 98 L 104 121 L 99 123 L 97 121 L 97 103 L 95 99 L 89 95 L 75 96 L 77 103 L 86 103 L 88 105 L 89 124 L 97 132 L 104 132 L 110 128 L 112 124 L 112 101 L 114 99 L 119 101 L 119 108 L 124 116 L 129 118 L 138 116 L 143 109 L 143 90 L 145 88 L 149 88 L 174 102 L 178 108 L 178 111 L 165 122 L 162 114 L 158 114 L 155 117 L 154 181 L 151 182 L 149 180 L 149 155 L 144 146 L 133 144 L 128 147 L 125 152 L 125 174 L 123 177 L 118 175 L 118 156 L 116 152 L 111 148 L 102 148 L 97 151 L 94 157 L 94 193 L 90 195 L 87 192 L 87 162 L 81 161 L 78 171 L 71 171 L 65 165 L 65 55 L 71 49 L 78 49 L 126 76 L 131 76 L 133 71 L 81 41 L 71 40 Z"
              />
            </svg>
          </div>
          <div className="hidden sm:block">
            <p className="text-[0.7rem] uppercase tracking-[0.28em] text-amber-300/80">
              Discover and track
            </p>
            <p className="text-lg font-bold text-white">AtlasWatch</p>
          </div>
        </button>

        <form onSubmit={handleSearch} className="hidden min-w-0 flex-1 md:block">
          <div className="mx-auto flex max-w-2xl items-center gap-3 rounded-full border border-slate-700/50 bg-slate-900/70 px-4 py-2 shadow-[0_12px_40px_rgba(0,0,0,0.18)]">
            <svg className="h-4 w-4 shrink-0 text-slate-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
              <path strokeLinecap="round" strokeLinejoin="round" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
            </svg>
            <input
              type="text"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="Search movies, cast, or a title you forgot years ago..."
              className="w-full bg-transparent text-sm text-white placeholder:text-slate-500 focus:outline-none"
            />
          </div>
        </form>

        <div className="hidden items-center gap-2 md:flex">
          {NAV_LINKS.map((link) => {
            const active = pathname === link.href;
            return (
              <button
                key={link.href}
                type="button"
                onClick={() => router.push(link.href)}
                className={`rounded-full px-4 py-2 text-sm font-semibold transition ${
                  active
                    ? "bg-amber-400/14 text-amber-200"
                    : "text-slate-300 hover:bg-white/6 hover:text-white"
                }`}
              >
                {link.label}
              </button>
            );
          })}
          <button type="button" onClick={handleLogout} className="btn-ghost text-sm text-slate-300">
            Logout
          </button>
        </div>

        <button
          type="button"
          onClick={() => setMobileOpen((open) => !open)}
          className="rounded-full border border-slate-700/40 bg-white/4 p-2.5 text-slate-200 transition hover:bg-white/8 md:hidden"
          aria-label="Toggle navigation"
        >
          <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
            {mobileOpen ? (
              <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
            ) : (
              <path strokeLinecap="round" strokeLinejoin="round" d="M4 6h16M4 12h16M4 18h16" />
            )}
          </svg>
        </button>
      </div>

      {mobileOpen && (
        <div className="border-t border-slate-700/35 bg-slate-950/90 px-4 pb-5 pt-4 backdrop-blur-xl md:hidden">
          <form onSubmit={handleSearch} className="mb-4">
            <div className="flex items-center gap-3 rounded-2xl border border-slate-700/45 bg-slate-900/78 px-4 py-3">
              <svg className="h-4 w-4 shrink-0 text-slate-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                <path strokeLinecap="round" strokeLinejoin="round" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
              </svg>
              <input
                type="text"
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder="Search movies..."
                className="w-full bg-transparent text-sm text-white placeholder:text-slate-500 focus:outline-none"
              />
            </div>
          </form>

          <div className="space-y-2">
            {NAV_LINKS.map((link) => (
              <button
                key={link.href}
                type="button"
                onClick={() => {
                  router.push(link.href);
                  setMobileOpen(false);
                }}
                className={`block w-full rounded-2xl px-4 py-3 text-left text-sm font-semibold ${
                  pathname === link.href
                    ? "bg-amber-400/12 text-amber-200"
                    : "bg-white/4 text-slate-200"
                }`}
              >
                {link.label}
              </button>
            ))}
            <button
              type="button"
              onClick={handleLogout}
              className="block w-full rounded-2xl bg-white/4 px-4 py-3 text-left text-sm font-semibold text-slate-300"
            >
              Logout
            </button>
          </div>
        </div>
      )}
    </nav>
  );
}
