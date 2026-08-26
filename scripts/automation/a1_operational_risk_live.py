#!/usr/bin/env python3
"""Live entrypoint for A1 with narrow tolerance for missing historical PR references.

A Notion record may legitimately preserve a historical PR number that does not
exist in the current CarePad repository. A1 has no signal defined for that
absence, so it must remain silent only when every source that references the
missing PR is historical. Active/non-historical evidence keeps A1 fail-closed.
"""

from __future__ import annotations

import sys
from pathlib import Path
from typing import Any, Iterable, Mapping

sys.path.insert(0, str(Path(__file__).resolve().parent))

import a1_operational_risk as a1


_ORIGINAL_GET_PR = a1.GitHubClient.get_pr
_ORIGINAL_QUERY_DATA_SOURCE = a1.NotionClient.query_data_source
_PR_REFERENCE_PROVENANCE: dict[int, set[str]] = {}


def _reference_number(record: Mapping[str, Any]) -> int | None:
    return a1.parse_pr_number(
        record.get("github_ref"),
        record.get("pr_url"),
        record.get("version"),
    )


def register_reference_provenance(
    *,
    coordination: Iterable[Mapping[str, Any]] = (),
    qa: Iterable[Mapping[str, Any]] = (),
) -> None:
    for record in coordination:
        number = _reference_number(record)
        if number is None:
            continue
        historical = (
            record.get("state") in a1.CLOSED_COORDINATION_STATES
            or a1.is_historical(record)
        )
        _PR_REFERENCE_PROVENANCE.setdefault(number, set()).add(
            "historical" if historical else "active"
        )

    for record in qa:
        number = _reference_number(record)
        if number is None:
            continue
        historical = record.get("state") in a1.HISTORICAL_QA_STATES
        _PR_REFERENCE_PROVENANCE.setdefault(number, set()).add(
            "historical" if historical else "active"
        )


def query_data_source_tracking_references(
    client: a1.NotionClient,
    data_source_id: str,
):
    pages = _ORIGINAL_QUERY_DATA_SOURCE(client, data_source_id)

    if data_source_id == a1.COORDINATION_DATA_SOURCE:
        register_reference_provenance(
            coordination=(a1.normalize_coordination(page) for page in pages)
        )
    elif data_source_id == a1.QA_DATA_SOURCE:
        register_reference_provenance(
            qa=(a1.normalize_qa(page) for page in pages)
        )

    return pages


def get_pr_tolerating_historical_not_found(
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
        historical_only = _PR_REFERENCE_PROVENANCE.get(number) == {"historical"}
        if historical_only and str(exc) == expected:
            return {}
        raise


def main() -> int:
    _PR_REFERENCE_PROVENANCE.clear()
    a1.NotionClient.query_data_source = query_data_source_tracking_references
    a1.GitHubClient.get_pr = get_pr_tolerating_historical_not_found
    try:
        return a1.main()
    finally:
        a1.NotionClient.query_data_source = _ORIGINAL_QUERY_DATA_SOURCE
        a1.GitHubClient.get_pr = _ORIGINAL_GET_PR


if __name__ == "__main__":
    raise SystemExit(main())
