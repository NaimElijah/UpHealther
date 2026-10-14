import js from '@eslint/js';
import { defineConfig, globalIgnores } from 'eslint/config';
import reactHooks from 'eslint-plugin-react-hooks';
import { reactRefresh } from 'eslint-plugin-react-refresh';
import globals from 'globals';
import tseslint from 'typescript-eslint';

/**
 * The frontend's lint configuration, run by `npm run lint` with `--max-warnings 0` in CI.
 *
 * Only `.ts` and `.tsx` files get rules, as under the `--ext ts,tsx` this replaces. The few `.js` and
 * `.mjs` files (this one, the PostCSS and Tailwind configs, `scripts/`) are matched by nothing here,
 * so ESLint reports nothing for them.
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
]);
