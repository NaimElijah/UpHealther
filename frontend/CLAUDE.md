# Frontend conventions

The conventions to follow inside `frontend/`; `../.claude/CLAUDE.md` points at the requirements, the
architecture and the ADRs, and has the commands.

`npm run dev` serves on :3000 and proxies `/api` and `/ws` → `http://localhost:8080` (see `vite.config.ts`).

`src/` holds `api/` (the axios client, the token store, the error decoder, and one module per backend
area), `components/` (`ui/` primitives, plus `layout/`, `notifications/`, `theme/`, `upgrade/`),
`contexts/` and `hooks/`, `lib/` (query client, view refresh, local dates), `pages/`, `router/`,
`types/`, and `test/` (shared test helpers, and guards that span files, like `bootScriptCsp.test.ts`).

**Node is pinned.** `.nvmrc` names the release CI and the image build with, and `.npmrc`'s
`engine-strict` makes npm refuse to install outside `package.json`'s `engines` range
([ADR-030](../docs/ADRs/ADR-030-the-toolchain-is-pinned-in-the-repository.md),
[ADR-031](../docs/ADRs/ADR-031-the-frontend-toolchain-runs-on-node-24-lts.md)). Moving Node means
moving `.nvmrc`, `engines` and the `FROM` tag in `Dockerfile` together; `ContainerImagesArePinnedTest`
fails the backend build if the image and `.nvmrc` name different releases.

- **Tests** are Vitest on jsdom ([ADR-004](../docs/ADRs/ADR-004-frontend-test-harness.md),
  [ADR-036](../docs/ADRs/ADR-036-the-frontend-builds-on-vite-8-and-tests-on-vitest-5.md)), colocated
  beside what they test. `npm run test:coverage` adds a coverage summary; nothing gates on the number.
  Two harness details worth knowing before writing one: `window.location` has to be redefined to
  observe a navigation, because jsdom refuses to perform one; and an axios call is tested by replacing
  `client.defaults.adapter`, so the real interceptor chain still runs. Every test runs in
  `America/Los_Angeles`, pinned in `vitest.config.ts`, so a date bug shows on every machine (#95).

  Three traps this suite has already hit:
  - TanStack Query runs `mutationFn` a microtask after `mutate`, so a "called once" or "not called
    again" check made straight after the second click cannot fail. Let pending promises run first
    (`await act(() => new Promise<void>((r) => setTimeout(r, 0)))`), then show it RED without the
    guard.
  - `mutationFn` is called as `(variables, context)`, so `toHaveBeenCalledWith(body)` on an API mock
    wired straight in fails on the extra argument; assert on `mock.calls[0][0]`.
  - Run from an agent, Vitest 5 switches to its minimal reporter and hides output CI prints. Check a
    run is quiet with `npx vitest run --reporter=default`; a test that throws on purpose uses
    `silenceExpectedRenderErrors()` from `src/test/expectedRenderErrors.ts`, not a bare
    `console.error` spy.

- **Lint** is ESLint 10 with a flat config, `eslint.config.js`, and `npm run lint` fails on any
  warning ([ADR-035](../docs/ADRs/ADR-035-the-frontend-lints-with-eslint-10-and-a-flat-config.md)).
  It applies react-hooks' whole `recommended` set, which includes the React Compiler's rules,
  although the compiler is not used. Most often met:
  - no `setState` called synchronously inside an effect: subscribe with `useSyncExternalStore`, or
    move the update into the event handler that causes it. A ref is not a drop-in replacement for
    state an effect depends on, because changing a ref runs nothing;
  - no assignment to an outer variable during render, test helpers included: assign it in an
    effect.

  A finding is fixed, not switched off.

- **Layout** belongs to Prettier, with single quotes at 120 columns (`prettier.config.js`):
  `npm run format` rewrites, `npm run format:check` fails CI on any file it would change
  ([ADR-038](../docs/ADRs/ADR-038-the-frontend-is-formatted-by-prettier.md)). `index.html` is left out
  on purpose, because reformatting it would change the boot script's CSP hash, and so is `*.md`, whose
  prose is wrapped by hand. Prettier is pinned exactly.

- **`src/api/client.ts`** is the single axios instance. Its `baseURL` is relative by default so every
  `/api/...` call is same-origin and flows through the Vite dev proxy / nginx prod proxy (set
  `VITE_API_URL` only for a different-origin API). A request interceptor attaches the access token
  from **`src/api/tokenStore.ts`**, which holds it in memory and nowhere else — never web storage,
  because anything injected into the page can read that. What survives a reload is the `HttpOnly`
  refresh cookie, which this code cannot read by design.
  A response interceptor treats a 401 as an expired token rather than a lost session: it renews from
  the cookie — single-flight within the tab and across tabs via `navigator.locks`, since rotation
  makes a credential good for one use — and retries the request once. Only a refusal from the refresh
  itself ends the session and redirects to `/login`. A 401 from `/api/auth/login` or
  `/api/auth/register` is the form's answer and is left to the page, and a 403 ("known, not allowed")
  never ends a session. See [ADR-015](../docs/ADRs/ADR-015-server-side-sessions-behind-a-rotating-refresh-cookie.md).
  All `src/api/*.ts` modules call through this client — add new endpoints there, not with raw axios.
- **Signing out can fail, and the failure has to be shown.** `logout()` rejects when the server could
  not be reached; the refresh cookie is then still in the browser, so the next page load signs the
  user back in. Clearing local state anyway would tell somebody on a shared machine they had signed
  out when they had not.
- **A failure is decoded once, in `src/api/apiError.ts`.** `toApiError(thrown)` turns anything a call
  rejected with into `{ status, message, fieldErrors, traceId }`; render it with
  `components/ui/ErrorState` — in place of a page that could not load, or `inline` beside the control
  whose action failed, from the mutation's own `error`. Every mutation needs one: a refusal that shows
  nothing reads as a button that does not work. Do not write a hard-coded "Failed to load X." and drop
  the error — the backend puts a **trace id** on every response (`X-Trace-Id`, and `traceId` on the
  error body) so that a user can quote one string that finds their request in the log, and discarding
  it is what left a support conversation with nothing in it. `toApiError` reads the body first and the
  header second: every error body the backend builds carries the id, the container's error page
  included, so the header covers a body that is not the API's JSON at all.
- **`components/ErrorBoundary`** is mounted in `App.tsx` inside `ThemeProvider` and around the router:
  inside so its fallback is themed, outside so a throw in any page is contained. It has to stay a
  class — `getDerivedStateFromError` has no hooks equivalent. It shows and does not record: there is
  no sink to record to, and adding one is a decision about sending user data off the device.
- **Server state** is managed by TanStack Query (`@tanstack/react-query`); avoid duplicating it in
  local React state. The cache is built by `src/lib/queryClient.ts`, so a test can run with the app's
  own thirty-second staleness window.
- **A write that changes an upgrade calls `refreshUpgradeViews(queryClient)`** (`src/lib/upgradeViews.ts`),
  not `invalidateQueries` on its own page's key. A tracking-configuration save is one: the upgrade
  carries its configuration, and the daily check-in picks each entry's input from it. An upgrade shows
  on several pages under different keys, and a page opened again within the staleness window renders
  from the cache, so refreshing one key left the others showing a state the upgrade had left (#189).
  A new page that shows upgrades under a new key adds that key there
  ([ADR-029](../docs/ADRs/ADR-029-a-write-to-an-upgrade-refreshes-every-view-of-upgrades.md)).
- **Auth** flows through `contexts/AuthContext.tsx` (the context object lives in `contexts/authContextValue.ts`)
  + `hooks/useAuth.ts`; routes are gated by `router/ProtectedRoute.tsx`, and the administration page
  also by `router/RequireRole.tsx` — a courtesy, not a wall: the API decides. Routing is React Router 6,
  deliberately: two of its advisories are fixed only in 7.18, and neither reaches the SPA while no
  navigation target is built from input. Do not build one (a redirect-after-login, a `returnTo`
  parameter) without moving to 7.18 or later first
  ([ADR-034](../docs/ADRs/ADR-034-stay-on-react-router-6-while-its-open-advisories-do-not-reach-the-spa.md)).
- **Theme** flows through `contexts/ThemeProvider.tsx` (context object in `contexts/themeContextValue.ts`)
  + `hooks/useTheme.ts`, mounted outermost in `App.tsx` so the auth pages get it too. That split of the
  `createContext` call into its own module is not stylistic: `react-refresh/only-export-components`
  fails `npm run lint` when a `.tsx` file exports a context, or any other value that is neither a
  component nor a constant. `contexts/NotificationProvider.tsx` + `notificationContextValue.ts` +
  `hooks/useNotifications.ts` follow it too; so must any new context.
- Shared TypeScript types (mirroring backend DTOs/enums, e.g. `UpgradeStatus`) live in `src/types/index.ts`.
- Styling is Tailwind CSS; reusable primitives are in `src/components/ui/`.
- **The page is served under a CSP, and the inline boot script is permitted by a hash.** It is in
  `nginx.conf`, so `npm run dev` serves no headers and a violation only appears in a compose run or
  in production. `src/test/bootScriptCsp.test.ts` recomputes the hash from the shipped `index.html`
  and fails `npm test` if the two drift, which is the only moment anybody would notice — editing the
  boot script at all, whitespace included, changes its hash. Adding a third-party script, font or
  image source means editing the policy; that friction is deliberate
  ([ADR-018](../docs/ADRs/ADR-018-a-content-security-policy-with-a-hashed-inline-boot-script.md)).
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
    every combination of stored choice and system preference, so drift fails `npm test` instead of
    shipping. Change the rule in both places, and the test will tell you if you missed one.
