#!/usr/bin/env python3
"""Live entrypoint for A1 with narrow tolerance for missing PR references.

A Notion record may legitimately preserve a historical PR number that does not
exist in the current CarePad repository. A1 has no signal defined for that
absence, so it must remain silent for that PR while keeping every other API
failure fail-closed.
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import a1_operational_risk as a1


_ORIGINAL_GET_PR = a1.GitHubClient.get_pr


def get_pr_tolerating_not_found(
    client: a1.GitHubClient,
    number: int,
):
    try:
        return _ORIGINAL_GET_PR(client, number)
    except a1.OperationalDataUnavailable as exc:
        expected = (
            f"API request failed for /repos/{client.repository}/pulls/{number}: "
            "HTTP Error 404: Not Found"
        )
        if str(exc) == expected:
            return {}
        raise


def main() -> int:
    a1.GitHubClient.get_pr = get_pr_tolerating_not_found
    return a1.main()


if __name__ == "__main__":
    raise SystemExit(main())
