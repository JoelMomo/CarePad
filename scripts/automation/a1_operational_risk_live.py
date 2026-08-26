#!/usr/bin/env python3
"""Live entrypoint for A1 with narrow handling for historical evidence.

A Notion record may legitimately preserve a historical PR number that does not
exist in the current CarePad repository. A1 has no signal defined for that
absence, so it must remain silent only when every source that references the
missing PR is historical. Active/non-historical evidence keeps A1 fail-closed.

A1-02 also needs to distinguish a current PR-state assertion from contextual or
historical prose. The live adapter keeps the detector strict, but only treats
open/draft/not-merged language as stale when it is the current GitHub reference
or an explicit present-tense assertion tied to that PR. Historical narration
and phrases about another object (for example, an open handoff) are not PR-state
claims.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path
from typing import Any, Iterable, Mapping

sys.path.insert(0, str(Path(__file__).resolve().parent))

import a1_operational_risk as a1


_ORIGINAL_GET_PR = a1.GitHubClient.get_pr
_ORIGINAL_QUERY_DATA_SOURCE = a1.NotionClient.query_data_source
_ORIGINAL_DETECT_A1_02 = a1.detect_a1_02
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


def _explicit_current_open_claim(text: str, pr_number: int) -> bool:
    if not text:
        return False
    if a1.STALE_OPEN_NEGATION_PATTERN.search(text):
        return False

    state = (
        r"(?:abiert[oa]|open|draft|no\s+fusionad[oa]|sin\s+fusionar|"
        r"pendiente\s+de\s+(?:merge|fusionar)|merge\s+pendiente)"
    )
    pr = rf"PR\s*#{pr_number}\b"
    # Deliberately present tense: imperfect forms such as "seguía" describe past evidence.
    present = r"(?:(?:todav[ií]a\s+)?(?:est[aá]|sigue|permanece|contin[uú]a)\s+)?"
    separator = r"\s*(?:(?:[:=·—-])\s*)?"

    after_pr = re.compile(
        rf"\b{pr}{separator}{present}{state}\b",
        re.IGNORECASE,
    )
    before_pr = re.compile(
        rf"\b(?:est[aá]|sigue|permanece|contin[uú]a)\s+{state}\b.{{0,24}}\b{pr}",
        re.IGNORECASE | re.DOTALL,
    )
    return bool(after_pr.search(text) or before_pr.search(text))


def _has_current_stale_pr_claim(record: Mapping[str, Any], pr_number: int) -> bool:
    github_ref = str(record.get("github_ref") or "")
    if (
        a1.STALE_OPEN_PATTERN.search(github_ref)
        and not a1.STALE_OPEN_NEGATION_PATTERN.search(github_ref)
    ):
        return True

    return any(
        _explicit_current_open_claim(str(record.get(field) or ""), pr_number)
        for field in ("summary", "subject")
    )


def detect_a1_02_contextual(snapshot: Mapping[str, Any]) -> list[a1.Alert]:
    """A1-02 with current-state context, preserving all other A1-02 gates."""

    alerts: list[a1.Alert] = []
    specialists = snapshot.get("specialists", [])
    for record in snapshot.get("coordination", []):
        if a1.is_historical(record):
            continue
        edited = a1.parse_time(record.get("last_edited"))
        if not edited:
            continue
        pr_number = a1.parse_pr_number(
            record.get("github_ref"), record.get("summary"), record.get("next_step")
        )
        pr = a1.pr_for(snapshot, pr_number)
        combined = a1.text_of(record)
        if pr:
            terminal_raw = pr.get("merged_at") or (
                pr.get("closed_at") if pr.get("state") == "closed" else None
            )
            terminal_at = a1.parse_time(terminal_raw)
            next_step = str(record.get("next_step") or "")
            stale_pr_claim = bool(
                (
                    pr_number is not None
                    and _has_current_stale_pr_claim(record, pr_number)
                )
                or (
                    a1.STALE_MERGE_ACTION_PATTERN.search(next_step)
                    and not a1.STALE_MERGE_ACTION_NEGATION_PATTERN.search(next_step)
                )
            )
            if terminal_at and edited > terminal_at and stale_pr_claim:
                alert = a1.make_alert(
                    "A1-02",
                    record,
                    specialists,
                    f"El registro fue actualizado después del cierre/merge de PR #{pr_number}, pero conserva una afirmación o acción incompatible con ese evento.",
                    f"evento={terminal_raw}; registro actualizado={record.get('last_edited')}; texto={combined[:500]}",
                )
                if alert:
                    alerts.append(alert)
                continue

        for run_number, claimed_status in a1.parse_ci_claims(
            record.get("github_ref"), record.get("summary"), record.get("next_step")
        ):
            if claimed_status not in {"PENDING", "IN_PROGRESS", "EN_CURSO", "PENDIENTE"}:
                continue
            if a1.ci_pending_claim_is_negated(record, run_number):
                continue
            run = a1.run_for(snapshot, run_number)
            completed_at = (
                a1.parse_time(run.get("updated_at"))
                if run and run.get("status") == "completed"
                else None
            )
            if run and completed_at and edited > completed_at:
                alert = a1.make_alert(
                    "A1-02",
                    record,
                    specialists,
                    f"El registro fue actualizado después de completarse CI #{run_number}, pero todavía la describe como pendiente/en curso.",
                    f"CI #{run_number}: conclusion={run.get('conclusion')}; completada={run.get('updated_at')}; registro={record.get('last_edited')}",
                )
                if alert:
                    alerts.append(alert)
                break
    return alerts


def main() -> int:
    _PR_REFERENCE_PROVENANCE.clear()
    a1.NotionClient.query_data_source = query_data_source_tracking_references
    a1.GitHubClient.get_pr = get_pr_tolerating_historical_not_found
    a1.detect_a1_02 = detect_a1_02_contextual
    try:
        return a1.main()
    finally:
        a1.NotionClient.query_data_source = _ORIGINAL_QUERY_DATA_SOURCE
        a1.GitHubClient.get_pr = _ORIGINAL_GET_PR
        a1.detect_a1_02 = _ORIGINAL_DETECT_A1_02


if __name__ == "__main__":
    raise SystemExit(main())
