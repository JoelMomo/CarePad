#!/usr/bin/env python3
"""Deterministic A1 operational-risk detector for CarePad.

A1 never diagnoses specialist health. It only emits REVISAR SALUD when one of
four cross-source contradictions is demonstrable. Missing/ambiguous evidence is
silence by design. Infrastructure unavailability is a separate technical
failure and is never a health signal.
"""

from __future__ import annotations

import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable, Mapping

NOTION_VERSION = "2026-03-11"
COORDINATION_DATA_SOURCE = "280e1e03-d9a1-4eb1-8c45-56e0e1b066b9"
QA_DATA_SOURCE = "e1136a36-aba5-41dc-bca5-4cf1d392e0a3"
SPECIALISTS_DATA_SOURCE = "53098d95-2f24-40ff-930a-26ef65aab673"
ANDROID_CI_WORKFLOW = "android-ci.yml"

EXIT_OK = 0
EXIT_ALERT = 2
EXIT_UNAVAILABLE = 3

ACTIVE_COORDINATION_STATES = {"Abierto", "En curso", "Esperando"}
CLOSED_COORDINATION_STATES = {"Resuelto", "Archivado"}
HISTORICAL_QA_STATES = {"Resuelto", "Descartado"}

PR_PATTERNS = (
    re.compile(r"\bPR\s*#(\d+)\b", re.IGNORECASE),
    re.compile(r"/pull/(\d+)(?:\b|/)", re.IGNORECASE),
)
HEAD_PATTERN = re.compile(
    r"\bHEAD(?:\s+(?:actual|exacto|vigente|autorizado))?\s*(?:=|:|@)?\s*`?([0-9a-f]{40})`?",
    re.IGNORECASE,
)
QA_SHA_PATTERN = re.compile(r"(?:@|HEAD\s*(?:=|:)?|SHA\s*(?:=|:)?)\s*`?([0-9a-f]{40})`?", re.IGNORECASE)
CI_PATTERN = re.compile(
    r"(?:Android\s+)?CI\s*#(\d+)\s*(?:[:/\-]\s*)?"
    r"(SUCCESS|FAILURE|FAILED|CANCELLED|CANCELED|IN_PROGRESS|PENDING|EN\s+CURSO|PENDIENTE)?",
    re.IGNORECASE,
)
HISTORICAL_PATTERN = re.compile(r"\b(hist[oó]ric[oa]|legacy|legado|referencia\s+hist[oó]rica)\b", re.IGNORECASE)
STALE_OPEN_PATTERN = re.compile(
    r"\b(abiert[oa]|open|draft|no\s+fusionad[oa]|sin\s+fusionar|"
    r"pendiente\s+de\s+(?:merge|fusionar)|merge\s+pendiente)\b",
    re.IGNORECASE,
)
STALE_MERGE_ACTION_PATTERN = re.compile(
    r"\b(fusionar|mergear|hacer\s+merge|aprobar\s+(?:el\s+)?merge)\b",
    re.IGNORECASE,
)
QA_GATE_PATTERN = re.compile(
    r"(?:\b(?:QA|validaci[oó]n(?:\s+f[ií]sica)?|prueba(?:\s+f[ií]sica)?)\b.{0,100}"
    r"\b(?:PASS|valid(?:ad[oa]|aci[oó]n)|aprob(?:ad[oa]|aci[oó]n)|gate)\b)"
    r"|(?:\b(?:PASS|valid(?:ad[oa]|aci[oó]n)|aprob(?:ad[oa]|aci[oó]n)|gate)\b.{0,100}"
    r"\b(?:QA|validaci[oó]n(?:\s+f[ií]sica)?)\b)",
    re.IGNORECASE | re.DOTALL,
)


class OperationalDataUnavailable(RuntimeError):
    """Live evidence could not be read reliably. This is not an A1 signal."""


@dataclass(frozen=True)
class Alert:
    signal: str
    area: str
    target: str
    reason: str
    evidence: str

    def text(self) -> str:
        return (
            f"REVISAR SALUD — {self.target}\n"
            f"Señal: {self.signal}\n"
            f"Motivo: {self.reason}\n"
            f"Evidencia: {self.evidence}\n"
            f"Auditar: {self.target}"
        )


def parse_time(value: str | None) -> datetime | None:
    if not value:
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None


def text_of(record: Mapping[str, Any]) -> str:
    return "\n".join(
        str(record.get(key) or "")
        for key in ("github_ref", "summary", "next_step", "subject")
    )


def unique_value(values: Iterable[Any]) -> Any | None:
    unique = []
    for value in values:
        if value is None:
            continue
        if value not in unique:
            unique.append(value)
    return unique[0] if len(unique) == 1 else None


def parse_pr_number(*texts: str | None) -> int | None:
    found: list[int] = []
    for text in texts:
        if not text:
            continue
        for pattern in PR_PATTERNS:
            found.extend(int(match.group(1)) for match in pattern.finditer(text))
    return unique_value(found)


def parse_claimed_head(*texts: str | None) -> str | None:
    found: list[str] = []
    for text in texts:
        if text:
            found.extend(match.group(1).lower() for match in HEAD_PATTERN.finditer(text))
    return unique_value(found)


def parse_qa_sha(text: str | None) -> str | None:
    if not text:
        return None
    found = [match.group(1).lower() for match in QA_SHA_PATTERN.finditer(text)]
    if not found:
        found = re.findall(r"@\s*`?([0-9a-f]{40})`?", text, re.IGNORECASE)
    return unique_value([value.lower() for value in found])


def parse_ci_claims(*texts: str | None) -> list[tuple[int, str | None]]:
    claims: list[tuple[int, str | None]] = []
    for text in texts:
        if not text:
            continue
        for match in CI_PATTERN.finditer(text):
            status = match.group(2)
            normalized = status.upper().replace(" ", "_") if status else None
            if normalized == "FAILED":
                normalized = "FAILURE"
            if normalized == "CANCELED":
                normalized = "CANCELLED"
            claims.append((int(match.group(1)), normalized))
    deduped: list[tuple[int, str | None]] = []
    for claim in claims:
        if claim not in deduped:
            deduped.append(claim)
    return deduped


def is_historical(record: Mapping[str, Any]) -> bool:
    return bool(HISTORICAL_PATTERN.search(text_of(record)))


def is_documentation_path(path: str) -> bool:
    normalized = path.lower().lstrip("./")
    name = normalized.rsplit("/", 1)[-1]
    if normalized.startswith(("docs/", "documentation/")):
        return True
    if normalized.startswith(".github/") and "template" in normalized:
        return True
    if name in {"license", "license.txt", "copying", "notice", "readme"}:
        return True
    return name.endswith((".md", ".mdx", ".rst", ".adoc", ".txt"))


def material_files(files: Iterable[str] | None) -> list[str] | None:
    if files is None:
        return None
    return [path for path in files if not is_documentation_path(path)]


def comparison_files(snapshot: Mapping[str, Any], base: str, head: str) -> list[str] | None:
    return snapshot.get("comparisons", {}).get(f"{base}..{head}")


def pr_for(snapshot: Mapping[str, Any], number: int | None) -> Mapping[str, Any] | None:
    if number is None:
        return None
    return snapshot.get("prs", {}).get(str(number)) or snapshot.get("prs", {}).get(number)


def run_for(snapshot: Mapping[str, Any], number: int) -> Mapping[str, Any] | None:
    return snapshot.get("runs", {}).get(str(number)) or snapshot.get("runs", {}).get(number)


def head_runs_for(snapshot: Mapping[str, Any], head_sha: str | None) -> list[Mapping[str, Any]]:
    if not head_sha:
        return []
    return list(snapshot.get("head_runs", {}).get(head_sha.lower(), []))


def same_pr(record: Mapping[str, Any], pr_number: int) -> bool:
    return parse_pr_number(record.get("github_ref"), record.get("pr_url"), record.get("version")) == pr_number


def area_of(record: Mapping[str, Any]) -> str | None:
    value = record.get("owner") or record.get("area")
    return str(value).strip() if value else None


def same_area(left: Mapping[str, Any], right: Mapping[str, Any]) -> bool:
    left_area = area_of(left)
    right_area = area_of(right)
    return bool(left_area and right_area and left_area.casefold() == right_area.casefold())


def resolve_target(area: str, specialists: Iterable[Mapping[str, Any]]) -> str:
    matches = [
        str(item.get("name"))
        for item in specialists
        if item.get("state") == "Activo"
        and str(item.get("area") or "").strip().casefold() == area.strip().casefold()
        and item.get("name")
    ]
    return matches[0] if len(matches) == 1 else area


def make_alert(
    signal: str,
    record: Mapping[str, Any],
    specialists: Iterable[Mapping[str, Any]],
    reason: str,
    evidence: str,
) -> Alert | None:
    area = area_of(record)
    if not area:
        return None
    return Alert(signal=signal, area=area, target=resolve_target(area, specialists), reason=reason, evidence=evidence)


def head_existed_by(snapshot: Mapping[str, Any], pr: Mapping[str, Any], head_sha: str, when: datetime) -> bool:
    pr_updated = parse_time(pr.get("updated_at"))
    if pr_updated and pr_updated <= when:
        return True
    for run in head_runs_for(snapshot, head_sha):
        created = parse_time(run.get("created_at"))
        if created and created <= when:
            return True
    return False


def ci_success_claim_invalid_at(
    run: Mapping[str, Any],
    expected_head: str | None,
    handoff_time: datetime,
) -> str | None:
    created = parse_time(run.get("created_at"))
    if not created:
        return None

    if created > handoff_time:
        return "la ejecución citada todavía no existía cuando se actualizó el handoff"

    run_head = str(run.get("head_sha") or "").lower() or None
    if expected_head and run_head and run_head != expected_head:
        return f"la ejecución ya estaba asociada a HEAD {run_head}, no a {expected_head}"

    actual = str(run.get("conclusion") or run.get("status") or "").upper()
    if actual == "SUCCESS":
        return None

    updated = parse_time(run.get("updated_at"))
    if run.get("status") == "completed" and updated and updated <= handoff_time:
        return f"la ejecución ya había terminado con estado {actual or 'desconocido'}"

    return None


def qa_gate_reuse_record(
    snapshot: Mapping[str, Any],
    pr_number: int,
    qa_sha: str,
    current_head: str,
) -> Mapping[str, Any] | None:
    validated_shas = {
        parsed
        for item in snapshot.get("qa", [])
        if item.get("validated_real")
        and item.get("state") not in HISTORICAL_QA_STATES
        and same_pr(item, pr_number)
        and (parsed := parse_qa_sha(item.get("version")))
    }
    if validated_shas != {qa_sha}:
        return None

    matches: list[Mapping[str, Any]] = []
    for source in snapshot.get("coordination", []):
        if source.get("state") not in ACTIVE_COORDINATION_STATES or is_historical(source):
            continue
        if not same_pr(source, pr_number):
            continue
        claimed_head = parse_claimed_head(source.get("github_ref"), source.get("summary"), source.get("next_step"))
        if claimed_head != current_head:
            continue
        if QA_GATE_PATTERN.search(text_of(source)):
            matches.append(source)
    return matches[0] if len(matches) == 1 else None


def latest_material_head_activity_after(
    snapshot: Mapping[str, Any],
    head_sha: str,
    after: datetime,
) -> Mapping[str, Any] | None:
    candidates = []
    for run in head_runs_for(snapshot, head_sha):
        created = parse_time(run.get("created_at"))
        if not created or created <= after:
            continue
        if run.get("event") not in {"pull_request", "push"}:
            continue
        candidates.append(run)
    if not candidates:
        return None
    return max(candidates, key=lambda item: parse_time(item.get("created_at")) or datetime.min.replace(tzinfo=timezone.utc))


def has_canonical_reactivation(
    snapshot: Mapping[str, Any],
    closed_record: Mapping[str, Any],
    pr_number: int,
    closed_at: datetime,
) -> bool:
    pr = pr_for(snapshot, pr_number)
    if pr:
        for event in pr.get("events", []):
            event_time = parse_time(event.get("created_at"))
            if event.get("event") == "reopened" and event_time and event_time > closed_at:
                return True

    for other in snapshot.get("coordination", []):
        if other is closed_record:
            continue
        edited = parse_time(other.get("last_edited"))
        if not edited or edited <= closed_at:
            continue
        if same_pr(other, pr_number) and (
            other.get("state") in ACTIVE_COORDINATION_STATES or other.get("type") == "Decisión"
        ):
            return True
        if other.get("type") == "Decisión" and same_area(closed_record, other):
            return True

    for item in snapshot.get("qa", []):
        edited = parse_time(item.get("last_edited"))
        if not edited or edited <= closed_at or item.get("state") in HISTORICAL_QA_STATES:
            continue
        if same_pr(item, pr_number) or same_area(closed_record, item):
            return True
    return False


def detect_a1_01(snapshot: Mapping[str, Any]) -> list[Alert]:
    alerts: list[Alert] = []
    specialists = snapshot.get("specialists", [])
    for record in snapshot.get("coordination", []):
        if record.get("type") != "Handoff" or record.get("state") not in ACTIVE_COORDINATION_STATES:
            continue
        if is_historical(record):
            continue
        handoff_time = parse_time(record.get("last_edited"))
        if not handoff_time:
            continue

        pr_number = parse_pr_number(record.get("github_ref"), record.get("summary"), record.get("next_step"))
        pr = pr_for(snapshot, pr_number)
        if not pr:
            continue

        claimed_head = parse_claimed_head(record.get("github_ref"), record.get("summary"), record.get("next_step"))
        current_head = str(pr.get("head_sha") or "").lower() or None
        if claimed_head and current_head and claimed_head != current_head:
            files = comparison_files(snapshot, claimed_head, current_head)
            material = material_files(files)
            if material and head_existed_by(snapshot, pr, current_head, handoff_time):
                alert = make_alert(
                    "A1-01",
                    record,
                    specialists,
                    f"El handoff fijó HEAD {claimed_head} cuando PR #{pr_number} ya estaba en {current_head} con cambios no documentales.",
                    f"handoff={record.get('last_edited')}; compare {claimed_head}..{current_head}: {', '.join(material[:8])}",
                )
                if alert:
                    alerts.append(alert)
                continue

        for run_number, claimed_status in parse_ci_claims(
            record.get("github_ref"), record.get("summary"), record.get("next_step")
        ):
            if claimed_status != "SUCCESS":
                continue
            run = run_for(snapshot, run_number)
            if not run:
                continue
            expected_head = claimed_head or current_head
            invalid = ci_success_claim_invalid_at(run, expected_head, handoff_time)
            if not invalid:
                continue
            alert = make_alert(
                "A1-01",
                record,
                specialists,
                f"El handoff usa CI #{run_number} como gate SUCCESS, pero esa invalidez ya existía al actualizar el handoff: {invalid}.",
                f"handoff={record.get('last_edited')}; CI #{run_number}: status={run.get('status')}, conclusion={run.get('conclusion')}, head={run.get('head_sha')}, created={run.get('created_at')}, updated={run.get('updated_at')}",
            )
            if alert:
                alerts.append(alert)
            break
    return alerts


def detect_a1_02(snapshot: Mapping[str, Any]) -> list[Alert]:
    alerts: list[Alert] = []
    specialists = snapshot.get("specialists", [])
    for record in snapshot.get("coordination", []):
        if is_historical(record):
            continue
        edited = parse_time(record.get("last_edited"))
        if not edited:
            continue
        pr_number = parse_pr_number(record.get("github_ref"), record.get("summary"), record.get("next_step"))
        pr = pr_for(snapshot, pr_number)
        combined = text_of(record)
        if pr:
            terminal_raw = pr.get("merged_at") or (pr.get("closed_at") if pr.get("state") == "closed" else None)
            terminal_at = parse_time(terminal_raw)
            stale_pr_claim = bool(
                STALE_OPEN_PATTERN.search(combined)
                or STALE_MERGE_ACTION_PATTERN.search(str(record.get("next_step") or ""))
            )
            if terminal_at and edited > terminal_at and stale_pr_claim:
                alert = make_alert(
                    "A1-02",
                    record,
                    specialists,
                    f"El registro fue actualizado después del cierre/merge de PR #{pr_number}, pero conserva una afirmación o acción incompatible con ese evento.",
                    f"evento={terminal_raw}; registro actualizado={record.get('last_edited')}; texto={combined[:500]}",
                )
                if alert:
                    alerts.append(alert)
                continue

        for run_number, claimed_status in parse_ci_claims(
            record.get("github_ref"), record.get("summary"), record.get("next_step")
        ):
            if claimed_status not in {"PENDING", "IN_PROGRESS", "EN_CURSO", "PENDIENTE"}:
                continue
            run = run_for(snapshot, run_number)
            completed_at = parse_time(run.get("updated_at")) if run and run.get("status") == "completed" else None
            if run and completed_at and edited > completed_at:
                alert = make_alert(
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


def detect_a1_03(snapshot: Mapping[str, Any]) -> list[Alert]:
    alerts: list[Alert] = []
    specialists = snapshot.get("specialists", [])
    for record in snapshot.get("qa", []):
        if not record.get("validated_real") or record.get("state") in HISTORICAL_QA_STATES:
            continue
        pr_number = parse_pr_number(record.get("pr_url"), record.get("version"))
        qa_sha = parse_qa_sha(record.get("version"))
        pr = pr_for(snapshot, pr_number)
        current_head = str(pr.get("head_sha") or "").lower() if pr else ""
        if not (pr_number and qa_sha and current_head) or qa_sha == current_head:
            continue
        files = comparison_files(snapshot, qa_sha, current_head)
        material = material_files(files)
        if not material:
            continue
        gate_source = qa_gate_reuse_record(snapshot, pr_number, qa_sha, current_head)
        if not gate_source:
            continue
        alert = make_alert(
            "A1-03",
            record,
            specialists,
            f"Una fuente activa está reutilizando la validación QA de {qa_sha} como gate de HEAD {current_head} en PR #{pr_number}, pese a cambios no documentales.",
            f"fuente activa={gate_source.get('subject') or gate_source.get('id')}; compare {qa_sha}..{current_head}: {', '.join(material[:8])}",
        )
        if alert:
            alerts.append(alert)
    return alerts


def detect_a1_04(snapshot: Mapping[str, Any]) -> list[Alert]:
    alerts: list[Alert] = []
    specialists = snapshot.get("specialists", [])
    for record in snapshot.get("coordination", []):
        if record.get("state") not in CLOSED_COORDINATION_STATES or is_historical(record):
            continue
        pr_number = parse_pr_number(record.get("github_ref"), record.get("summary"), record.get("next_step"))
        pr = pr_for(snapshot, pr_number)
        if not pr or pr.get("state") != "open":
            continue
        closed_at = parse_time(record.get("last_edited"))
        current_head = str(pr.get("head_sha") or "").lower()
        if not closed_at or not current_head:
            continue
        activity = latest_material_head_activity_after(snapshot, current_head, closed_at)
        if not activity:
            continue
        if has_canonical_reactivation(snapshot, record, pr_number, closed_at):
            continue
        alert = make_alert(
            "A1-04",
            record,
            specialists,
            f"El trabajo de PR #{pr_number} volvió a tener actividad GitHub después del cierre canónico, mientras las fuentes propietarias siguen cerradas y no hay trigger canónico de reactivación.",
            f"cierre={record.get('last_edited')}; HEAD={current_head}; Android CI #{activity.get('run_number')} event={activity.get('event')} created={activity.get('created_at')}",
        )
        if alert:
            alerts.append(alert)
    return alerts


def detect(snapshot: Mapping[str, Any]) -> list[Alert]:
    alerts = []
    for detector in (detect_a1_01, detect_a1_02, detect_a1_03, detect_a1_04):
        alerts.extend(detector(snapshot))
    unique: dict[tuple[str, str, str], Alert] = {}
    for alert in alerts:
        unique[(alert.signal, alert.area, alert.evidence)] = alert
    return sorted(unique.values(), key=lambda item: (item.signal, item.area, item.evidence))


class JsonClient:
    def __init__(self, base_url: str, headers: Mapping[str, str]):
        self.base_url = base_url.rstrip("/")
        self.headers = dict(headers)

    def request(self, path: str, *, method: str = "GET", body: Mapping[str, Any] | None = None) -> Any:
        data = json.dumps(body).encode("utf-8") if body is not None else None
        request = urllib.request.Request(
            f"{self.base_url}{path}",
            data=data,
            method=method,
            headers={**self.headers, "Content-Type": "application/json", "User-Agent": "CarePad-A1"},
        )
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                return json.load(response)
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
            raise OperationalDataUnavailable(f"API request failed for {path}: {exc}") from exc


class NotionClient:
    def __init__(self, token: str):
        self.client = JsonClient(
            "https://api.notion.com/v1",
            {"Authorization": f"Bearer {token}", "Notion-Version": NOTION_VERSION},
        )

    def query_data_source(self, data_source_id: str) -> list[Mapping[str, Any]]:
        results: list[Mapping[str, Any]] = []
        cursor: str | None = None
        while True:
            body: dict[str, Any] = {"page_size": 100, "result_type": "page"}
            if cursor:
                body["start_cursor"] = cursor
            payload = self.client.request(f"/data_sources/{data_source_id}/query", method="POST", body=body)
            results.extend(item for item in payload.get("results", []) if item.get("object") == "page")
            if not payload.get("has_more"):
                return results
            cursor = payload.get("next_cursor")
            if not cursor:
                raise OperationalDataUnavailable("Notion pagination returned has_more without next_cursor")


class GitHubClient:
    def __init__(self, token: str, repository: str, api_url: str = "https://api.github.com"):
        self.repository = repository
        self.client = JsonClient(
            api_url,
            {
                "Authorization": f"Bearer {token}",
                "Accept": "application/vnd.github+json",
                "X-GitHub-Api-Version": "2022-11-28",
            },
        )

    def get_pr(self, number: int) -> Mapping[str, Any]:
        return self.client.request(f"/repos/{self.repository}/pulls/{number}")

    def get_pr_events(self, number: int) -> list[Mapping[str, Any]]:
        events: list[Mapping[str, Any]] = []
        page = 1
        while page <= 5:
            payload = self.client.request(f"/repos/{self.repository}/issues/{number}/events?per_page=100&page={page}")
            if not isinstance(payload, list):
                raise OperationalDataUnavailable(f"Unexpected GitHub events payload for PR #{number}")
            events.extend(payload)
            if len(payload) < 100:
                break
            page += 1
        return events

    def get_workflow_run(self, run_number: int) -> Mapping[str, Any] | None:
        page = 1
        while page <= 10:
            payload = self.client.request(
                f"/repos/{self.repository}/actions/workflows/{ANDROID_CI_WORKFLOW}/runs?per_page=100&page={page}"
            )
            runs = payload.get("workflow_runs", [])
            for run in runs:
                if run.get("run_number") == run_number:
                    return run
            if len(runs) < 100:
                return None
            numeric = [item.get("run_number") for item in runs if isinstance(item.get("run_number"), int)]
            if numeric and min(numeric) < run_number:
                return None
            page += 1
        return None

    def get_workflow_runs_for_head(self, head_sha: str) -> list[Mapping[str, Any]]:
        encoded = urllib.parse.quote(head_sha, safe="")
        payload = self.client.request(
            f"/repos/{self.repository}/actions/workflows/{ANDROID_CI_WORKFLOW}/runs?head_sha={encoded}&per_page=100"
        )
        runs = payload.get("workflow_runs", [])
        if not isinstance(runs, list):
            raise OperationalDataUnavailable(f"Unexpected workflow-runs payload for HEAD {head_sha}")
        return runs

    def compare_files(self, base: str, head: str) -> list[str] | None:
        path = f"/repos/{self.repository}/compare/{urllib.parse.quote(base, safe='')}...{urllib.parse.quote(head, safe='')}"
        payload = self.client.request(path)
        files = payload.get("files")
        if files is None:
            return None
        return [str(item.get("filename")) for item in files if item.get("filename")]


def property_value(page: Mapping[str, Any], name: str) -> Any:
    prop = page.get("properties", {}).get(name)
    if not prop:
        return None
    kind = prop.get("type")
    value = prop.get(kind) if kind else None
    if kind in {"title", "rich_text"}:
        return "".join(item.get("plain_text", "") for item in (value or []))
    if kind == "select":
        return value.get("name") if value else None
    if kind == "multi_select":
        return [item.get("name") for item in (value or []) if item.get("name")]
    return value


def normalize_coordination(page: Mapping[str, Any]) -> dict[str, Any]:
    return {
        "id": page.get("id"),
        "subject": property_value(page, "Asunto"),
        "type": property_value(page, "Tipo"),
        "state": property_value(page, "Estado"),
        "owner": property_value(page, "Área propietaria"),
        "affected": property_value(page, "Afecta a") or [],
        "github_ref": property_value(page, "GitHub / referencia"),
        "summary": property_value(page, "Resumen"),
        "next_step": property_value(page, "Siguiente paso"),
        "last_edited": page.get("last_edited_time") or property_value(page, "Actualizado"),
    }


def normalize_qa(page: Mapping[str, Any]) -> dict[str, Any]:
    return {
        "id": page.get("id"),
        "subject": property_value(page, "Bug"),
        "state": property_value(page, "Estado"),
        "validated_real": bool(property_value(page, "Validado en dispositivo real")),
        "version": property_value(page, "Versión corregida"),
        "pr_url": property_value(page, "PR / Issue"),
        "regression": property_value(page, "Regresión"),
        "area": property_value(page, "Área responsable"),
        "last_edited": page.get("last_edited_time") or property_value(page, "Última actualización"),
    }


def normalize_specialist(page: Mapping[str, Any]) -> dict[str, Any]:
    return {
        "name": property_value(page, "Especialista"),
        "area": property_value(page, "Área"),
        "state": property_value(page, "Estado"),
        "generation": property_value(page, "Generación"),
    }


def normalize_run(raw: Mapping[str, Any]) -> dict[str, Any]:
    return {
        "run_number": raw.get("run_number"),
        "status": raw.get("status"),
        "conclusion": raw.get("conclusion"),
        "head_sha": raw.get("head_sha"),
        "event": raw.get("event"),
        "created_at": raw.get("created_at"),
        "updated_at": raw.get("updated_at"),
    }


def collect_snapshot(env: Mapping[str, str]) -> dict[str, Any]:
    github_token = env.get("GITHUB_TOKEN")
    notion_token = env.get("NOTION_A1_READ_TOKEN")
    repository = env.get("GITHUB_REPOSITORY")
    if not github_token or not notion_token or not repository:
        raise OperationalDataUnavailable("Required read credentials/repository identity are unavailable")

    notion = NotionClient(notion_token)
    github = GitHubClient(github_token, repository, env.get("GITHUB_API_URL", "https://api.github.com"))

    coordination = [normalize_coordination(page) for page in notion.query_data_source(COORDINATION_DATA_SOURCE)]
    qa = [normalize_qa(page) for page in notion.query_data_source(QA_DATA_SOURCE)]
    specialists = [normalize_specialist(page) for page in notion.query_data_source(SPECIALISTS_DATA_SOURCE)]

    pr_numbers = {
        number
        for record in [*coordination, *qa]
        if (number := parse_pr_number(record.get("github_ref"), record.get("pr_url"), record.get("version"))) is not None
    }
    prs: dict[str, Any] = {}
    for number in sorted(pr_numbers):
        raw = github.get_pr(number)
        pr = {
            "number": number,
            "state": raw.get("state"),
            "merged": bool(raw.get("merged")),
            "head_sha": (raw.get("head") or {}).get("sha"),
            "base_sha": (raw.get("base") or {}).get("sha"),
            "merged_at": raw.get("merged_at"),
            "closed_at": raw.get("closed_at"),
            "updated_at": raw.get("updated_at"),
            "events": [],
        }
        if pr["state"] == "open" and any(
            item.get("state") in CLOSED_COORDINATION_STATES and same_pr(item, number)
            for item in coordination
        ):
            pr["events"] = [
                {"event": event.get("event"), "created_at": event.get("created_at")}
                for event in github.get_pr_events(number)
                if event.get("event") in {"closed", "reopened", "merged"}
            ]
        prs[str(number)] = pr

    run_numbers = {
        run_number
        for record in coordination
        for run_number, _ in parse_ci_claims(record.get("github_ref"), record.get("summary"), record.get("next_step"))
    }
    runs: dict[str, Any] = {}
    for number in sorted(run_numbers):
        raw = github.get_workflow_run(number)
        if raw:
            runs[str(number)] = normalize_run(raw)

    head_shas_needed: set[str] = set()
    for record in coordination:
        pr_number = parse_pr_number(record.get("github_ref"), record.get("summary"), record.get("next_step"))
        pr = prs.get(str(pr_number)) if pr_number else None
        current = str(pr.get("head_sha") or "").lower() if pr else ""
        claimed = parse_claimed_head(record.get("github_ref"), record.get("summary"), record.get("next_step"))
        needs_a1_01_time = (
            record.get("type") == "Handoff"
            and record.get("state") in ACTIVE_COORDINATION_STATES
            and claimed
            and current
            and claimed != current
        )
        needs_a1_04_activity = record.get("state") in CLOSED_COORDINATION_STATES and pr and pr.get("state") == "open"
        if current and (needs_a1_01_time or needs_a1_04_activity):
            head_shas_needed.add(current)

    head_runs: dict[str, list[dict[str, Any]]] = {}
    for head_sha in sorted(head_shas_needed):
        head_runs[head_sha] = [normalize_run(raw) for raw in github.get_workflow_runs_for_head(head_sha)]

    comparisons: dict[str, list[str] | None] = {}
    for record in coordination:
        pr_number = parse_pr_number(record.get("github_ref"), record.get("summary"), record.get("next_step"))
        pr = prs.get(str(pr_number)) if pr_number else None
        claimed = parse_claimed_head(record.get("github_ref"), record.get("summary"), record.get("next_step"))
        current = str(pr.get("head_sha") or "").lower() if pr else ""
        if claimed and current and claimed != current:
            comparisons[f"{claimed}..{current}"] = github.compare_files(claimed, current)
    for record in qa:
        pr_number = parse_pr_number(record.get("pr_url"), record.get("version"))
        pr = prs.get(str(pr_number)) if pr_number else None
        qa_sha = parse_qa_sha(record.get("version"))
        current = str(pr.get("head_sha") or "").lower() if pr else ""
        if qa_sha and current and qa_sha != current:
            comparisons[f"{qa_sha}..{current}"] = github.compare_files(qa_sha, current)

    return {
        "repository": repository,
        "coordination": coordination,
        "qa": qa,
        "specialists": specialists,
        "prs": prs,
        "runs": runs,
        "head_runs": head_runs,
        "comparisons": comparisons,
    }


def write_outputs(alerts: list[Alert], repository: str, output_path: Path, summary_path: Path | None) -> None:
    payload = {
        "schema": "carepad.a1-alert.v1",
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "repository": repository,
        "alerts": [
            {
                "signal": alert.signal,
                "area": alert.area,
                "target": alert.target,
                "reason": alert.reason,
                "evidence": alert.evidence,
                "text": alert.text(),
            }
            for alert in alerts
        ],
    }
    output_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if summary_path:
        with summary_path.open("a", encoding="utf-8") as summary:
            summary.write("## A1 — riesgo operacional\n\n")
            for alert in alerts:
                summary.write(alert.text() + "\n\n")


def run_live(
    env: Mapping[str, str] | None = None,
    *,
    output_path: Path | None = None,
    summary_path: Path | None = None,
) -> int:
    env = os.environ if env is None else env
    output_path = output_path or Path("a1-alert.json")
    if summary_path is None and env.get("GITHUB_STEP_SUMMARY"):
        summary_path = Path(env["GITHUB_STEP_SUMMARY"])
    try:
        snapshot = collect_snapshot(env)
        alerts = detect(snapshot)
    except OperationalDataUnavailable as exc:
        print(f"A1 unavailable; no health signal emitted: {exc}", file=sys.stderr)
        return EXIT_UNAVAILABLE

    if not alerts:
        return EXIT_OK
    write_outputs(alerts, snapshot.get("repository", env.get("GITHUB_REPOSITORY", "")), output_path, summary_path)
    for alert in alerts:
        print(alert.text())
        print()
    return EXIT_ALERT


def main() -> int:
    return run_live()


if __name__ == "__main__":
    raise SystemExit(main())
