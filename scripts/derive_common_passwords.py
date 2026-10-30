#!/usr/bin/env python3
"""Derives the common-password list that registration refuses (BR-17, ADR-071).

The source is SecLists' ``Pwdb_top-100000.txt``, pinned to one commit and one SHA-256. Download it from
``SOURCE_URL`` and pass its path::

    curl -fsSL -o Pwdb_top-100000.txt <SOURCE_URL>
    node scripts/python.mjs scripts/derive_common_passwords.py Pwdb_top-100000.txt

That rewrites ``backend/src/main/resources/auth/common-passwords.txt``. With ``--check`` it writes
nothing and fails when the committed file differs from what the source derives. Both print the two
SHA-256s that ``common-passwords-NOTICE.txt`` records.

The derivation, in this order, line by line and in the source's rank order:

1. Strip CR, so the file reads the same whatever line endings it was checked out with.
2. Keep an entry of ``MIN_LENGTH`` characters or more. A shorter one is refused by BR-17's minimum
   already, so listing it would only push a longer one out.
3. Drop an entry holding ``@`` (an email address, or part of one), a non-ASCII character or a
   non-printable one. A space is printable and is kept.
4. Normalise it as the backend does a password: NFKC, then lower case. On the ASCII left after step 3,
   NFKC changes nothing, so this lower-cases it.
5. Drop it if it holds a term from ``BLOCKED_TERMS``.
6. Drop it if its normalised form is already on the list. Comparing normalised forms is what makes the
   list ``LIST_SIZE`` entries the matcher can tell apart, since ``Password1`` and ``password1`` are
   refused alike.
7. Stop at ``LIST_SIZE`` entries. Nothing is sorted at any step: the list keeps the source's ranking.

The terms in ``BLOCKED_TERMS`` are slurs and hate slogans, found by reading the head of the source by
eye. They are stored as SHA-256 digests, so the repository does not spell them, with each term's
length so that only substrings of that length are hashed. A digest is checked with
``printf %s term | sha256sum``. A term is matched anywhere in the normalised entry.
"""

import argparse
import hashlib
import sys
import unicodedata
from collections import Counter
from pathlib import Path

SOURCE_REPOSITORY = "danielmiessler/SecLists"
SOURCE_COMMIT = "0008f4343439b48257c0815fc797955d0ab1e755"
SOURCE_PATH = "Passwords/Common-Credentials/Pwdb_top-100000.txt"
SOURCE_URL = f"https://raw.githubusercontent.com/{SOURCE_REPOSITORY}/{SOURCE_COMMIT}/{SOURCE_PATH}"

# The source as downloaded from SOURCE_URL. A different file is refused rather than derived from, so
# a moved pin is a change to this line and to the notice, never a silent one.
SOURCE_SHA256 = "07f876a616f08fb2cc5c3e0ce04e4a6d1123380580472b0997baebc4e8226977"

# BR-17's minimum: AuthController.PASSWORD_MIN.
MIN_LENGTH = 8

# The owner's decision on issue #339 (S5.A6). OWASP ASVS 5.0 6.2.4 asks for at least 3,000.
LIST_SIZE = 10_000

OUTPUT = Path(__file__).resolve().parents[1] / "backend/src/main/resources/auth/common-passwords.txt"

RACIAL_SLUR = "racial slur"
DISABILITY_SLUR = "disability slur"
HATE_SLOGAN = "hate slogan or code"

# (category, length of the term, SHA-256 of the lower-case ASCII term)
BLOCKED_TERMS = (
    (RACIAL_SLUR, 6, "120f6e5b4ea32f65bda68452fcfaaef06b0136e1d0e4a6f60bc3771fa0936dd6"),
    (RACIAL_SLUR, 5, "08a841e996781e9e77d30a4e4420a8f501a280b00624e6d1224bf54aaff73eba"),
    (RACIAL_SLUR, 5, "84105992682cfadf3cfbd9ccdaf9246b72e05b2f59e4a751d5398ad6e983ee6f"),
    (RACIAL_SLUR, 8, "701033ae26411908f31114d29190f60cdd9bf3e720e303f793882af5763b688f"),
    (DISABILITY_SLUR, 6, "158869a97379229b7681efae9d7f9c9214134e836d649ba53477c0c111414d59"),
    (HATE_SLOGAN, 8, "f9f28846f495daef43d0925a5012603b86f8db43259e88e4639f294804ff8bd1"),
    (HATE_SLOGAN, 6, "fd70ad909b94deb27b460692084d9f2b1dbc9df3c6bcfd3caee571e707031e3f"),
    (HATE_SLOGAN, 8, "66dbe34897b5bb7a423789e58aecee26472ff57b1f5aa1ef5b8cecbee3f164c3"),
    (HATE_SLOGAN, 10, "4250c0f1a20027a0a1b05d4557326b2c13dd71b9eac43faeecfd66438220bec0"),
)

CATEGORY_BY_DIGEST = {digest: category for category, _, digest in BLOCKED_TERMS}
TERM_LENGTHS = sorted({length for _, length, _ in BLOCKED_TERMS})


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def normalise(entry: str) -> str:
    """The form the backend compares: NFKC, then lower case, as CommonPasswordPolicy does."""
    return unicodedata.normalize("NFKC", entry).lower()


def blocked_category(entry: str) -> str | None:
    """The category of the first blocked term the entry holds, or None."""
    for length in TERM_LENGTHS:
        for start in range(len(entry) - length + 1):
            category = CATEGORY_BY_DIGEST.get(sha256_hex(entry[start : start + length].encode("ascii")))
            if category is not None:
                return category
    return None


def derive(source: bytes) -> tuple[list[str], Counter, int]:
    """The list, the count of entries dropped per reason, and how many source lines were read."""
    lines = source.decode("utf-8").replace("\r", "").split("\n")
    if lines and lines[-1] == "":
        lines.pop()

    kept: list[str] = []
    seen: set[str] = set()
    dropped: Counter = Counter()
    read = 0
    for line in lines:
        if len(kept) == LIST_SIZE:
            break
        read += 1
        if len(line) < MIN_LENGTH:
            dropped["shorter than the minimum"] += 1
            continue
        if "@" in line:
            dropped["holds @"] += 1
            continue
        if not line.isascii():
            dropped["non-ASCII"] += 1
            continue
        if not line.isprintable():
            dropped["non-printable"] += 1
            continue
        entry = normalise(line)
        category = blocked_category(entry)
        if category is not None:
            dropped[category] += 1
            continue
        if entry in seen:
            dropped["duplicate once normalised"] += 1
            continue
        seen.add(entry)
        kept.append(entry)

    if len(kept) < LIST_SIZE:
        raise SystemExit(f"the source yields {len(kept)} entries, fewer than {LIST_SIZE}")
    return kept, dropped, read


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("source", type=Path, help=f"the file downloaded from {SOURCE_URL}")
    parser.add_argument("--check", action="store_true", help="fail if the committed list differs")
    arguments = parser.parse_args()

    source = arguments.source.read_bytes()
    source_digest = sha256_hex(source)
    if source_digest != SOURCE_SHA256:
        print(f"source SHA-256 is {source_digest}, expected {SOURCE_SHA256}", file=sys.stderr)
        return 1

    kept, dropped, read = derive(source)
    derived = ("\n".join(kept) + "\n").encode("ascii")

    print(f"source  {SOURCE_URL}")
    print(f"source  SHA-256 {source_digest}")
    print(f"read    {read} lines, to the {LIST_SIZE:,}th entry kept")
    for reason, count in sorted(dropped.items()):
        print(f"dropped {count:>6}  {reason}")
    print(f"derived {len(kept)} entries, SHA-256 {sha256_hex(derived)}")

    if arguments.check:
        if not OUTPUT.is_file() or OUTPUT.read_bytes() != derived:
            print(f"{OUTPUT} differs from what the source derives", file=sys.stderr)
            return 1
        return 0
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_bytes(derived)
    print(f"wrote   {OUTPUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
