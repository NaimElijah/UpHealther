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
 * `scripts/*.mjs`, which the gate runs. They get ESLint's `recommended` with Node's built-in globals,
 * not `globals.node`: every one is an ES module, which has no `require` or `__dirname`, so a use of
 * either is reported rather than declared. ESLint lints nothing above this file's directory, so
 * `npm run lint:scripts` runs this configuration from the repository root to reach the latter. See
 * `docs/ADRs/ADR-056-the-javascript-files-are-linted-too.md`.
 *
 * Each preset is taken whole, and a finding is fixed rather than switched off. react-hooks'
 * `recommended` includes the React Compiler's rules although the compiler is not used. See
 * `docs/ADRs/ADR-035-the-frontend-lints-with-eslint-10-and-a-flat-config.md`.
 *
 * After the presets, core ESLint's `no-restricted-imports` enforces two kinds of rule on the production
 * files of `src/`. One is the layering: a folder imports only from the layers below it. The other is the
 * single-owner imports, the modules only one place may import, which apply to every file of `src/`, a
 * folder `LAYERS` does not name included. `no-restricted-globals` keeps `fetch`, `XMLHttpRequest` and
 * `WebSocket` inside `src/api/`, and `no-restricted-syntax` refuses, inside `src/api/`, a client call whose
 * path is a template with a value interpolated into it, which must go through `apiPath` (NFR-58). Tests,
 * `src/test/` and `src/setupTests.ts` are exempt, because a test may compose a provider with the view
 * that reads it. `src/test/importRules.test.ts` lints
 * representative modules through this file, so a block moved out of its place fails a test. See
 * `docs/ADRs/ADR-061-the-frontends-stack-and-layering.md`.
 */

/** `src/`'s layers, lowest first. Folders in one entry are one layer, and may import each other. */
const LAYERS = [['types'], ['api'], ['lib'], ['contexts', 'hooks'], ['components'], ['pages'], ['router']];

/** The order as a reader meets it in a lint message, the entry modules at the top. */
const ORDER = 'types <- api <- lib <- contexts + hooks <- components <- pages <- router <- App.tsx and main.tsx';

/** Not production code, so neither the layering nor the single-owner imports apply. */
const TEST_FILES = ['**/*.test.{ts,tsx}', 'src/test/**', 'src/setupTests.ts'];

/**
 * The imports one place owns. A block lists the keys it is the owner of, and every other block gets the
 * restriction. `paths` match a package by its exact name, and a pattern beside it any subpath of the
 * package. A module of `src/` is matched by its last segment, with or without an extension, so
 * `./a/../b/` segments on the way to it change nothing.
 */
const SINGLE_OWNER = {
  axios: {
    paths: [
      {
        name: 'axios',
        message:
          'Single owner (ADR-061, ADR-015): only src/api/ imports axios. Every HTTP call goes through the one ' +
          'instance in api/client.ts, whose interceptors carry the session; call an api/ module instead.',
      },
    ],
    patterns: [
      {
        regex: '^axios/',
        message:
          'Single owner (ADR-061, ADR-015): only src/api/ imports axios. Every HTTP call goes through the one ' +
          'instance in api/client.ts, whose interceptors carry the session; call an api/ module instead.',
      },
    ],
  },
  stomp: {
    paths: [
      {
        name: '@stomp/stompjs',
        message:
          'Single owner (ADR-061): only api/notificationSocket.ts imports @stomp/stompjs. Open the socket with ' +
          'openNotificationSocket, so the live notifications stay one transport.',
      },
    ],
    patterns: [
      {
        regex: '^@stomp/stompjs/',
        message:
          'Single owner (ADR-061): only api/notificationSocket.ts imports @stomp/stompjs. Open the socket with ' +
          'openNotificationSocket, so the live notifications stay one transport.',
      },
    ],
  },
  generated: {
    patterns: [
      {
        regex: '(^|/)api\\.generated(\\.(js|jsx|ts|tsx))?$',
        message:
          'Single owner (ADR-053): only types/index.ts imports types/api.generated. Import the names it gives ' +
          'the wire types from types/ instead.',
      },
    ],
  },
  tokenStore: {
    patterns: [
      {
        regex: '^\\.\\.?/(.*/)?tokenStore(\\.(js|jsx|ts|tsx))?$',
        message:
          'Single owner (ADR-015): only src/api/ and contexts/AuthContext.tsx import api/tokenStore. The access ' +
          'token stays in memory and reaches no component; call through api/ instead.',
      },
    ],
  },
};

/**
 * The `no-restricted-imports` block for some files of one layer.
 *
 * Flat config does not merge two `no-restricted-imports` entries that match one file: the last wins. So
 * each block carries its files' whole set, the layering and every single-owner import they do not own.
 *
 * @param files  the globs the block applies to, under `src/`
 * @param layer  the index into `LAYERS` of the files' layer, or `LAYERS.length` for the entry modules
 * @param owns   the `SINGLE_OWNER` keys these files are the owner of
 */
function restrictImports({ files, layer, owns = [] }) {
  const above = LAYERS.slice(layer + 1).flat();
  const own = layer < LAYERS.length ? LAYERS[layer].join(' + ') : 'App.tsx and main.tsx';
  // The entry modules sit above every layer, and nothing sits above them.
  const upward = [
    ...(above.length > 0 ? [`^(\\.\\./)+(${above.join('|')})(/|$)`] : []),
    '^(\\.\\./)+(App|main)(\\.tsx)?$',
  ];
  const layering =
    layer < LAYERS.length
      ? [
          {
            regex: upward.join('|'),
            message:
              `Layering (ADR-061): ${own} imports only from the layers below it, never from one above. The order ` +
              `is ${ORDER}. Move what both sides need down a layer.`,
          },
        ]
      : [];
  const restricted = Object.entries(SINGLE_OWNER)
    .filter(([key]) => !owns.includes(key))
    .map(([, restriction]) => restriction);
  const paths = restricted.flatMap((r) => r.paths ?? []);
  const patterns = [...layering, ...restricted.flatMap((r) => r.patterns ?? [])];
  return {
    files,
    ignores: TEST_FILES,
    rules: {
      'no-restricted-imports': [
        'error',
        { ...(paths.length > 0 && { paths }), ...(patterns.length > 0 && { patterns }) },
      ],
    },
  };
}

/** The globals that make a request; only `src/api/` uses them, so that every request carries the session. */
const REQUEST_GLOBALS = ['fetch', 'XMLHttpRequest', 'WebSocket'].map((name) => ({
  name,
  message:
    'Single owner (ADR-061, ADR-015): only src/api/ makes a request. Call an api/ module, whose requests go ' +
    'through the one axios instance and carry the session.',
}));

/**
 * A call to the shared client whose path is a template with something interpolated into it (NFR-58). An id
 * put into a path as it is can climb out of its segment, so `src/api/` builds every such path with the
 * `apiPath` tag, which encodes each value. A tagged template is a different node, so `apiPath` passes.
 */
const BARE_TEMPLATE_PATH = {
  selector:
    "CallExpression[callee.type='MemberExpression'][callee.object.name='client']" +
    '[callee.property.name=/^(get|post|put|patch|delete)$/]' +
    "[arguments.0.type='TemplateLiteral'][arguments.0.expressions.length>0]",
  message:
    'NFR-58: build an API path with the apiPath tag (api/apiPath.ts), which encodes every interpolated ' +
    'value as one segment, never with a bare template.',
};

/** The index into `LAYERS` of a folder of `src/`. */
const layerOf = (folder) => LAYERS.findIndex((folders) => folders.includes(folder));

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
      globals: globals.nodeBuiltin,
    },
  },
  {
    files: ['src/**/*.{ts,tsx}'],
    ignores: [...TEST_FILES, 'src/api/**'],
    rules: { 'no-restricted-globals': ['error', ...REQUEST_GLOBALS] },
  },
  {
    files: ['src/api/**/*.{ts,tsx}'],
    ignores: TEST_FILES,
    rules: { 'no-restricted-syntax': ['error', BARE_TEMPLATE_PATH] },
  },
  // First, so that every folder's block below replaces it for that folder's files: a folder LAYERS does not
  // name still gets every single-owner restriction, though no layer.
  restrictImports({ files: ['src/**/*.{ts,tsx}'], layer: LAYERS.length }),
  // Each owner's block comes after its folder's, so that for the owning file it is the one that applies.
  restrictImports({ files: ['src/types/**/*.{ts,tsx}'], layer: layerOf('types') }),
  restrictImports({ files: ['src/types/index.ts'], layer: layerOf('types'), owns: ['generated'] }),
  restrictImports({ files: ['src/api/**/*.{ts,tsx}'], layer: layerOf('api'), owns: ['axios', 'tokenStore'] }),
  restrictImports({
    files: ['src/api/notificationSocket.ts'],
    layer: layerOf('api'),
    owns: ['axios', 'tokenStore', 'stomp'],
  }),
  restrictImports({ files: ['src/lib/**/*.{ts,tsx}'], layer: layerOf('lib') }),
  restrictImports({ files: ['src/{contexts,hooks}/**/*.{ts,tsx}'], layer: layerOf('contexts') }),
  restrictImports({ files: ['src/contexts/AuthContext.tsx'], layer: layerOf('contexts'), owns: ['tokenStore'] }),
  restrictImports({ files: ['src/components/**/*.{ts,tsx}'], layer: layerOf('components') }),
  restrictImports({ files: ['src/pages/**/*.{ts,tsx}'], layer: layerOf('pages') }),
  restrictImports({ files: ['src/router/**/*.{ts,tsx}'], layer: layerOf('router') }),
  restrictImports({ files: ['src/*.{ts,tsx}'], layer: LAYERS.length }),
]);
