# Frontend conventions

`../docs/requirements/requirements.md` states what the system must do, including the rules the UI is
expected to respect. `../docs/architecture/architecture.md` describes how the SPA fits into the wider
system — the proxy, the two transports, and what the backend guarantees. This file covers the
conventions to follow inside `frontend/`.

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
- **Theme** flows through `contexts/ThemeProvider.tsx` (context object in `contexts/themeContextValue.ts`)
  + `hooks/useTheme.ts`, mounted outermost in `App.tsx` so the auth pages get it too. That split of the
  `createContext` call into its own module is not stylistic: `react-refresh/only-export-components`
  warns when a `.tsx` file exports a non-component value, and `npm run lint` runs `--max-warnings 0` in
  CI. Follow it for any new context.
- Shared TypeScript types (mirroring backend DTOs/enums, e.g. `UpgradeStatus`) live in `src/types/index.ts`.
- Styling is Tailwind CSS; reusable primitives are in `src/components/ui/`.
- **Colours are semantic tokens, never palette shades.** Write `bg-surface`, not `bg-white`;
  `text-fg-subtle`, not `text-gray-500`. The tokens are declared in `tailwind.config.js` and given
  their light and dark values in `src/index.css`. `npm run check:colours` fails on any direct palette
  use and runs in CI — Tailwind itself emits nothing and reports nothing for an unrecognised class, so
  a mistyped token renders as *no colour at all* rather than as an error.
  - Adding a colour means adding a **role**, with both values, not reaching for a shade. If no existing
    role fits, the role is what is missing.
  - Check contrast when you add one: 4.5:1 for text, 3:1 for control boundaries, in both themes.
    Nothing re-checks this automatically.
  - The `dark` class on `<html>` is set by the inline boot script in `index.html` *and* by
    `ThemeProvider`. They must agree on the storage key (`theme`) and the resolution rule; changing one
    alone brings back the flash of wrong theme on load.
