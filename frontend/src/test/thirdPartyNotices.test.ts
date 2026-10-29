// Runs in the suite's default jsdom environment for the reason bootScriptCsp.test.ts gives: the shared
// setupTests.ts touches localStorage and <html> before every test, so a node-environment file fails in
// setup.
import { build, type Rollup } from 'vite';
import { describe, expect, it } from 'vitest';
import tailwindLicence from 'tailwindcss/LICENSE?raw';
import preflightLicence from 'tailwindcss/lib/css/LICENSE?raw';

/** The asset `build.license` names in vite.config.ts. */
const NOTICES_FILE = 'THIRD-PARTY-NOTICES.txt';

/** A whole production build runs inside the test, which takes seconds where Vitest allows five. */
const BUILD_TIMEOUT_MS = 60_000;

/**
 * The notices the production build writes, built in memory from vite.config.ts, which Vite finds in the
 * working directory: `frontend/`, where `npm test` runs. A build that found another config, or none,
 * emits no notices and fails here rather than passing.
 */
async function buildNotices(): Promise<string> {
  const result = await build({ logLevel: 'silent', build: { write: false } });
  const outputs = (Array.isArray(result) ? result : [result]) as Rollup.RollupOutput[];
  const notices = outputs
    .flatMap(({ output }) => output)
    .find((file) => file.type === 'asset' && file.fileName === NOTICES_FILE);
  if (notices?.type !== 'asset') {
    throw new Error(`The build emitted no ${NOTICES_FILE}; was it run from frontend/, with build.license set?`);
  }
  return typeof notices.source === 'string' ? notices.source : new TextDecoder().decode(notices.source);
}

/**
 * The stylesheet ships Tailwind's preflight, which is normalize.css and modern-normalize under their
 * own MIT licence, with copyright holders of its own beside it in `lib/css/`. Tailwind's package
 * licence names only Tailwind Labs, so it alone would leave those holders' notice out of every copy.
 */
describe('the third-party notices', () => {
  it(
    'GivenTheProductionBuild_WhenItWritesTheNotices_ThenTheyCarryTailwindsLicenceAndItsPreflightsLicence',
    async () => {
      const notices = await buildNotices();

      expect.soft(notices, "Tailwind's package licence").toContain(tailwindLicence.trim());
      expect.soft(notices, "the licence of Tailwind's preflight, lib/css/LICENSE").toContain(preflightLicence.trim());
    },
    BUILD_TIMEOUT_MS,
  );
});
