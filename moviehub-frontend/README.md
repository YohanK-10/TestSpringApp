# AtlasWatch Frontend

Next.js 15 and React 19 client for AtlasWatch. It provides public movie discovery, account flows, reviews, watchlists, and explainable personalized or cold-start recommendations.

## Local development

Create `.env.local` from `.env.example`, then install and run:

```bash
npm ci
npm run dev
```

The Spring Boot API must be available at `NEXT_PUBLIC_API_URL` (normally `http://localhost:8080`). Open [http://localhost:3000/homepage](http://localhost:3000/homepage).

## Quality checks

```bash
npm run lint
npm exec tsc --noEmit
npm run build
```

## Client architecture

- `app/`: App Router pages and layouts
- `components/`: shared navigation, cards, feedback, and rating components
- `lib/api.ts`: typed API boundary, error normalization, CSRF handling, and one-time access-token refresh
- `lib/types.ts`: API request and response contracts

Authentication tokens remain in `HttpOnly` cookies. Before a state-changing request, the client obtains a CSRF token from `/auth/csrf` and sends it using the header name returned by the backend.

## Container build

The root `docker-compose.yml` builds this application with `NEXT_PUBLIC_API_URL=http://localhost:8080` and exposes it on port 3000.
