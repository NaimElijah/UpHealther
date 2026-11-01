#!/usr/bin/env node
/**
 * Runs the backend's Maven wrapper in `backend/` with the arguments it is given, and exits with Maven's
 * status, or 1 when Maven is killed by a signal. Run it as `node scripts/mvnw.mjs <maven arguments>`; the
 * root's `test:backend` and `generate:wire-types` do.
 *
 * On Windows an argument containing whitespace or one of `"&|<>^%!()` is refused with an `Error` before
 * anything runs, which Node leaves uncaught: a stack trace and exit status 1. `mvnw.cmd` calls
 * `powershell`, so it has to be on `PATH`.
 *
 * The root `package.json` cannot call the wrapper directly, because npm runs a script through `cmd.exe`
 * on Windows and through `sh` everywhere else, and no one spelling works in both. `cmd.exe` reads
 * `./mvnw` as a command named `.`. `sh` never looks in the current directory, so a bare `mvnw` is not
 * found. Where `NoDefaultCurrentDirectoryInExePath` is set, `cmd.exe` stops looking there too, so only
 * an explicit `.\mvnw.cmd` is found. See `docs/ADRs/ADR-044-one-root-package-json-runs-setup-start-and-test.md`.
 *
 * Node runs a `.cmd` only through a shell (CVE-2024-27980), and passing an argument array with
 * `shell: true` is deprecated (DEP0190), because the shell re-parses whatever the arguments contain. So
 * on Windows the command line is one string, and an argument `cmd.exe` would read as syntax is refused
 * before anything runs.
 */
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const BACKEND = fileURLToPath(new URL('../backend/', import.meta.url));

/**
 * Whitespace, or a character `cmd.exe` treats as quoting, piping, redirection, escaping or expansion.
 * Parentheses too: `mvnw.cmd` expands its arguments inside an `IF … ( … )` block, where a `)` ends the
 * block early.
 */
const UNSAFE_FOR_CMD = /[\s"&|<>^%!()]/;

const args = process.argv.slice(2);

function run() {
  if (process.platform !== 'win32') {
    return spawnSync('./mvnw', args, { cwd: BACKEND, stdio: 'inherit' });
  }
  const unsafe = args.find((arg) => UNSAFE_FOR_CMD.test(arg));
  if (unsafe !== undefined) {
    throw new Error(
      `refusing to pass ${JSON.stringify(unsafe)} to cmd.exe: it contains whitespace or one of "&|<>^%!()`,
    );
  }
  return spawnSync(['.\\mvnw.cmd', ...args].join(' '), { cwd: BACKEND, stdio: 'inherit', shell: true });
}

const result = run();
if (result.error) {
  throw result.error;
}
// A child killed by a signal has no status. It did not succeed, so it must not exit 0.
process.exit(result.status ?? 1);
