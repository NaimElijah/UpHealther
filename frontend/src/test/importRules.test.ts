import { beforeAll, describe, expect, it } from 'vitest';
import { ESLint } from 'eslint';

/**
 * The import rules of `eslint.config.js` (ADR-061), and its one syntax rule, the API layer's bare template
 * path (NFR-58), run through the real configuration.
 *
 * Flat config keeps only the last `no-restricted-imports` entry that matches a file, so the rules hold
 * only while each block keeps its place: an owner's block after its folder's, the catch-all before both.
 * A reordering drops a restriction or an exemption and lint stays green, so these lint representative
 * modules, written nowhere on disk, and say what each must produce.
 *
 * ESLint finds `eslint.config.js` from the linted path, resolved against the working directory, which is
 * `frontend/` wherever `npm test` runs Vitest. It runs in the suite's jsdom environment, because the
 * shared setup resets the DOM before every test; ESLint needs only Node's built-ins, which it keeps.
 */

const eslint = new ESLint();

/** The rules this file is about; anything else a snippet trips, an unused import say, is not. */
const RULES = new Set(['no-restricted-imports', 'no-restricted-globals', 'no-restricted-syntax']);

/** What the two rules report for `code` as if it were the module at `file`, under `src/`. */
async function restrictionsIn(file: string, code: string): Promise<string[]> {
  const [result] = await eslint.lintText(code, { filePath: `src/${file}` });
  return result.messages.filter((m) => m.ruleId !== null && RULES.has(m.ruleId)).map((m) => m.message);
}

/** A module that only imports `source`, and uses what it imports, so no other rule has a say. */
const importing = (source: string) => `import * as imported from '${source}';\n\nexport default imported;\n`;

/** How long the first lint may take: it loads the configuration and its plugins, which takes seconds. */
const CONFIG_LOAD_MS = 60_000;

describe('the import rules', () => {
  beforeAll(async () => {
    await eslint.lintText('', { filePath: 'src/zz.ts' });
  }, CONFIG_LOAD_MS);

  describe('the layering', () => {
    it.each([
      ['types/zz.ts', '../api/client', 'types'],
      ['api/zz.ts', '../lib/queryClient', 'api'],
      ['lib/zz.ts', '../contexts/authContextValue', 'lib'],
      ['lib/zz.ts', '../hooks/useAuth', 'lib'],
      ['contexts/zz.ts', '../components/ui/Modal', 'contexts + hooks'],
      ['hooks/zz.ts', '../components/ui/Modal', 'contexts + hooks'],
      ['components/ui/zz.tsx', '../../pages/LoginPage', 'components'],
      ['pages/zz.tsx', '../router', 'pages'],
      ['router/zz.tsx', '../App', 'router'],
    ])('GivenAModuleIn %s_WhenItImports %s_ThenTheLayeringRefusesIt', async (file, source, layer) => {
      expect(await restrictionsIn(file, importing(source))).toEqual([
        expect.stringContaining(`Layering (ADR-061): ${layer} imports only from the layers below it`),
      ]);
    });

    it.each([
      ['hooks/zz.ts', '../contexts/authContextValue'],
      ['contexts/zz.ts', '../hooks/useAuth'],
      ['router/zz.tsx', '../pages/LoginPage'],
      ['components/zz.tsx', '../lib/localDate'],
      ['zz.tsx', './router'],
    ])('GivenAModuleIn %s_WhenItImports %s_ThenTheLayeringAllowsIt', async (file, source) => {
      expect(await restrictionsIn(file, importing(source))).toEqual([]);
    });
  });

  describe('the single owners', () => {
    it.each([
      ['pages/zz.tsx', 'axios'],
      ['lib/zz.ts', 'axios'],
      ['api/zz.ts', '@stomp/stompjs'],
      ['contexts/zz.ts', '@stomp/stompjs'],
      ['api/zz.ts', '../types/api.generated'],
      ['types/zz.ts', './api.generated'],
      ['pages/zz.tsx', '../types/api.generated'],
      ['components/zz.tsx', '../api/tokenStore'],
      ['contexts/NotificationProvider.tsx', '../api/tokenStore'],
      ['zz.tsx', './api/tokenStore'],
    ])('GivenANonOwnerIn %s_WhenItImports %s_ThenItIsRefused', async (file, source) => {
      expect(await restrictionsIn(file, importing(source))).toEqual([expect.stringContaining('Single owner')]);
    });

    it.each([
      ['api/client.ts', 'axios'],
      ['api/apiError.ts', 'axios'],
      ['api/notificationSocket.ts', '@stomp/stompjs'],
      ['types/index.ts', './api.generated'],
      ['api/session.ts', './tokenStore'],
      ['contexts/AuthContext.tsx', '../api/tokenStore'],
    ])('GivenTheOwner %s_WhenItImports %s_ThenItIsAllowed', async (file, source) => {
      expect(await restrictionsIn(file, importing(source))).toEqual([]);
    });

    it.each([
      'axios/unsafe/core/AxiosHeaders.js',
      'axios/dist/browser/axios.cjs',
      '@stomp/stompjs/esm6/index.js',
      '../api/tokenStore.js',
      '../api/tokenStore.ts',
      '../api/./tokenStore',
      '../api/../api/tokenStore',
      '../types/api.generated.js',
      '../types/api.generated.ts',
      '../types/./api.generated',
    ])('GivenAPageImportingAnOwnedModuleSpelt %s_WhenItIsLinted_ThenItIsRefused', async (source) => {
      expect(await restrictionsIn('pages/zz.tsx', importing(source))).toEqual([
        expect.stringContaining('Single owner'),
      ]);
    });
  });

  describe('a request made around the API layer', () => {
    it.each(['fetch', 'XMLHttpRequest', 'WebSocket'])(
      'GivenAPageUsingTheGlobal %s_WhenItIsLinted_ThenItIsRefused',
      async (global) => {
        expect(await restrictionsIn('pages/zz.tsx', `export const zz = ${global};\n`)).toEqual([
          expect.stringContaining('Single owner (ADR-061, ADR-015)'),
        ]);
      },
    );

    it('GivenTheApiLayerUsingFetch_WhenItIsLinted_ThenItIsAllowed', async () => {
      expect(await restrictionsIn('api/zz.ts', 'export const zz = fetch;\n')).toEqual([]);
    });
  });

  describe('a path built in the API layer', () => {
    /** A module of `api/` that calls the client with `path` as its first argument. */
    const calling = (method: string, path: string) =>
      `import client from './client';\nimport { apiPath } from './apiPath';\n\n` +
      `export const zz = (id: string) => client.${method}(${path});\nexport { apiPath };\n`;

    it.each(['get', 'post', 'put', 'patch', 'delete'])(
      'GivenAnApiModuleCalling %s_WhenItsPathIsABareTemplate_ThenLintRefusesIt',
      async (method) => {
        // NFR-58: an id interpolated as it is can climb out of its segment; apiPath encodes it.
        expect(await restrictionsIn('api/zz.ts', calling(method, '`/api/upgrades/${id}`'))).toEqual([
          expect.stringContaining('apiPath'),
        ]);
      },
    );

    it.each([
      ['an apiPath template', 'apiPath`/api/upgrades/${id}`'],
      ['a template with nothing interpolated', '`/api/upgrades`'],
      ['a constant', "'/api/upgrades'"],
    ])('GivenAnApiModulePassing %s_WhenItIsLinted_ThenItIsAllowed', async (_, path) => {
      expect(await restrictionsIn('api/zz.ts', calling('get', path))).toEqual([]);
    });
  });

  describe('a folder no block names', () => {
    it('GivenANewFolder_WhenAModuleInItImportsAxios_ThenTheOwnerRuleStillRefusesIt', async () => {
      expect(await restrictionsIn('newfolder/Zed.tsx', importing('axios'))).toEqual([
        expect.stringContaining('Single owner'),
      ]);
    });

    it('GivenANewFolder_WhenAModuleInItImportsAPage_ThenNoLayerRefusesIt', async () => {
      // A folder has no place in the order until LAYERS names it; the owner rules apply regardless.
      expect(await restrictionsIn('newfolder/Zed.tsx', importing('../pages/LoginPage'))).toEqual([]);
    });
  });

  describe('a test', () => {
    it.each([
      ['lib/zz.test.ts', '../pages/LoginPage'],
      ['pages/zz.test.tsx', 'axios'],
      ['test/zz.ts', '../api/tokenStore'],
    ])('GivenATestIn %s_WhenItImports %s_ThenNothingRefusesIt', async (file, source) => {
      expect(await restrictionsIn(file, importing(source))).toEqual([]);
    });
  });
});
