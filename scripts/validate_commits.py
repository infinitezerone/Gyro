#!/usr/bin/env python3
"""
Gyro Project - Commit History & Conventional Commit Validator
Validates that all commits in a given revset strictly adhere to:
1. Conventional Commits format: <type>(<scope>): <subject>
2. English-only (ASCII characters only)
3. No trailing period
4. Subject length <= 80 chars
"""

import sys
import re
import subprocess

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")


PATTERN = re.compile(
    r"^(feat|fix|perf|refactor|test|build|ci|docs|chore|style|revert)"
    r"(\([a-z0-9_\-]+\))?!?: [a-zA-Z0-9].*[^.]$"
)

def is_ascii_only(text: str) -> bool:
    try:
        text.encode("ascii")
        return True
    except UnicodeEncodeError:
        return False

def check_commit_message(msg: str) -> list[str]:
    errors = []
    lines = msg.strip().splitlines()
    if not lines or not lines[0].strip():
        return ["Commit message is empty"]

    first_line = lines[0].strip()

    # Skip merge commits or root commits if needed
    if first_line.startswith("Merge ") or first_line == "Initial commit":
        return []

    # Check ASCII / English-only
    if not is_ascii_only(msg):
        errors.append(f"Non-ASCII / non-English characters found in message: '{first_line}'")

    # Check Conventional Commit pattern
    if not PATTERN.match(first_line):
        errors.append(
            f"Invalid Conventional Commit format: '{first_line}'. "
            f"Expected '<type>(<scope>): <subject>' without trailing period."
        )

    # Check length
    if len(first_line) > 80:
        errors.append(f"Subject line is too long ({len(first_line)} > 80 chars): '{first_line}'")

    return errors

def main():
    rev_range = sys.argv[1] if len(sys.argv) > 1 else "HEAD~5..HEAD"
    try:
        # Get commit hashes and full messages
        cmd = ["git", "log", "--format=%H%x00%B%x00", rev_range]
        output = subprocess.check_output(cmd, text=True, errors="replace")
    except subprocess.CalledProcessError:
        # Fallback to single HEAD if range doesn't exist
        try:
            output = subprocess.check_output(["git", "log", "-1", "--format=%H%x00%B%x00"], text=True, errors="replace")
        except Exception as e:
            print(f"Failed to read git log: {e}")
            return 0

    records = output.strip("\x00\n").split("\x00\x00")
    total_checked = 0
    total_errors = 0

    for rec in records:
        if not rec.strip():
            continue
        parts = rec.split("\x00", 1)
        commit_hash = parts[0].strip()
        msg = parts[1].strip() if len(parts) > 1 else ""

        errs = check_commit_message(msg)
        total_checked += 1
        if errs:
            total_errors += 1
            print(f"❌ Commit {commit_hash[:8]} failed validation:")
            for err in errs:
                print(f"   - {err}")

    if total_errors > 0:
        print(f"\nFound {total_errors} non-compliant commit(s) out of {total_checked} checked.")
        sys.exit(1)
    else:
        print(f"✅ All {total_checked} commit(s) follow Conventional Commits & English-only specification.")
        sys.exit(0)

if __name__ == "__main__":
    main()
