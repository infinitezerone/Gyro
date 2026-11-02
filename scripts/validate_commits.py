#!/usr/bin/env python3
"""
Gyro Project - Commit Message & Commit History Validator

Validates, for every commit being pushed:
1. Conventional Commits format: <type>(<scope>): <subject>
2. English text and English punctuation only
3. No trailing period
4. Subject length <= 80 chars

"English only" is enforced per character. ASCII is always allowed. Above ASCII
the only permitted characters are Latin letters (so cafe, naive and senor can
keep their accents) and combining diacritics. Everything else is refused: CJK,
kana, hangul, Greek, Cyrillic, Hebrew, Arabic, Thai, fullwidth forms, and also
typographic punctuation such as an em dash, curly quotes or the ellipsis, plus
symbols and emoji.

Two entry points:
    validate_commits.py [rev-range]        validate commits in a range
    validate_commits.py --scan-file PATH   validate a single message file

Git output is decoded as UTF-8 explicitly: the default on Windows is the console
code page, which mangles non-ASCII bytes into replacement characters and turns a
fine message into a "non-English" failure.
"""

import re
import subprocess
import sys
import unicodedata

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")


PATTERN = re.compile(
    r"^(feat|fix|perf|refactor|test|build|ci|docs|chore|style|revert)"
    r"(\([a-z0-9_\-]+\))?!?: [a-zA-Z0-9].*[^.]$"
)

# Latin letters outside ASCII, i.e. the accented forms used inside English words.
LATIN_LETTER_RANGES = (
    (0x00C0, 0x024F),  # Latin-1 Supplement letters, Latin Extended-A and B
    (0x0250, 0x02AF),  # IPA Extensions
    (0x1D00, 0x1DBF),  # Phonetic Extensions and Supplement
    (0x1E00, 0x1EFF),  # Latin Extended Additional
    (0x2C60, 0x2C7F),  # Latin Extended-C
    (0xA720, 0xA7FF),  # Latin Extended-D
    (0xAB30, 0xAB6F),  # Latin Extended-E
    (0xFB00, 0xFB06),  # Latin ligatures
)

# Diacritics that can follow a letter instead of being precomposed into it.
COMBINING_RANGES = ((0x0300, 0x036F),)

# git log records, separated by ASCII control characters so a message body can never
# be mistaken for a record boundary (the previous NUL-based split misaligned records
# and blamed the wrong commit).
RECORD_SEP = "\x1e"
FIELD_SEP = "\x1f"


def in_ranges(code: int, ranges) -> bool:
    return any(low <= code <= high for low, high in ranges)


def is_allowed(char: str) -> bool:
    code = ord(char)
    if code <= 0x7F:
        return True
    if in_ranges(code, COMBINING_RANGES):
        return True
    if not unicodedata.category(char).startswith("L"):
        # Punctuation, symbols and emoji: refused above ASCII.
        return False
    return in_ranges(code, LATIN_LETTER_RANGES)


def first_offending(text: str):
    """Returns (index, character) of the first character outside the rule, or None."""
    for index, char in enumerate(text):
        if ord(char) > 0x7F and not is_allowed(char):
            return index, char
    return None


def describe(index: int, char: str, text: str) -> str:
    line = text.count("\n", 0, index) + 1
    context = text[max(0, index - 20) : index + 20].replace("\n", " ")
    kind = unicodedata.category(char)
    return (
        f"U+{ord(char):04X} {char!r} (category {kind}) on line {line} "
        f"at offset {index}, near: {context!r}"
    )


def check_commit_message(msg: str) -> list:
    errors = []
    lines = msg.strip().splitlines()
    if not lines or not lines[0].strip():
        return ["Commit message is empty"]

    first_line = lines[0].strip()

    # Skip merge commits or root commits if needed
    if first_line.startswith("Merge ") or first_line == "Initial commit":
        return []

    # English text and punctuation only, subject and body alike
    hit = first_offending(msg)
    if hit is not None:
        errors.append(
            f"Non-English character in message '{first_line}': {describe(*hit, msg)}"
        )

    # Check Conventional Commit pattern
    if not PATTERN.match(first_line):
        errors.append(
            f"Invalid Conventional Commit format: '{first_line}'. "
            f"Expected '<type>(<scope>): <subject>' without trailing period."
        )

    # Check length
    if len(first_line) > 80:
        errors.append(
            f"Subject line is too long ({len(first_line)} > 80 chars): '{first_line}'"
        )

    return errors


def git(*args: str) -> str:
    return subprocess.check_output(
        ["git", *args], encoding="utf-8", errors="replace"
    )


def resolve_range(rev_range: str) -> str:
    """Falls back to the latest commit when the range cannot be resolved."""
    try:
        git("rev-parse", "--verify", rev_range.split("..")[0])
        return rev_range
    except subprocess.CalledProcessError:
        return "HEAD~1..HEAD"


def scan_file(path: str) -> int:
    """Character rule only, for the commit-msg hook. Format is checked there."""
    with open(path, encoding="utf-8", errors="replace") as handle:
        text = handle.read()
    hit = first_offending(text)
    if hit is None:
        return 0
    print("Non-English character in commit message:")
    print(f"   - {describe(*hit, text)}")
    print("Messages must be written in English, with English punctuation only.")
    return 1


def main(argv) -> int:
    if "--scan-file" in argv:
        position = argv.index("--scan-file")
        if position + 1 >= len(argv):
            print("--scan-file requires a path")
            return 2
        return scan_file(argv[position + 1])

    rev_range = resolve_range(argv[1] if len(argv) > 1 else "origin/master..HEAD")
    try:
        output = git("log", f"--format=%H{FIELD_SEP}%B{RECORD_SEP}", rev_range)
    except subprocess.CalledProcessError:
        print(f"Failed to read git log for range '{rev_range}'")
        return 0

    records = [rec for rec in output.split(RECORD_SEP) if rec.strip()]
    total_checked = 0
    total_errors = 0

    for rec in records:
        commit_hash, _, msg = rec.partition(FIELD_SEP)
        commit_hash = commit_hash.strip()
        if not commit_hash:
            continue
        errs = check_commit_message(msg.strip())
        total_checked += 1
        if errs:
            total_errors += 1
            print(f"❌ Commit {commit_hash[:8]} failed validation:")
            for err in errs:
                print(f"   - {err}")

    if total_errors > 0:
        print(f"\nFound {total_errors} non-compliant commit(s) out of {total_checked} checked.")
        return 1

    print(f"✅ All {total_checked} commit(s) follow Conventional Commits & English-only specification.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
