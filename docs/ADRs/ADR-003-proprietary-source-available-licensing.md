# ADR-003: Publish source-available under a proprietary licence, not open source

- **Status:** Accepted
- **Date:** 2026-08-18
- **Scope:** The repository as a whole. No effect on the architecture, the build or the runtime.

## Context

The repository is being made public. Until now it was private and carried no `LICENSE` file at all,
which the repository baseline requires.

The absence was not neutral. Under copyright law, code published with no licence is "all rights
reserved" by default — nobody may lawfully use it — but readers routinely assume a public repository is
free to take. The gap between the legal position and the common assumption is exactly where a dispute
would sit.

The author's goal in publishing is **evaluation, not adoption**: the repository is a portfolio piece,
meant to be read by prospective employers, collaborators and the technically curious. There is no
intent to build a user community, accept contributions, or have the code adopted as a dependency. The
concern that prompted this decision was that someone would present the work as their own.

Two facts shaped the choice:

1. **No licence prevents copying.** A licence is a legal instrument, not a technical control. Its value
   is that it makes the terms unambiguous and gives the author standing to act.
2. **The best evidence of authorship is already in place.** The repository has a full commit history
   attributed to the author, dated, and now public alongside the code. That is stronger day-to-day
   protection against a plagiarism claim than any licence text.

## Decision

Publish under a **proprietary source-available licence**, written for this repository and recorded in
`LICENSE`, rather than under an open-source licence.

The licence grants exactly one thing: permission to read, retain a local copy for evaluation, and quote
short excerpts with credit. It withholds everything else — use, modification, redistribution,
deployment, and presenting the work as one's own — pending written permission.

Three clauses exist for reasons worth stating:

- **Third-party components are carved out** (§5). The repository depends on Spring Boot, React,
  PostgreSQL and others under their own terms. A licence that appeared to claim rights over them would
  be both wrong and unenforceable.
- **Contributions are assigned to the author** (§6). Contributions are not solicited, but an
  unsolicited pull request that the author merged would otherwise leave a fragment of the repository
  owned by somebody else — which is the single-ownership property this licence exists to preserve.
- **Warranty and liability are disclaimed** (§10, §11). Publishing code creates exposure regardless of
  whether anyone is permitted to use it, and this repository models health behaviour, where a reader
  acting on it could come to harm.

The README carries a plain-English summary and points at `LICENSE` as the governing text.

## Consequences

**What this makes easy**

- The legal position is stated rather than inferred. A reader who copies the work cannot claim the
  repository looked like an invitation.
- Authorship is asserted in the licence, in the README, and in the commit history — three independent
  records that agree.
- The repository can be shown to anyone without implicitly offering them anything.
- The repository baseline is satisfied: `LICENSE` exists.

**What this makes hard**

- **It is not open source, and GitHub will not display a licence badge for it.** Automated tooling that
  classifies licences will report this repository as unlicensed or "other". Some readers will read that
  as carelessness rather than as a decision — which is part of why this record exists.
- **Forking cannot be prevented on GitHub.** Making a repository public means accepting GitHub's terms,
  under which other users may view and fork it through the platform. The licence governs what a forker
  may then lawfully *do*, not whether the fork button works.
- **Nobody can contribute or reuse the work without an individual grant.** That is the intent, but it
  forecloses the incidental benefits of open source: bug reports from users, adoption as a dependency,
  and the signal that permissive licensing sends to some employers.
- **Enforcement is the author's problem.** The licence creates standing; acting on it costs time and,
  potentially, money. Realistically its function is deterrence and clarity.

**Neutral**

- No code, build, dependency or runtime behaviour changes.
- The licence can be relaxed later. It cannot be tightened retroactively for a version already
  published under looser terms — which is the asymmetry behind starting restrictive.

## Alternatives considered

- **Apache-2.0.** The professional default for "use it, but credit me": redistribution must retain the
  copyright notice, state modifications, and it withholds trademark rights. Rejected because it permits
  exactly what the author did not want to permit — anyone building on the work, commercially and
  closed-source, with attribution buried in a notices file. **Revisit if** the goal changes from being
  read to being used, or if the repository ever needs to be consumed as a dependency by another
  project.

- **MIT.** Simplest and most recognised. Rejected for the same reason as Apache-2.0, and it is weaker
  still: no patent grant, no trademark clause, no requirement to state modifications. **Revisit if**
  the project is ever released for general use and minimising friction matters more than control.

- **AGPL-3.0.** Would prevent anyone building a closed-source product on the work, since network use
  triggers the obligation to publish source. Rejected because it still grants full use and modification
  rights, which is more than intended, and because many organisations ban AGPL code outright — reducing
  the readership this repository is published for. **Revisit if** the project gains real users and the
  concern shifts from attribution to commercial appropriation.

- **No licence at all.** Legally close to the chosen outcome, since the default is already all rights
  reserved. Rejected because it communicates nothing: it reads as an oversight, leaves readers guessing,
  and fails the repository baseline. Saying "all rights reserved" deliberately is different from having
  said nothing.

- **A dual licence** — source-available by default, with a permissive licence for a defined subset.
  Rejected as premature: there is no subset anyone has asked for, and maintaining two licences over one
  codebase is a real cost with no current benefit. **Revisit when** somebody actually asks to use a
  specific part.

## Note

This licence was drafted for the specific goal recorded above; it has not been reviewed by a lawyer.
It is enforceable as written in the ordinary case — the terms are clear and the restrictions
conventional — but it names no governing jurisdiction, which is the first thing to add if the
repository ever becomes commercially significant.
