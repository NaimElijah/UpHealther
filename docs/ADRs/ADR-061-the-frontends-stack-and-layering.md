# ADR-061: The frontend's stack, and the layering lint enforces

- **Status:** Accepted. It records decisions already in the code, which no earlier record explains.
  Only the lint enforcement of the layering is new with it.
- **Date:** 2026-10-30
- **Scope:** `frontend/src/` and `frontend/eslint.config.js`. No new dependency.
- **Issue:** [#338](https://github.com/NaimElijah/UpHealther/issues/338), scan 04 of
  [#334](https://github.com/NaimElijah/UpHealther/issues/334), findings A4.F2, A4.F3 and A4.F8.
  [ADR-001](ADR-001-ddd-hexagonal-architecture.md) and
  [ADR-002](ADR-002-close-the-gap-between-the-described-and-enforced-architecture.md) are scoped to
  `backend/`. This is their counterpart for the SPA.

## Context

The frontend is a React 18 single-page application built by Vite. Several ADRs record parts of its
toolchain:

- the test harness ([ADR-004](ADR-004-frontend-test-harness.md));
- React Router 6 ([ADR-034](ADR-034-stay-on-react-router-6-while-its-open-advisories-do-not-reach-the-spa.md));
- ESLint ([ADR-035](ADR-035-the-frontend-lints-with-eslint-10-and-a-flat-config.md));
- Vite 8 and Vitest 5 ([ADR-036](ADR-036-the-frontend-builds-on-vite-8-and-tests-on-vitest-5.md));
- Prettier ([ADR-038](ADR-038-the-frontend-is-formatted-by-prettier.md));
- the generated wire types ([ADR-053](ADR-053-the-frontends-wire-types-are-generated-from-the-apis-records.md)).

None of them records the choices the code is built on:

- **Server state** is held by TanStack Query (5.104.3, from the lockfile). There is one cache, built by
  `lib/queryClient.ts` and provided once in `App.tsx`.
- **Shared client state** is three React contexts: `AuthProvider`, `ThemeProvider` and
  `NotificationProvider`. A form's fields, or whether a dialog is open, live in the component that
  shows them.
- **HTTP** goes through one axios instance (1.20.0), `api/client.ts`. Its interceptors attach the
  in-memory access token, and renew it once on a 401 ([ADR-015](ADR-015-server-side-sessions-behind-a-rotating-refresh-cookie.md)).
  Nothing calls `fetch`.
- **Push** is STOMP through `@stomp/stompjs` (7.5.0), on a plain WebSocket to `/ws`, in
  `api/notificationSocket.ts`.
- **Delivery** is a static build: `vite build` writes `dist/`, and nginx serves it. nginx falls back to
  `index.html` for any path, and proxies `/api` and `/ws` to the backend. Nothing renders on a server.
- **Styling** is Tailwind CSS (3.4.19, held there by [ADR-060](ADR-060-dependabot-holds-the-tailwind-and-typescript-majors-back.md)).
  Every colour is a semantic token, and `npm run check:colours` fails on a direct palette class.

The folders of `src/` follow one dependency order, and nothing stated it or checked it. Before this
record, a component importing a page passed every check. So did an `axios` call from a page, which goes
around the interceptors and so around the session. The order held once a toast view stopped being
imported by the notification provider (A4.F1).

## Decision

**The stack.**

- **TanStack Query is the one owner of server state.** A component reads the server through a query and
  writes through a mutation, and the result is not copied into React state. A write that changes an
  upgrade refreshes every view of upgrades ([ADR-029](ADR-029-a-write-to-an-upgrade-refreshes-every-view-of-upgrades.md)).
- **React Context holds the little client state that is shared**, one provider per concern: the
  signed-in user, the theme, and the notification connection with its toasts. Each context object
  lives in its own `…ContextValue.ts`, and its reader in `hooks/`. The split exists because
  `react-refresh/only-export-components` refuses a `.tsx` file that exports a context.
- **axios, with interceptors, is the HTTP client.** The interceptors carry the whole session behaviour
  in one place: the token on every request, and the renewal and single retry on a 401.
- **`@stomp/stompjs` is the push client.** The backend speaks STOMP on `/ws`, and the client handles
  reconnecting, the `CONNECT` header and the frames.
- **The SPA is a static build**, served by nginx behind the same origin as the API.
- **Tailwind CSS, with semantic colour tokens.** A component names a role, such as `bg-surface`, and
  never a shade. The role's value for each theme is set once, in `index.css`, so a component cannot be
  right in one theme and wrong in the other. A palette shade would be: it renders correctly in the theme
  it was picked in and can be invisible in the other. Tailwind emits nothing and reports nothing for a
  class it does not know, so nothing would say so. `check:colours` fails the build on any palette class,
  which is the guarantee the tokens rest on.

**The layering.** Each folder of `src/` imports only from the folders below it:

`types` ← `api` ← `lib` ← `contexts` + `hooks` ← `components` ← `pages` ← `router` ← `App.tsx` and `main.tsx`

`contexts` and `hooks` are one layer, and may import each other: each hook is the reader half of one
context.

**The single-owner imports.** Some modules may be imported from one place only:

- `axios` only under `src/api/`, so that no request skips the interceptors;
- `@stomp/stompjs` only in `api/notificationSocket.ts`, so that the live notifications are one
  transport;
- `types/api.generated` only in `types/index.ts`, which gives the generated types the names the app
  uses (ADR-053);
- `api/tokenStore` only under `src/api/` and in `contexts/AuthContext.tsx`, which stores the token a
  sign-in returns, so that the token reaches no component (ADR-015).

**Enforcement** is core ESLint's `no-restricted-imports`, in `eslint.config.js`. `npm run lint` already
runs it, with `--max-warnings 0`.

- There is one block per folder. Owners get a block of their own after their folder's: the API's
  folder, `api/notificationSocket.ts`, `types/index.ts` and `contexts/AuthContext.tsx`.
- Flat config does not merge two entries of this rule that match the same file; the last one wins.
  So each block carries its files' whole set of restrictions. A helper builds every block from the
  layer order and the owner list, which are each written once.
- Every message names its rule and its ADR.
- The rules cover production files only. A test, `src/test/` and `src/setupTests.ts` may import across
  layers, because a test may put a provider together with the view that reads it.

## Consequences

**What this makes easy**

- **An import that points upward fails lint** and names the order, so a reviewer does not have to spot
  it.
- **The session lives in one layer.** A page or a component cannot reach `axios` or the token directly,
  so a request outside the interceptors needs an edit to `eslint.config.js` first.
- **The record of why** these libraries were chosen exists, so the next proposal to replace one starts
  from it.

**What this makes hard**

- **A new folder under `src/` is unrestricted until it is given a layer.** `LAYERS` in
  `eslint.config.js` has to name it, in the right place.
- **Some cycles get past the rule.** A cycle within a layer, between `contexts` and `hooks`, or between
  two folders of `components/`, passes. The rule restricts where an import may point; it does not
  follow the graph.
- **Some imports are matched loosely.** Patterns match the import as it is written, a relative path.
  - A dynamic `import()` is not checked at all. No production file uses one.
  - A folder inside a layer that happens to share a layer's name would be matched as that layer.
    `components/ui/` is not; a future `components/pages/` would be.
- **A component cannot use axios for a one-off call.** It calls a function in `api/`, which is the
  point, and is also more code.

## Alternatives considered

- **Redux or Zustand for client state.** Rejected. Every piece of shared state belongs to one concern
  and has one writer. The one link between providers is `NotificationProvider` reading whether anybody
  is signed in, through `useAuth`. A store would add a dependency and a second home for state, with no
  caller that needs it.
- **SWR for server state.** Rejected. The code is written against TanStack Query's cache API. The
  optimistic notification updates cancel a fetch in flight (`cancelQueries`), write the cache
  (`setQueryData`) and invalidate by key prefix. `refreshUpgradeViews` invalidates a list of keys.
  Moving would mean re-expressing each of those, for nothing the app lacks.
- **`fetch` instead of axios.** Rejected. The session behaviour lives in interceptors: the token on
  every request, a single-flight renewal on a 401, and one retry. With `fetch` that becomes a wrapper
  the project writes and maintains, and the same rule is needed to keep a raw `fetch` out of the pages.
- **Server rendering, or a meta-framework such as Next.js.** Rejected. Every screen but sign-in and
  registration is behind a session, and nothing needs to be indexed. A static build behind nginx needs
  no Node process in production: the image that serves it is nginx alone.
- **Plain CSS, or CSS modules.** Rejected. Tailwind's tokens put both themes' values in one stylesheet,
  and a colour reaches a component as a class name, which `check:colours` checks against the palette.
  In hand-written CSS a colour can be any value in any declaration, and the same guarantee would need a
  check that parses CSS.
- **An ESLint import plugin, or dependency-cruiser, instead of core `no-restricted-imports`.**
  - Each would add a dependency, and the global rules ask for a justification of each.
  - A plugin would also make each ESLint major wait on its peer range, as `typescript-eslint` already
    does for TypeScript (ADR-060).
  - dependency-cruiser would add a second tool and a configuration of its own to the gate.
  - What they would add is cycle detection and resolved paths. No production module of `src/` is in an
    import cycle today, and the core rule covers the order.

## When to revisit

- **Client state crosses providers** beyond that one read: a provider has to write another's state, or
  the same state is wanted in two. That is when a store earns its place.
- **The SPA needs server rendering or SEO**, for example a public landing page for a signed-out visitor
  ([#232](https://github.com/NaimElijah/UpHealther/issues/232)).
- **[#156](https://github.com/NaimElijah/UpHealther/issues/156), the installable PWA, is taken on.** It
  means persisting the query cache, so it changes what "one owner of server state" covers.
- **TypeScript 7 arrives ([#370](https://github.com/NaimElijah/UpHealther/issues/370)), or a lint plugin
  detects import cycles without a resolver of its own.** Either is the moment to weigh cycle detection
  again.
