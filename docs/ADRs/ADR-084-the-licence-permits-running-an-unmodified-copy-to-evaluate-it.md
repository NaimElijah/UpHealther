# ADR-084: The licence permits running an unmodified copy to evaluate it

- **Status:** Accepted
- **Date:** 2026-11-01
- **Extends:** [ADR-003](ADR-003-proprietary-source-available-licensing.md), whose *Decision* says the
  licence "grants exactly one thing: permission to read, retain a local copy for evaluation, and quote
  short excerpts with credit". It now also grants the permission in `LICENSE` §3.4. The rest of ADR-003
  stands.
- **Scope:** `LICENSE` (the summary, §3.4, the closing paragraph of §3, §4.1, §4.5, and the version
  label, now 1.1) and the licence wording in `README.md`. No code, build or runtime change.
- **Issue:** [#346](https://github.com/NaimElijah/UpHealther/issues/346), scan 12, finding F1, where the
  owner decided on 2026-11-01. Related, and not settled here:
  [#225](https://github.com/NaimElijah/UpHealther/issues/225).

## Context

The README is written for evaluators ([ADR-003](ADR-003-proprietary-source-available-licensing.md)'s
stated goal: the repository is read by prospective employers, collaborators and the technically
curious), and its Quick start tells them to run `docker compose up --build`. The licence contradicted
that.

- **§4.5 forbade running the code.** "You may NOT … deploy, host, execute or operate the Software",
  without written permission from the Author.
- **§3.2 permitted a local copy only "solely for the purpose of reading and evaluating it".** A reader
  could hold the files and read them; running them was not mentioned, and §2 withholds every right not
  expressly granted.
- **§9 ends the permission on any breach.** An evaluator who followed the README's Quick start would
  have breached §4.5 and lost the permission to hold the copy at all.

Two things are true of evaluating this system. A reader who only reads the source cannot see that the
stack comes up healthy, that the interface works, or that the tests the README cites pass; that is
evidence the repository exists to offer. And the risks the licence guards against (reuse in another
product, hosting a competing instance, modification, redistribution, training data) are not made worse
by one person running an unmodified copy on their own machine to look at it.

The licence has not been reviewed by a lawyer ([ADR-003](ADR-003-proprietary-source-available-licensing.md),
*Note*). This record does not change that.

## Decision

**`LICENSE` §3.4 permits a licensee to build and run an unmodified copy of the Software, as the
repository's own build files and documentation direct, on a computer under their own control, solely to
evaluate it.** The permission carries its own limit: the licensee must not deploy it, host it, offer it
as a service, or make it reachable by any other person, or by any other computer over a network.

- **§4.1 and §4.5 carve the permission out** ("other than as permitted by Section 3.4"), the way §4.2
  already carves out Section 3, so neither "use in a system" nor "execute" can be read against it. The
  summary at the head of the file and the closing paragraph of §3 say the same. A reader who stops at
  the summary is not misled in either direction.
- **The version label moves to 1.1**, so the text an evaluator accepted can be told from the one before
  this grant.
- **Nothing else is broadened.** Modification (§4.3), redistribution (§4.4), any use in a project,
  product, service or system beyond evaluating (§4.1), presenting the work as one's own (§4.7) and
  training a model on it (§4.8) stay forbidden. Running for any purpose but evaluation stays forbidden.
  So does running a modified copy, which includes building one from edited sources.
- **The README says it.** Its Quick start, Development and Testing sections may tell an evaluator to run
  the stack, and its Licence section and *Source-available, not open source* entry state the grant.
  `LICENSE` governs where they differ.

## Consequences

**What this makes easy.**

- **The README's instructions are lawful to follow.** A stranger can go from clean clone to a running
  stack without first writing to the Author, which is the README's one job.
- **An evaluator can verify the claims.** The tests, the composed stack and the smoke suite are things
  to run, not only to read.

**What this makes hard.**

- **The line between evaluating and using is the reader's to judge.** "Solely to evaluate" has no
  bright edge: leaving a stack running for a week, or building a personal tool on its API, is not
  evaluating, and the licence does not say where the edge lies. §4.1 and §9 are what an evaluator
  who crosses it meets.
- **"Unmodified" excludes experiments.** Changing a line to see what happens is outside §3.4 and needs
  written permission under §8.
- **A native backend run can break the condition by default.** `./mvnw spring-boot:run` listens on every
  interface while holding the published signing key
  ([#366](https://github.com/NaimElijah/UpHealther/issues/366)), so on a shared network it can be
  reachable by another computer. The composed stack publishes only on `127.0.0.1`. Until #366 is fixed,
  the README's native-run instructions need to say so.
- **The licence is longer, and the summary must keep pace.** Every restriction that mentions running
  now needs its carve-out, or a later edit reintroduces the contradiction this record removes.
- **It is still unreviewed.** The clause is drafted to be narrow, not checked. See ADR-003's *Note*.

**Neutral.**

- No code, build, dependency or runtime behaviour changes.
- The permission is revocable like the rest of §3, and ends on breach (§9).

## Alternatives considered

- **Keep §4.5 and say in the README that running needs written permission.** Rejected. It makes the
  evaluator's first step a letter to the Author, and an unanswered request is a refused one (§8), so
  most evaluators would stop at the Quick start or run it anyway and breach the licence without
  knowing. It also contradicts the README's own purpose: a Quick start that nobody may follow. **Revisit
  if** the repository stops being offered to evaluators, or the Author wants to know who runs it.
- **Defer the question.** Rejected. The README already told evaluators to run the code, so deferring
  meant publishing instructions the licence forbade until somebody settled it, with §9 attached to every
  reader who obeyed them. **Revisit if** a lawyer's review of the licence is commissioned: the clause is
  then reviewed with the rest.
- **A broader grant: run, modify and test for any non-commercial purpose.** Rejected. The Author's
  concern in [ADR-003](ADR-003-proprietary-source-available-licensing.md) was reuse and misattribution,
  and modification is the step from evaluating the work to building on it. **Revisit when** somebody
  asks to build on a specific part, as ADR-003's dual-licence entry says.

## When to revisit

- **A lawyer reviews the licence**, or a governing jurisdiction is added (ADR-003's *Note*). The clause
  is read again then, in particular the words "solely for the purpose of evaluating it" and "reachable
  by … any other computer over a network".
- **The Author wants evaluators to be able to change the code**, to run experiments or contribute. That
  needs a wider grant and an ADR of its own.
- **[#225](https://github.com/NaimElijah/UpHealther/issues/225) concludes.** It asks whether a security
  researcher may run the code to confirm a finding. This grant covers evaluation only, and #225 still
  owns the research question: if it adds a narrower or wider permission for researchers, this record is
  extended, not rewritten.
- **A person reports being unsure whether their use was evaluation.** The edge needs a sentence, in the
  licence rather than in the README.
