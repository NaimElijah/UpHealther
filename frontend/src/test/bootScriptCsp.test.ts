// Runs in the suite's default jsdom environment even though it renders nothing and only reads two
// files. The shared setupTests.ts clears localStorage and the <html> class before every test, so a
// file that opts into the node environment fails in setup before it reaches its first assertion —
// and making that setup environment-aware for the sake of one test would trade a real isolation
// guarantee for an imaginary saving.
import { describe, expect, it } from 'vitest';
import nginxConf from '../../nginx.conf?raw';
import { BOOT_SCRIPT, BOOT_SCRIPT_HAS_CRLF, cspHashOf } from './inlineBootScript';

/** The `add_header Content-Security-Policy "…" always;` line, with the policy captured. */
const CSP_DIRECTIVE = /add_header\s+Content-Security-Policy\s+"([^"]*)"\s*(always)?\s*;/;

/** Every `add_header` in the file, with whatever follows the value captured. */
const ADD_HEADER = /add_header\s+(\S+)\s+"[^"]*"\s*([^;]*);/g;

/**
 * The CSP and the inline script it has to permit — two files that must agree, with nothing checking
 * them at runtime except a browser that silently refuses to run the script.
 *
 * The boot script sets the theme before the first paint and therefore has to be inline; a hash in
 * `script-src` is what allows exactly that script and nothing else. The failure mode when they drift
 * is quiet and specific: the browser blocks the script, the page paints light, React corrects it a
 * frame later, and the only symptom is the flash of wrong theme that the script existed to prevent —
 * on production only, because the dev server serves no CSP at all.
 *
 * So the hash is recomputed here from the shipped file rather than trusted. Editing the boot script
 * without updating the policy fails the build, which is the only moment anybody would notice.
 */
describe('the content security policy', () => {
  const policy = (() => {
    const match = nginxConf.match(CSP_DIRECTIVE);
    if (!match) {
      throw new Error('No Content-Security-Policy add_header found in nginx.conf.');
    }
    return { value: match[1], hasAlways: match[2] === 'always' };
  })();

  it('GivenTheShippedIndexHtml_WhenItsLineEndingsAreRead_ThenTheyAreLf', () => {
    // The image is built from the working tree, so whatever is on disk here is what a browser hashes.
    // CRLF makes the digest below describe bytes nobody serves, and the only symptom in production is
    // the flash of wrong theme the boot script exists to prevent.
    expect(BOOT_SCRIPT_HAS_CRLF)
      .toBe(false);
  });

  it('GivenTheShippedBootScript_WhenItsHashIsComputed_ThenThePolicyAlreadyPermitsThatExactScript',
    async () => {
      const expected = await cspHashOf(BOOT_SCRIPT);

      expect(policy.value).toContain(expected);
    });

  it('GivenThePolicy_WhenScriptSourcesAreRead_ThenNothingUnsafeIsPermitted', () => {
    // 'unsafe-inline' in script-src would permit the boot script — and every script an attacker
    // manages to inject alongside it, which is the entire thing a CSP is for.
    expect(policy.value).not.toContain('unsafe-inline');
    expect(policy.value).not.toContain('unsafe-eval');
  });

  it('GivenThePolicy_WhenItIsRead_ThenItClosesTheDirectionsADefaultWouldLeaveOpen', () => {
    expect(policy.value).toContain("default-src 'self'");
    expect(policy.value).toContain("object-src 'none'");
    expect(policy.value).toContain("base-uri 'self'");
    expect(policy.value).toContain("frame-ancestors 'none'");
  });

  it('GivenTheSpaOpensAWebSocket_WhenConnectSourcesAreRead_ThenTheSocketIsPermitted', () => {
    // Without this the STOMP connection to /ws is blocked and live notifications stop, while every
    // REST call keeps working — a failure that looks like a backend problem and is not.
    expect(policy.value).toContain('ws://$http_host');
    expect(policy.value).toContain('wss://$http_host');
  });

  it('GivenEverySecurityHeader_WhenItIsDeclared_ThenItIsMarkedAlways', () => {
    // Without `always`, nginx omits the header on error responses — so the 404 and 5xx pages, the ones
    // most likely to be rendering something unexpected, would ship with no policy at all.
    const headers = [...nginxConf.matchAll(ADD_HEADER)];

    expect(headers.length).toBeGreaterThan(0);
    expect(headers.map(([, name, suffix]) => `${name}:${suffix.trim()}`))
      .toSatisfy((declared: string[]) => declared.every((entry) => entry.endsWith(':always')));
  });

  it('GivenTheDocumentResponse_WhenItsHeadersAreListed_ThenTheHardeningOnesAreAllThere', () => {
    const names = [...nginxConf.matchAll(ADD_HEADER)].map(([, name]) => name);

    expect(names).toContain('X-Content-Type-Options');
    expect(names).toContain('X-Frame-Options');
    expect(names).toContain('Referrer-Policy');
    expect(names).toContain('Permissions-Policy');
    expect(policy.hasAlways).toBe(true);
  });

  it('GivenTheServer_WhenItAnswers_ThenItDoesNotAdvertiseItsVersion', () => {
    // It tells an attacker which advisories to read and tells a user nothing.
    expect(nginxConf).toMatch(/server_tokens\s+off\s*;/);
  });
});
