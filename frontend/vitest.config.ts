import { defineConfig, mergeConfig } from 'vitest/config';
import viteConfig from './vite.config';

// Every test runs in one fixed zone, set here in the main process so the forked workers inherit it.
// Left to the machine, the suite ran in UTC on CI and in UTC+2/+3 on a developer's laptop, so a bug
// in how a date is taken or shown passed on one and failed on the other (#95). West of UTC is chosen
// because that is where both halves of that bug show: the UTC date is tomorrow in the evening, and a
// `YYYY-MM-DD` parsed as UTC midnight is displayed as the day before.
process.env.TZ = 'America/Los_Angeles';

/**
 * Test configuration, layered over the real build configuration rather than restating it.
 *
 * Vitest reads this file *instead of* `vite.config.ts` when both exist, so the plugin list is merged in
 * explicitly — without the merge the tests would run without the React plugin and every `.tsx` file
 * would fail to transform.
 *
 * Kept out of `vite.config.ts` so the production build configuration does not type-depend on a
 * development-only package. See `docs/ADRs/ADR-004-frontend-test-harness.md`.
 */
export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
      environment: 'jsdom',
      setupFiles: ['./src/setupTests.ts'],
      include: ['src/**/*.test.{ts,tsx}'],
      // `describe`/`it`/`expect` are imported explicitly instead, which is what keeps `tsconfig.json`'s
      // `types` array — and therefore the production type check — untouched.
      globals: false,
      restoreMocks: true,
      css: false,
      coverage: {
        // Reported, never gated: a threshold is satisfied by tests written to move a number, and says
        // nothing about whether a requirement is enforced. The gate is requirements.md naming a test
        // for each entry - see docs/ADRs/ADR-009-test-levels-boundaries-and-naming.md.
        provider: 'v8',
        reporter: ['text-summary', 'lcov'],
        include: ['src/**/*.{ts,tsx}'],
        // Type-only and entry modules: no branches to cover, so counting them only dilutes the number.
        exclude: ['src/**/*.test.{ts,tsx}', 'src/test/**', 'src/types/**', 'src/main.tsx'],
      },
    },
  }),
);
