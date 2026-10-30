# ADR-071: Registration refuses the 10,000 most common passwords, from a pinned SecLists list

- **Status:** Accepted
- **Date:** 2026-10-30
- **Scope:** Registration only. `backend/src/main/resources/auth/common-passwords.txt` and
  `common-passwords-NOTICE.txt` beside it; `scripts/derive_common_passwords.py`, which derives the list;
  `NotCommonPassword` on `AuthController.RegisterRequest`, `CommonPasswordPolicy`,
  `CommonPasswordListPort` and `ClasspathCommonPasswordList` in `auth`; `LICENSE` §5. BR-17 in
  `docs/requirements/requirements.md`.
- **Issue:** [#339](https://github.com/NaimElijah/UpHealther/issues/339), scan 05 of
  [#334](https://github.com/NaimElijah/UpHealther/issues/334), finding S5.A6. The owner's decisions are
  [the S5.A6 bullet](https://github.com/NaimElijah/UpHealther/issues/339#issuecomment-6516800870) and
  [the deny-list's terms](https://github.com/NaimElijah/UpHealther/issues/339#issuecomment-6522753362).
  Builds on [ADR-003](ADR-003-proprietary-source-available-licensing.md) and extends what
  [ADR-059](ADR-059-the-bundle-ships-its-third-party-notices.md) says about the jar.

## Context

BR-17 refused a password under eight characters and nothing else, so `password`, `12345678` and
`qwerty123` registered. NIST SP 800-63B-4 requires a new password to be checked against a list of
commonly used, expected or compromised values, and sets no size for that list. OWASP ASVS 5.0 6.2.4
asks for at least the top 3,000 passwords that meet the length policy. ASVS L2 6.2.12, a check against
a breach corpus, is not a requirement here.

**What it replaces.** Nothing. No check of any kind ran before.

**Why the JDK and the existing dependencies are not enough.** The JDK carries no password list.
Spring Security's `CompromisedPasswordChecker` is an interface, and its one implementation,
spring-security-web's `HaveIBeenPwnedRestApiPasswordChecker`, calls the Have I Been Pwned range API on
every check. That
is a network call to a third party on the registration path, in a system where nothing leaves the
deployment (`architecture.md`, External dependencies).

**The candidates.** `dependency-vetter` checked them before the owner decided:

- **SecLists** is MIT, "Copyright (c) 2018 Daniel Miessler". None of its common-password files states
  a licence or a provenance of its own, so where `Pwdb_top-100000.txt`'s entries came from is not
  documented. The owner accepts that.
- **The NCSC's copy** of a top-100,000 list is defective: it carries mojibake and fragments of email
  addresses, and the NCSC's own terms for it could not be found.
- **The size is a judgement.** NIST names none; ASVS's 3,000 is a floor.

**Maintenance.** SecLists is active and not archived; its `master` was pushed on 2026-10-30. The file
itself last changed on 2025-03-22, when it was renamed. The list does not need upstream maintenance to
work: a common password from 2015 is still common.

## Decision

**Registration refuses a password whose normalised form is on a bundled list of 10,000 common
passwords, answered 400 with a field error on `password`, as BR-17's minimum is.**

- **The source** is SecLists' `Passwords/Common-Credentials/Pwdb_top-100000.txt` at commit
  `0008f4343439b48257c0815fc797955d0ab1e755`, the `master` head on 2026-10-30. The raw file's SHA-256
  is `07f876a616f08fb2cc5c3e0ce04e4a6d1123380580472b0997baebc4e8226977`.
- **The derivation** is `scripts/derive_common_passwords.py`, committed so that anyone can repeat it.
  It refuses a source with another SHA-256. Line by line in rank order, it strips CR, keeps an entry
  of eight characters or more, drops one that is an email address as a whole (`something@domain.tld`)
  and one holding a non-ASCII character or a non-printable one, normalises it, drops slurs and hate
  slogans, drops a normalised duplicate, and stops at 10,000. Nothing is sorted. The derived list's
  SHA-256 is `e3404947376fd7131257479a5c0441198c5ec0134fd2dc70c7db0837e43753dd`.
  - The list was also read by eye, every entry and the ones that would follow it. The filter's terms
    are the slurs and hate slogans that reading found. The script stores them as SHA-256 digests, so
    the repository does not spell them. They dropped nine entries: six racial or ethnic slurs, one
    slur against disability and two hate codes. One of the six is a former NFL team's name, which
    dictionaries define as an ethnic slur, so the team's sense does not keep it.
  - An `@` alone does not drop an entry, by the owner's decision on
    [PR #385](https://github.com/NaimElijah/UpHealther/pull/385)'s review. `p@ssw0rd`, `1qaz@wsx` and
    `zaq1@wsx` are common passwords that a sprayer tries early, and dropping every entry holding `@`
    let them register. No entry of the pinned source is a whole address, so the rule drops none of
    it today. `@yahoo.com` and `@gmail.com.mx`, fragments of one, stay on the list and are refused like
    any other entry.
  - A duplicate is judged on the normalised form, because the matcher cannot tell `Password1` from
    `password1`. The 10,000 entries are therefore 10,000 passwords the rule refuses separately.
- **The notice.** `common-passwords-NOTICE.txt` sits beside the list. It carries SecLists' MIT notice
  verbatim, the source URL, the pinned commit and both SHA-256s. Both files are in
  `src/main/resources`, so both ship in the jar, under `BOOT-INF/classes/auth/`.
  `CommonPasswordListTest` fails when the list's digest differs from the one the notice records.
- **`LICENSE` §5 names the list.** ADR-003's carve-out covers third-party components the software
  depends on. This one is data copied into the repository, so §5 now names it as a third-party
  component governed by its own MIT licence.
- **Matching.** A password is refused when its NFKC form, lower-cased, is on the list. Each entry is
  normalised the same way as it is loaded. Nothing else is compared: not the email, the name, or the
  service's name.
- **Where it runs.** `@NotCommonPassword` sits on `RegisterRequest.password` beside `@Size` and
  `@MaxUtf8Bytes`. A refusal is a bean-validation failure, so it is the same 400, with the same
  `Validation failed` body and a message under `fieldErrors.password`, and it is refused before
  `AuthService` runs. The constraint asks `CommonPasswordPolicy` in the application layer, which owns
  the normalisation and the set. The policy reads the entries through `CommonPasswordListPort`, which
  `ClasspathCommonPasswordList` implements, so the web adapter never touches an outbound port
  (ADR-064).
- **It is loaded once.** `CommonPasswordPolicy` builds an immutable set in its constructor, at
  startup. `ClasspathCommonPasswordList` refuses a missing resource and an empty one, naming where it
  looked, so the application refuses to start rather than run without the rule.
  `ClasspathCommonPasswordListTest` starts a context over each and sees it fail.
- **Registration only.** It is the one route by which a password enters the system (BR-17). The
  seeded demo account's `demo1234` is not on the list, and the rule does not apply at sign-in.
- **No dependency is added.**

## Consequences

**What this makes easy**

- The most common passwords that meet the length rule are refused, with a reason the register page
  already shows, as it shows any field error.
- The list is reproducible: the script, the pin and the two digests let anyone derive it again and
  compare.
- A check costs one hash lookup, in memory, with no network call.

**What this makes hard**

- **The list is a snapshot.** A password that becomes common later is not on it until somebody
  re-derives it, and nothing reminds them to.
- **A list of 10,000 is not a breach corpus.** A password leaked in a breach but outside the top
  10,000 is accepted. That is the gap ASVS L2 6.2.12 would close.
- **The filter's terms cannot be read in the repository.** A reviewer confirms one by hashing a
  candidate word. That is the cost of not publishing them.
- **The list holds profanity.** The decision names slurs, and only slurs and hate slogans are
  filtered. A profane password is as common as any other, and it is refused like one.
- **About 96 KB of the jar and a little heap** hold the list. The set is built once.

**Neutral**

- The list is public by design. It holds passwords that are already public, and it is not a store of
  anyone's credentials. The README says so.
- The frontend needs no change: `toFormMessage` renders a 400's field errors, the refusal's included.

## When to revisit

- **The file's terms turn out to conflict:** a licence or provenance statement for it appears that
  does not allow this use.
- **ASVS L2 6.2.12 becomes a requirement.** That needs a breach-corpus check, which a static list
  cannot be.
- **Breaches outside the list dominate,** so that refused registrations no longer reflect what
  attackers try.
- **SecLists is archived, or the file is removed** from it. The pinned copy keeps working, but it has
  no source left to re-derive from.
- **The list goes three years stale,** counted from the date above.

## Alternatives considered

`dependency-vetter` compared these before the owner decided. Its counts of entries of eight or more
were taken in bytes; counted in characters, as BR-17 counts, they are a little lower where an entry
holds a character outside ASCII, and both are given where they differ.

- **SecLists' `Pwdb_top-100000.txt`, chosen.** The largest yield: 49,058 entries of eight bytes or more
  (49,056 characters). SecLists' README gives no provenance for it, but it is a plain compilation under
  SecLists' MIT licence with no terms of its own.
- **The NCSC's `100k-most-used-passwords-NCSC.txt`, second.** Its origin is the best known of the
  candidates: Have I Been Pwned's data, published by the NCSC in April 2019. Rejected because the copy
  is not clean. Its git history is a run of cleanup commits and reverts, and the vet found mojibake and
  email fragments left in it: 51 of its 47,369 entries of eight bytes or more (47,324 characters) hold
  `@`. The NCSC's own terms could not be read, because their URL answers 404.
- **SecLists' `xato-net-10-million-passwords-100000.txt`, the fallback.** Its provenance is documented,
  xato.net's ten-million-credential set of 2015, and it yields 39,329 entries of eight or more. Not
  chosen because it is smaller and older than `Pwdb`.
- **SecLists' `darkweb2017-top10000.txt`.** Too small: 4,001 entries of eight bytes or more (3,978
  characters), under the 10,000 decided.
- **Spring Security's `CompromisedPasswordChecker` over Have I Been Pwned's k-anonymity range API.**
  It states no licence or attribution requirement and covers more than 800 million passwords, far more
  than any list here. Rejected: it adds a network call to every registration, makes sign-up depend on a
  third party's availability, and forces a choice between failing open and failing closed when that
  party is down, in a system with no third-party integration. The owner chose an offline list.
  **Revisit if** ASVS L2 6.2.12, a check against a breached-password set of no stated size, becomes a
  requirement.
- **A library from Maven Central.** None was preferred. `java-dumb-passwords` (MIT, per its POM),
  `zxcvbn4j` (`com.nulab-inc:zxcvbn` 1.9.0, MIT, per its README) and `nbvcxz` (MIT) were found, but
  the vet could not verify where their dictionaries come from, how large they are, or whether the
  projects still release. Each would add a dependency tree to deliver the same kind of unprovenanced
  data that a committed file delivers with none.
- **A list written by hand, as the only source.** Rejected: it would sit far below ASVS L1 6.2.4's
  floor of the top 3,000, and nothing would say where its entries came from.
- **Another size.** NIST SP 800-63B-4 requires a blocklist and sets no size. ASVS 5.0 6.2.4 asks for at
  least the top 3,000, so 10,000 is about three times that floor. Every qualifying `Pwdb` entry, about
  47,000 once normalised and deduplicated, would cost only memory, but it was not asked for.
- **No list.** Rejected: NIST SP 800-63B-4 requires one, and BR-17 alone lets `password` register.
