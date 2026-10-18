#!/usr/bin/env node
/**
 * Runs a Python 3 script under whichever name this machine gives the interpreter, and exits with its
 * status.
 *
 * The name differs by platform. CI's Ubuntu runner has `python3`. A python.org install on Windows has
 * `python` or the `py` launcher. There, `python3` is often the Microsoft Store's alias, which may
 * install a different Python, or print a message and fail. So each candidate is asked for its version
 * first, and the first that answers as a Python 3 runs the script.
 * See `docs/ADRs/ADR-044-one-root-package-json-runs-setup-start-and-test.md`.
 */
import { spawnSync } from 'node:child_process';

/** Tried in order. `py -3` is the launcher asked for its newest Python 3. */
const CANDIDATES = [['python3'], ['python'], ['py', '-3']];

/** Long enough for a cold interpreter start, short enough that a prompting stub cannot hang the gate. */
const PROBE_TIMEOUT_MS = 10_000;

/** Older interpreters print their version to stderr and newer ones to stdout, so both are read. */
function isPython3([command, ...prefix]) {
  const probe = spawnSync(command, [...prefix, '--version'], {
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
    timeout: PROBE_TIMEOUT_MS,
  });
  return probe.status === 0 && /^Python 3\./.test(`${probe.stdout}${probe.stderr}`.trim());
}

const interpreter = CANDIDATES.find(isPython3);
if (interpreter === undefined) {
  throw new Error(`no Python 3 interpreter found; tried ${CANDIDATES.map((c) => c.join(' ')).join(', ')}`);
}

const [command, ...prefix] = interpreter;
const result = spawnSync(command, [...prefix, ...process.argv.slice(2)], { stdio: 'inherit' });
if (result.error) {
  throw result.error;
}
// A child killed by a signal has no status. It did not succeed, so it must not exit 0.
process.exit(result.status ?? 1);
