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
 * Whether the script on disk uses CRLF line endings.
 *
 * A CSP hash covers the script's bytes exactly, so line endings are part of it — and the Docker image
 * is built from the working tree, not from a fresh checkout. An earlier version of this file
 * normalised to LF before hashing, which made the test pass against bytes nobody serves: the image
 * was built with CRLF, the browser hashed CRLF, the digest did not match, and the boot script was
 * silently blocked. That was caught by serving the page and hashing what came back — not here.
 *
 * So the hash is now taken over the bytes as they are, and this reports the mismatch as what it
 * actually is: a file that has to be LF and is not. `.gitattributes` declares `* text=auto eol=lf`,
 * so it only happens in a working tree that predates that, or an editor that ignores it.
 */
export const BOOT_SCRIPT_HAS_CRLF: boolean = BOOT_SCRIPT.includes('\r\n');

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
