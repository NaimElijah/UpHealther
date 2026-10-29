# ADR-055: Python is left floating at any Python 3

- **Status:** Accepted
- **Date:** 2026-10-29
- **Extends:** [ADR-030](ADR-030-the-toolchain-is-pinned-in-the-repository.md), whose "Deliberately
  left floating" list it adds to
- **Scope:** `scripts/python.mjs`, the root `package.json`'s `test:diagrams`, the diagrams job in
  `.github/workflows/ci.yml`, and `docs/architecture/arch-diagrams/generate.py`.
- **Issue:** [#336](https://github.com/NaimElijah/UpHealther/issues/336), scan 02 of
  [#334](https://github.com/NaimElijah/UpHealther/issues/334), finding I2.14.

## Context

ADR-030 records every tool that builds or runs the system, pins it, and lists the few it leaves floating
on purpose: the JDK patch `setup-java` resolves, the `ubuntu-latest` runner, and the action tags.

Python is on neither list. One gate runs it: `npm run test:diagrams`, which CI's `Diagrams - Drift check`
job runs, calls `scripts/python.mjs`, and that runs `generate.py --check` under the first of `python3`,
`python` and `py -3` that answers as a Python 3 (ADR-044). So the gate runs on whatever Python 3 the
runner has. On a developer machine it runs on whatever that machine has. The runner's own image changes
under it: run 40603684149 annotates the diagrams job with "The ubuntu-latest label will migrate to
Ubuntu 26 beginning October 19, 2026".

`generate.py` imports only `re`, `sys`, `pathlib` and `__future__`. The newest syntax it uses is
`from __future__ import annotations`, which Python 3.7 introduced. It reads the source tree and writes
Markdown; it builds and ships nothing.

## Decision

**Python is left floating at any Python 3, and `generate.py` stays on the standard library.**

- CI keeps the runner's own `python3`. No `actions/setup-python` step, and no `.python-version` file.
- `scripts/python.mjs` keeps accepting any interpreter that answers `Python 3.`.
- The README keeps asking for "Python 3".

This joins the three entries ADR-030 left floating, for its reason: nothing Python produces is in a
shipped artifact.

## Consequences

- **Nothing to keep current.** No version to bump, no action to pin, and nothing for Dependabot to
  propose.
- **A developer runs the gate on the Python they have.** Windows, macOS and Linux each spell it
  differently, and `python.mjs` already finds it.
- **A runner image can change the interpreter under the gate.** The script uses only the standard
  library and old syntax, so a newer Python 3 is very unlikely to change its output. If it ever does,
  the drift check fails loudly rather than passing wrongly: the check compares the generated files
  with the committed ones.
- **A Python feature newer than the oldest machine's would break that machine's run.** The README does
  not name a minimum.

## Alternatives considered

- **`actions/setup-python` with a pinned version, read from a `.python-version` file.** Rejected for
  now. It would pin CI only, while every developer machine kept its own Python. It would add an action
  to pin and keep current (#299), and a file that `python.mjs` would also have to enforce, to pin
  anything locally. All of that would hold a 397-line, standard-library script to an exact release.
  - **Revisit:** see below.
- **Run the generator in a pinned `python` image,** as the audit and the changelog run theirs
  (ADR-047, ADR-051). Rejected. The diagram check runs first in `npm test`, and needs no Docker today.
  An image would make the fastest gate need Docker, and pull an image on every first run.

## When to revisit

- **`generate.py` needs a package outside the standard library,** or a language feature newer than
  the oldest Python 3 a supported machine has. Pin a version then, in CI and in `python.mjs` together.
- **A runner image change alters the generator's output,** or breaks it.
- **Python starts producing something that ships.**
