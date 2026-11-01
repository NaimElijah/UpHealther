/**
 * Playwright's configuration for the smoke suite that drives the composed stack: Chromium against the
 * frontend nginx serves on port 3000, which the root's `npm run test:images` starts from both images
 * and publishes for it. Run alone with `npm test` in `e2e/`, the suite starts nothing and drives whatever
 * answers on 127.0.0.1:3000. See
 * docs/ADRs/ADR-052-a-playwright-smoke-suite-drives-the-stack-the-image-check-starts.md.
 */
import { defineConfig, devices } from '@playwright/test';

/**
 * The only origin the stack accepts. CORS and the `/ws` origin check both allow `http://localhost:3000`
 * and nothing else, because compose does not pass `CORS_ALLOWED_ORIGINS`. `localhost` is also the only
 * host where a browser keeps the always-`Secure` refresh cookie over plain HTTP (ADR-041).
 */
const BASE_URL = 'http://localhost:3000';

/**
 * Docker Desktop accepts a connection on `::1` and then stalls it, and Chromium tries `::1` first for
 * `localhost`. Resolving the name to IPv4 inside the browser keeps the origin `localhost`, which the
 * `BASE_URL` depends on. On Linux it changes nothing.
 */
const RESOLVE_LOCALHOST_TO_IPV4 = '--host-resolver-rules=MAP localhost 127.0.0.1';

/** The backend answers its first requests cold, after Flyway has migrated an empty database. */
const ASSERTION_TIMEOUT_MS = 10_000;
const TEST_TIMEOUT_MS = 60_000;
const SUITE_TIMEOUT_MS = 5 * 60_000;

export default defineConfig({
  testDir: 'tests',
  // A leftover `.only` would silently skip the rest of the suite in CI and locally alike, so it fails
  // everywhere, as vitest.config.ts does for the frontend.
  forbidOnly: true,
  // A flaky step fails loudly and is fixed. A retry would hide it (ADR-052).
  retries: 0,
  // Every run registers an account, and sign-in and registration share ten attempts a minute per
  // address (NFR-42). One worker keeps the suite's use of that budget predictable.
  workers: 1,
  timeout: TEST_TIMEOUT_MS,
  globalTimeout: SUITE_TIMEOUT_MS,
  expect: { timeout: ASSERTION_TIMEOUT_MS },
  reporter: process.env.CI ? [['list'], ['github']] : 'list',
  use: {
    baseURL: BASE_URL,
    // The server keeps time in UTC (ADR-020) and the SPA dates an entry by the browser's local day, so
    // the browser runs in UTC too. Otherwise, east of UTC, between local midnight and UTC midnight, an
    // entry the browser dates today is tomorrow's to the server.
    timezoneId: 'UTC',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        // The sidebar the suite navigates by is shown from Tailwind's `md` width up.
        viewport: { width: 1280, height: 720 },
        launchOptions: { args: [RESOLVE_LOCALHOST_TO_IPV4] },
      },
    },
  ],
});
