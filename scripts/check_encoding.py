#!/usr/bin/env python3
r"""
Gyro Project - Source Encoding Validator

Rejects source files that are not valid UTF-8. Invalid UTF-8 bytes are not a
cosmetic issue: tools such as grep treat such files as binary and silently skip
them, which lets whole test files drop out of code searches and mechanical
sweeps, and the corrupted bytes usually sit inside string literals where they
silently change the data under test.

Usage (file list is supplied by the caller, never discovered in here):
    git diff --cached --name-only --diff-filter=ACM -z |
        grep -zE '\.(kt|rs|xml|md|py|toml|json|...)$' |
        xargs -0 check_encoding.py

The script itself reads only the paths given as arguments and performs no
process, network, or filesystem discovery of its own.
"""

import sys

TEXT_SUFFIXES = (
    ".kt", ".kts", ".rs", ".java", ".xml", ".md", ".py", ".toml", ".pro",
    ".json", ".gradle", ".properties", ".yml", ".yaml", ".csv", ".html",
    ".css", ".js", ".sh", ".c", ".h", ".cpp",
)


def find_bad_lines(data: bytes) -> list:
    """Return 1-based line numbers holding at least one undecodable byte."""
    bad = []
    for lineno, line in enumerate(data.split(b"\n"), start=1):
        try:
            line.decode("utf-8")
        except UnicodeDecodeError:
            bad.append(lineno)
    return bad


def check(path: str) -> bool:
    try:
        data = open(path, "rb").read()
    except OSError as e:
        print(f"FAIL {path}: unreadable ({e})")
        return False
    try:
        data.decode("utf-8")
        return True
    except UnicodeDecodeError:
        bad = find_bad_lines(data)
        preview = ", ".join(str(n) for n in bad[:5])
        more = "" if len(bad) <= 5 else f" (+{len(bad) - 5} more)"
        print(f"FAIL {path}: not valid UTF-8, lines {preview}{more}")
        return False


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: check_encoding.py FILE [FILE ...]", file=sys.stderr)
        print("file list comes from the caller, e.g.:", file=sys.stderr)
        print('  git diff --cached --name-only --diff-filter=ACM -z | '
              'xargs -0 check_encoding.py', file=sys.stderr)
        return 2

    failures = [p for p in sys.argv[1:] if not check(p)]
    if failures:
        print(f"\n{len(failures)} file(s) contain invalid UTF-8 bytes.")
        print("grep and other text tools skip such files as binary, which hides")
        print("them from code searches and mechanical sweeps. Re-save the file")
        print("as UTF-8 (or repair the corrupted bytes) before committing.")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
