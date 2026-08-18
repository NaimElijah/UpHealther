# Frontend conventions

`../docs/architecture/architecture.md` describes how the SPA fits into the wider system — the proxy,
the two transports, and what the backend guarantees. This file covers the conventions to follow inside
`frontend/`.

`npm run dev` serves on :3000 and proxies `/api` → `http://localhost:8080` (see `vite.config.ts`).

- **`src/api/client.ts`** is the single axios instance. Its `baseURL` is relative by default so every
  `/api/...` call is same-origin and flows through the Vite dev proxy / nginx prod proxy (set
  `VITE_API_URL` only for a different-origin API). A request interceptor attaches the JWT from
  `localStorage['jwt_token']`; a response interceptor clears the token and redirects to `/login` on 401.
  All `src/api/*.ts` modules call through this client — add new endpoints there, not with raw axios.
- **Server state** is managed by TanStack Query (`@tanstack/react-query`); avoid duplicating it in
  local React state.
- **Auth** flows through `contexts/AuthContext.tsx` (the context object lives in `contexts/authContextValue.ts`)
  + `hooks/useAuth.ts`; routes are gated by `router/ProtectedRoute.tsx`. Routing is React Router 6.
- Shared TypeScript types (mirroring backend DTOs/enums, e.g. `UpgradeStatus`) live in `src/types/index.ts`.
- Styling is Tailwind CSS; reusable primitives are in `src/components/ui/`.
