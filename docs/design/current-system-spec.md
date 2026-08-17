# AtlasWatch — current frontend system

Paste this into Claude Design alongside your prompt so it designs against the
real component vocabulary instead of inventing one.

## Product

AtlasWatch is a movie discovery, tracking, and recommendation web app. Users
search TMDB, open movie details, rate and review, keep a watchlist, and use
"Pick for Me" — a vibe-based recommender that returns a 5-item shortlist with
an explanation for each pick.

The content is film poster art: saturated, wildly varied, unpredictable colour.
**The interface must recede and let posters carry the colour.**

## Stack constraints

- Next.js 15 (App Router), React 19, TypeScript
- Tailwind CSS v4 with CSS-first config — there is no `tailwind.config.js`.
  Tokens live as CSS custom properties in `app/globals.css` under `:root`, and
  are exposed to Tailwind via `@theme inline`.
- Dark UI only today. A light mode would be new work.
- Fonts must be available on Google Fonts or npm (`next/font`).

## Existing design tokens (`app/globals.css`)

Replacing the *values* of these is cheap. Adding or renaming them means
touching every consumer, so prefer redefining what exists.

```
--background        #05070b
--foreground        #f8fafc
--muted             #94a3b8
--muted-strong      #cbd5e1
--surface           rgba(12, 18, 28, 0.86)
--surface-strong    rgba(17, 24, 39, 0.96)
--surface-soft      rgba(15, 23, 42, 0.62)
--border            rgba(148, 163, 184, 0.18)
--accent            #f59e0b
--accent-strong     #fb923c
--danger            #f87171
--success           #34d399
--info              #38bdf8
--shadow            0 24px 70px rgba(0, 0, 0, 0.35)
--radius-lg         1.25rem
--radius-xl         1.75rem
```

Body font is currently `"Trebuchet MS", "Avenir Next", "Segoe UI", sans-serif`.
There is no type scale and no spacing scale — sizes are ad hoc per component.

## Existing utility classes

These sit between tokens and components. A redesign changes these plus the
tokens; it should not need to change component markup.

| Class | Role |
|---|---|
| `.app-page` | Page shell / max width |
| `.app-surface`, `.app-surface-soft` | Panel backgrounds |
| `.app-card` | Card container |
| `.app-title`, `.app-section-title` | Headings |
| `.app-copy-muted`, `.app-copy-soft` | Secondary text |
| `.app-pill` | Tag / chip |
| `.btn-primary`, `.btn-secondary`, `.btn-ghost`, `.btn-danger` | Buttons |
| `.field-textarea` | Text input |
| `.status-panel` (`data-tone="error" \| "success"`) | Empty / error state |
| `.feedback-banner` (`data-tone="success" \| "error" \| "info"`) | Inline notice |

## Component inventory

Design against these. Do not introduce a different component vocabulary.

- **MovieCard** — `{ tmdbId, title, posterPath, rating?, releaseDate? }`.
  Clickable. Currently shows poster, rating badge, year badge, title, and a
  redundant "MOVIE DETAILS" caption on every card.
- **Navbar** — logo, search, account/nav. Currently collapses to a hamburger.
- **StarRating** — `{ value, max=5, onChange?, size: sm|md|lg, disabled? }`.
  Interactive with hover preview.
- **StatusPanel** — `{ title, description, tone: default|error|success,
  actionLabel?, secondaryLabel?, compact? }`. Empty and error states.
- **FeedbackBanner** — `{ title, message, tone: success|error|info, onDismiss? }`.
- **Skeletons** — per-page loading placeholders. Users see these on every load,
  so they need designing, not just greying out.
- **RemoteImage** — TMDB image wrapper with fallback.

## Routes

`/` search · `/homepage` discover grid · `/movie/[tmdbId]` detail ·
`/pick-for-me` recommender · `/watchlist` · `/login` · `/register` ·
`/password-reset` · `/verify`

## Real content to design with

Movies: *Project Hail Mary* 8.7 (2026) · *The Furious* 8.0 (2026) ·
*Backrooms* 7.1 (2026) · *Camp Rock 3* 6.1 (2026) · *Supergirl* (2026)

Ratings are 0–10 with one decimal, plus a vote count. Reviews use a 1–10 scale.

Pick for Me controls:
- **Vibes**, up to 5 of 14: Comforting, Funny, Tense, Dark, Emotional,
  Thoughtful, Adventurous, Cozy, Romantic, Eerie, Hopeful, Bittersweet,
  Mind-bending, Inspiring
- **Runtime**: any / short / medium / long
- **Era**: any / pre-1980 / 1980s / 1990s / 2000s / 2010s / 2020s
- **"Try another mix"** re-rolls the shortlist
- Each result carries reason chips explaining why it was picked

Watchlist statuses: Plan to watch · Watching · Watched

## What to move away from

Name these as anti-goals — they are the current problems:

1. Trebuchet MS as the type face
2. Amber→orange gradient CTA with an outer glow
3. Three stacked radial/linear gradients in the page background
4. Uniform 1.25–1.75rem radius on cards, buttons, badges, and logo alike
5. Repeated low-value labels ("MOVIE DETAILS" on every card)
6. No type scale, no spacing scale

## What to deliver back

1. Token values mapping onto the names above (font stack, type scale, spacing
   scale, neutral ramp, one accent with hover/active/focus, 3-step radius,
   elevation rules)
2. Desktop and mobile mockups of: discover grid, movie detail, Pick for Me
3. Default, empty, loading, and hover/focus states for cards and controls
4. Any change that alters structure rather than style, called out explicitly
