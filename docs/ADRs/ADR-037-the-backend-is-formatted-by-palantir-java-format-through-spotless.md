# ADR-037: The backend is formatted by palantir-java-format through Spotless

- **Status:** Accepted
- **Date:** 2026-10-15
- **Scope:** every Java source under `backend/src/main` and `backend/src/test`; `backend/pom.xml`;
  `.git-blame-ignore-revs`.
- **Issue:** [#161](https://github.com/NaimElijah/UpHealther/issues/161), the backend half. The
  frontend half is [ADR-038](ADR-038-the-frontend-is-formatted-by-prettier.md).

## Context

The backend had no formatter. `.editorconfig` fixed whitespace and line endings, and said nothing
about wrapping, import order or blank lines. Layout was therefore enforced by review or not at all. A
behaviour change also carried reformatting noise whenever two editors' defaults differed. The
repository baseline asks for a formatter that is configured in the repository and checked in CI.

The sources use 4-space indentation, wrap at about 110 columns, and group imports by hand. Any formatter
moves some of that. The question is which one moves the least and still runs cleanly on JDK 21.

Each candidate was run once over an LF-normalised copy of `backend/src` at `f002e14`: 291 files and
27,355 lines. Spotless 3.10.4 drove palantir-java-format. It could not drive google-java-format
1.37.0: every file failed with an `InvocationTargetException`, with or without the exports below, and
Spotless does not report the cause even at `-X`. google-java-format's own CLI drove the other two rows.

| Formatter | Indent | Width | Files changed | Lines added / removed | Of which import lines | Runs on JDK 21 |
|---|---|---|---|---|---|---|
| **palantir-java-format 2.102.0** | 4 | 120 | 251 | +3,797 / −2,881 | 1,695 | Yes, through Spotless, with no flags |
| google-java-format 1.37.0 `--aosp` | 4 | 100 | 268 | +9,915 / −6,094 | 945 | Not through Spotless 3.10.4. Its CLI needs six `--add-exports` for `jdk.compiler` |
| google-java-format 1.37.0 | 2 | 100 | 273 | +22,383 / −20,035 | 1,645 | As above |

google-java-format also **refuses 17 files** ("Imports not contiguous"): their imports carry a
trailing comment, such as `DashboardView`'s `// domain aggregate carried in each bucket` and every
import in `HexagonalArchitectureTest`. Those 17 files are left out of its two rows, so its churn is a
floor. palantir-java-format formats them and keeps the comments.

## Decision

**Format the backend with palantir-java-format 2.102.0, run by the Spotless Maven plugin 3.10.4.**

- **`./mvnw spotless:apply`** rewrites every Java source under `src/main` and `src/test`.
- **`spotless:check` is bound at its default phase, `verify`.** `./mvnw verify` therefore fails on an
  unformatted file, locally exactly as in CI, and CI needs no step of its own.
- **Imports become one block:** static imports first, then every other import in ASCII order, and
  unused ones are removed. That is about a quarter of the churn (1,695 of 6,678 changed lines), because
  the sources grouped imports by hand.
- **Javadoc is not reformatted** (Spotless's `formatJavadoc` stays off). The prose is wrapped by hand,
  as it is everywhere else in the repository.
- **Line endings follow `.gitattributes`,** which is Spotless's default.
- **Both versions are pinned as properties.** A formatter release can move lines. A bump is therefore
  its own `style(backend)` commit, and its SHA joins `.git-blame-ignore-revs`.
- **The one-time reformat is a commit of its own.** It contains only `spotless:apply`'s output, so a
  reviewer can check it by re-running the formatter. `.git-blame-ignore-revs` names it, and GitHub's
  blame skips it. Locally, run `git config blame.ignoreRevsFile .git-blame-ignore-revs` once. The SHA
  survives into `dev` because this repository merges with merge commits; a squash would have created a
  new one.

**New build-time dependencies.** Neither ships in the jar.

| Dependency | Replaces | Licence | Maintenance |
|---|---|---|---|
| Spotless Maven plugin 3.10.4 (DiffPlug) | Layout comments in review | Apache-2.0 | Active; 3.10.4 is the latest release |
| palantir-java-format 2.102.0 (Palantir) | — | Apache-2.0 | Active; 2.102.0 is the latest release |

The existing tools are not enough. `.editorconfig` cannot express wrapping or import order, and ArchUnit
checks dependencies, not layout.

## Consequences

- **Layout is no longer a review topic.** A diff shows what changed, not whose editor touched it.
- **The reformat touches 251 files once.** Every branch open at that moment would conflict. None was
  open when it landed.
- **`git blame` needs `.git-blame-ignore-revs`** to see past the reformat. GitHub reads it
  automatically; a local clone needs the one-time `git config` above.
- **A formatting failure surfaces late in CI.** The check is the last goal of `verify`, so an
  unformatted file fails the job only after the integration tests have run and `failsafe:verify` has
  reported them. The plugin is declared last in `pom.xml` for that reason: goals bound to one phase run
  in declaration order, and a check ahead of `failsafe:verify` would hide a failing integration test
  behind a layout failure. Binding the check earlier would make `./mvnw test` fail on layout too, in the
  middle of a red-green loop. Running `./mvnw spotless:apply` before committing avoids both.
- **An editor should match.** IntelliJ's palantir-java-format plugin applies the same formatter.
  Without it, `spotless:apply` corrects whatever the editor did.
- **A working copy whose files are CRLF fails the check.** The index is LF (`.gitattributes`), but a
  file checked out before `.gitattributes` existed can still be CRLF on disk. `spotless:apply` rewrites
  it to LF, and Git sees no change.

## Alternatives considered

- **google-java-format, Google style.** Rejected. It changes more than six times as many lines as
  palantir-java-format (42,418 against 6,678), because it moves every indented line to 2 spaces. It did
  not run under Spotless 3.10.4 at all, its own CLI needs six `--add-exports`, and it refuses 17 files
  until their import comments are moved.
  - **Revisit:** if palantir-java-format is abandoned upstream, with no release for a Java version the
    backend moves to.
- **google-java-format `--aosp`.** Rejected for the same failure under Spotless, the same flags and the
  same 17 refusals. It changes 2.4 times as many lines, mostly by rewrapping at 100 columns code that
  was written to about 110.
  - **Revisit:** as above.
- **The Eclipse formatter, configured to match today's layout.** Not measured, and rejected. Matching
  the current layout means writing and maintaining a profile of several hundred settings. That moves
  the layout debate from review into an XML file. A formatter whose style is fixed upstream settles it.
  - **Revisit:** never on its own. Only if both formatters above become unusable.
- **Format only changed files (Spotless `ratchetFrom`).** Rejected. It keeps two layouts in the tree
  indefinitely, and every behaviour change also reformats whatever it touches. The one-time reformat is
  cheapest while no branch is in flight.
- **Checkstyle for layout.** Rejected. Once a formatter owns layout, a style linter only duplicates it.
  Bug-pattern checking is a separate question ([#162](https://github.com/NaimElijah/UpHealther/issues/162)).

## When to revisit

- **palantir-java-format cannot parse a Java version the backend adopts,** or stops releasing.
- **A formatter release moves more than a handful of lines.** Decide whether to take the bump, or hold
  the pin, in a commit of its own.
