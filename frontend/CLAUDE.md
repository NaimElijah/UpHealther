# Frontend conventions

`../docs/requirements/requirements.md` states what the system must do, including the rules the UI is
expected to respect. `../docs/architecture/architecture.md` describes how the SPA fits into the wider
system — the proxy, the two transports, and what the backend guarantees. This file covers the
conventions to follow inside `frontend/`.

`npm run dev` serves on :3000 and proxies `/api` → `http://localhost:8080` (see `vite.config.ts`).

- **Tests** are Vitest on jsdom ([ADR-004](../docs/ADRs/ADR-004-frontend-test-harness.md)), colocated
  beside what they test, and named `Given<state>_When<action>_Then<outcome>`
  ([ADR-009](../docs/ADRs/ADR-009-test-levels-boundaries-and-naming.md)). `npm run test:coverage` adds
  a coverage summary; nothing gates on the number. Two harness details worth knowing before writing
  one: `window.location` has to be redefined to observe a navigation, because jsdom refuses to perform
  one; and an axios call is tested by replacing `client.defaults.adapter`, so the real interceptor
  chain still runs.

- **`src/api/client.ts`** is the single axios instance. Its `baseURL` is relative by default so every
  `/api/...` call is same-origin and flows through the Vite dev proxy / nginx prod proxy (set
  `VITE_API_URL` only for a different-origin API). A request interceptor attaches the JWT from
  `localStorage['jwt_token']`; a response interceptor clears the token and redirects to `/login` on 401.
  That interceptor is **not** what ends an expired session: the API refuses an expired token with 403,
  not 401, so expiry is noticed by `AuthContext`'s mount-time `/api/auth/me` check on the next page
  load instead ([#58](https://github.com/NaimElijah/UpHealther/issues/58)).
  All `src/api/*.ts` modules call through this client — add new endpoints there, not with raw axios.
- **A failure is decoded once, in `src/api/apiError.ts`.** `toApiError(thrown)` turns anything a call
  rejected with into `{ status, message, fieldErrors, traceId }`; render it with
  `components/ui/ErrorState`. Do not write a hard-coded "Failed to load X." and drop the error — the
  backend puts a **trace id** on every response (`X-Trace-Id`, and `traceId` on the error body) so that
  a user can quote one string that finds their request in the log, and discarding it is what left a
  support conversation with nothing in it. `toApiError` reads the body first and the header second,
  because a request refused inside the security chain carries the header alone.
- **`components/ErrorBoundary`** is mounted in `App.tsx` inside `ThemeProvider` and around the router:
  inside so its fallback is themed, outside so a throw in any page is contained. It has to stay a
  class — `getDerivedStateFromError` has no hooks equivalent. It shows and does not record: there is
  no sink to record to, and adding one is a decision about sending user data off the device.
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
  a mistyped token renders as *no colour at all* rather than as an error. It covers everything in
  Tailwind's `content` glob, `index.html` included, and matches named utilities only: an arbitrary
  value like `bg-[#fff]` slips past it.
  - Adding a colour means adding a **role**, with both values, not reaching for a shade. If no existing
    role fits, the role is what is missing.
  - Check contrast when you add one: 4.5:1 for text, 3:1 for control boundaries, in both themes.
    Nothing re-checks this automatically.
  - The `dark` class on `<html>` is set by the inline boot script in `index.html` *and* by
    `ThemeProvider`. They must agree on the storage key (`theme`) and the resolution rule; changing one
    alone brings back the flash of wrong theme on load. `src/contexts/bootScript.test.tsx` enforces
    that agreement — it runs the shipped script verbatim and compares it against the provider across
    every combination of stored choice and system preference, so drift fails the build instead of
    shipping. Change the rule in both places, and the test will tell you if you missed one.
