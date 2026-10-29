import js from '@eslint/js';
import { defineConfig, globalIgnores } from 'eslint/config';
import reactHooks from 'eslint-plugin-react-hooks';
import { reactRefresh } from 'eslint-plugin-react-refresh';
import globals from 'globals';
import tseslint from 'typescript-eslint';

/**
 * The frontend's lint configuration, run by `npm run lint` with `--max-warnings 0` in CI.
 *
 * `.ts` and `.tsx` files get the presets below. The `.js` and `.mjs` files are Node's: this one, the
 * PostCSS, Prettier and Tailwind configs, `scripts/check-colours.mjs`, and the repository's own
 * `scripts/*.mjs`, which the gate runs. They get ESLint's `recommended` with Node's globals. ESLint
 * lints nothing above this file's directory, so `npm run lint:scripts` runs this configuration from
 * the repository root to reach the latter. See
 * `docs/ADRs/ADR-056-the-javascript-files-are-linted-too.md`.
 *
 * Each preset is taken whole, and a finding is fixed rather than switched off. react-hooks'
 * `recommended` includes the React Compiler's rules although the compiler is not used. See
 * `docs/ADRs/ADR-035-the-frontend-lints-with-eslint-10-and-a-flat-config.md`.
 */
export default defineConfig([
  globalIgnores(['dist', 'coverage']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      reactHooks.configs.flat.recommended,
      // Vite's preset: `allowConstantExport` as before, plus `allowCompoundComponents`, which
      // `@vitejs/plugin-react` has supported since 4.7.
      reactRefresh.configs.vite(),
    ],
    languageOptions: {
      ecmaVersion: 2020,
      globals: globals.browser,
    },
  },
  {
    files: ['**/*.{js,mjs}'],
    extends: [js.configs.recommended],
    languageOptions: {
      globals: globals.node,
    },
  },
]);
