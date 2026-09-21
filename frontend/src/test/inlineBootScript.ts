// Vite's `?raw` rather than `node:fs`: it resolves the file the same way the build does, and it keeps
// the app's `tsconfig.json` free of Node types — see ADR-004.
import indexHtml from '../../index.html?raw';

/**
 * The pre-paint theme script from `index.html`, shared by the two tests that need it.
 *
 * Two different tests read this script for two unrelated reasons — one executes it to prove it agrees
 * with `ThemeProvider`, the other hashes it to prove the CSP in `nginx.conf` still permits it — and both
 * break in the same way if it moves. Extracting it once means a change to `index.html` produces one
 * clear failure with one explanation, rather than two puzzling ones.
 */

/** Matches a classic inline script: no `src`, no `type` — as opposed to the module that loads the bundle. */
const INLINE_CLASSIC_SCRIPT = /<script(?![^>]*\b(?:src|type)=)[^>]*>([\s\S]*?)<\/script>/g;

/**
 * The inline script's contents, exactly as they appear in the file.
 *
 * @throws Error if `index.html` no longer holds exactly one classic inline script, which means both
 *         tests using this are now checking something that is not there
 */
export const BOOT_SCRIPT: string = (() => {
  const inline = [...indexHtml.matchAll(INLINE_CLASSIC_SCRIPT)];

  if (inline.length !== 1) {
    throw new Error(
      `Expected exactly one classic inline script in index.html, found ${inline.length}. ` +
        'If the boot script moved or was split, the tests reading it need to follow it.',
    );
  }

  return inline[0][1];
})();

/**
 * The same script with LF line endings — the bytes a browser will actually hash.
 *
 * A CSP hash covers the script's text exactly, so the line endings are part of it. `.gitattributes`
 * declares `* text=auto eol=lf`, so the repository and every fresh checkout — including the one the
 * Docker image is built from — hold LF. A working tree that predates that declaration can still be
 * CRLF on disk, and hashing those bytes would produce a digest that matches nothing anybody serves.
 */
export const BOOT_SCRIPT_AS_SERVED: string = BOOT_SCRIPT.replace(/\r\n/g, '\n');

/**
 * The CSP source expression for a script, as `script-src` spells it.
 *
 * @param script the script text, with the line endings it will be served with
 * @returns the quoted `sha256-…` expression
 */
export async function cspHashOf(script: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(script));
  // btoa rather than Buffer: ADR-004 keeps Node types out of tsconfig.json, so the production type
  // check has no Buffer to find. btoa is a DOM global, which is also what the browser computing this
  // hash for real would use.
  const bytes = new Uint8Array(digest);
  return `'sha256-${btoa(String.fromCharCode(...bytes))}'`;
}
