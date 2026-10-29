import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';
import { defineConfig, type Plugin } from 'vite';
import react from '@vitejs/plugin-react';

// The bundle carries copies of MIT and Apache-2.0 code, and both licences require their notices to
// travel with every copy, so the build ships them beside the bundle:
// docs/ADRs/ADR-059-the-bundle-ships-its-third-party-notices.md.
const THIRD_PARTY_NOTICES_FILE = 'THIRD-PARTY-NOTICES.txt';

const require = createRequire(import.meta.url);

/**
 * Appends Tailwind CSS's licence to the notices Vite writes, in the same `## name - version (licence)`
 * shape. Tailwind's preflight is copied into the stylesheet, but Vite lists only the packages whose
 * modules end up in a JS chunk, and minification strips the banner Tailwind puts in the CSS.
 *
 * Fails the build when the notices file is missing or Tailwind's licence cannot be read, so the
 * bundle never ships without them.
 */
function tailwindNotice(): Plugin {
  return {
    name: 'uphealther:tailwind-notice',
    apply: 'build',
    generateBundle: {
      // Vite's own `vite:license` emits the notices file in this hook, and runs after every user
      // plugin, `enforce: 'post'` ones included; only the hook order puts this one after it.
      order: 'post',
      handler(_options, bundle) {
        const notices = bundle[THIRD_PARTY_NOTICES_FILE];
        if (notices?.type !== 'asset' || typeof notices.source !== 'string') {
          this.error(`${THIRD_PARTY_NOTICES_FILE} is not in the bundle as text; is build.license set?`);
        }
        let entry: string;
        try {
          const manifestPath = require.resolve('tailwindcss/package.json');
          const { version, license } = JSON.parse(readFileSync(manifestPath, 'utf-8')) as {
            version: string;
            license: string;
          };
          const text = readFileSync(join(dirname(manifestPath), 'LICENSE'), 'utf-8').trim();
          entry = `\n## tailwindcss - ${version} (${license})\n\n${text}\n`;
        } catch (cause) {
          this.error(`Cannot read Tailwind CSS's licence for ${THIRD_PARTY_NOTICES_FILE}: ${String(cause)}`);
        }
        notices.source += entry;
      },
    },
  };
}

export default defineConfig({
  plugins: [react(), tailwindNotice()],
  build: {
    // Vite 5's default, kept when Vite 7 raised it to `baseline-widely-available`, which Vite moves
    // each major (Chrome 111 and Safari 16.4 in Vite 8). Nothing states which browsers the SPA
    // supports, so an upgrade should not decide it: ADR-036, and #250 for the decision itself.
    target: ['es2020', 'edge88', 'firefox78', 'chrome87', 'safari14'],
    license: { fileName: THIRD_PARTY_NOTICES_FILE },
  },
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      // WebSocket endpoint for real-time notifications.
      '/ws': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        ws: true,
      },
    },
  },
});
