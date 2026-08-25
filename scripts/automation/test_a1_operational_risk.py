#!/usr/bin/env python3

import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

import a1_operational_risk as a1

OLD = "1" * 40
NEW = "2" * 40


def specialist(area="Código y arquitectura", name="Código y arquitectura • 8", state="Activo"):
    return {"area": area, "name": name, "state": state, "generation": "8"}


def coord(**overrides):
    item = {
        "subject": "handoff",
        "type": "Handoff",
        "state": "En curso",
        "owner": "Código y arquitectura",
        "github_ref": f"PR #12 · HEAD {OLD}",
        "summary": "",
        "next_step": "Validar PR #12",
        "last_edited": "2026-08-25T00:00:00Z",
    }
    item.update(overrides)
    return item


def qa(**overrides):
    item = {
        "subject": "BUG-X",
        "state": "En corrección",
        "validated_real": True,
        "version": f"PR #12 @ {OLD}",
        "pr_url": "https://github.com/JoelMomo/CarePad/pull/12",
        "regression": "No",
        "area": "Código y arquitectura",
        "last_edited": "2026-08-25T00:00:00Z",
    }
    item.update(overrides)
    return item


def pr(**overrides):
    item = {
        "number": 12,
        "state": "open",
        "merged": False,
        "head_sha": OLD,
        "merged_at": None,
        "closed_at": None,
        "updated_at": "2026-08-24T23:59:00Z",
        "events": [],
    }
    item.update(overrides)
    return item


def head_run(**overrides):
    item = {
        "run_number": 90,
        "status": "completed",
        "conclusion": "success",
        "head_sha": NEW,
        "event": "pull_request",
        "created_at": "2026-08-24T23:59:00Z",
        "updated_at": "2026-08-24T23:59:30Z",
    }
    item.update(overrides)
    return item


def snapshot(
    *, coordination=None, qa_rows=None, prs=None, runs=None, head_runs=None,
    comparisons=None, specialists=None,
):
    return {
        "repository": "JoelMomo/CarePad",
        "coordination": coordination or [],
        "qa": qa_rows or [],
        "prs": prs or {},
        "runs": runs or {},
        "head_runs": head_runs or {},
        "comparisons": comparisons or {},
        "specialists": specialists if specialists is not None else [specialist()],
    }


def qa_gate_for_new_head(**overrides):
    item = coord(
        github_ref=f"PR #12 · HEAD {NEW} · QA PASS",
        summary="La validación física QA se usa como gate del HEAD actual.",
        next_step="Aprobar gate QA",
    )
    item.update(overrides)
    return item


class A1SignalTests(unittest.TestCase):
    def test_a1_01_positive_head_gate_was_already_invalid_at_handoff(self):
        data = snapshot(
            coordination=[coord()],
            prs={"12": pr(head_sha=NEW)},
            comparisons={f"{OLD}..{NEW}": ["app/src/main/Foo.kt"]},
        )
        self.assertEqual(["A1-01"], [alert.signal for alert in a1.detect(data)])

    def test_a1_01_negative_head_changed_after_correct_handoff(self):
        data = snapshot(
            coordination=[coord()],
            prs={"12": pr(head_sha=NEW, updated_at="2026-08-25T00:10:00Z")},
            head_runs={NEW: [head_run(created_at="2026-08-25T00:10:00Z", updated_at="2026-08-25T00:11:00Z")]},
            comparisons={f"{OLD}..{NEW}": ["app/src/main/Foo.kt"]},
        )
        self.assertEqual([], [alert for alert in a1.detect(data) if alert.signal == "A1-01"])

    def test_a1_01_negative_merge_or_close_after_correct_handoff(self):
        data = snapshot(
            coordination=[coord(github_ref="PR #12", next_step="Validar PR #12")],
            prs={"12": pr(state="closed", merged=True, merged_at="2026-08-25T00:10:00Z", closed_at="2026-08-25T00:10:00Z")},
        )
        self.assertEqual([], [alert for alert in a1.detect(data) if alert.signal == "A1-01"])

    def test_a1_01_positive_claimed_success_ci_was_already_failure(self):
        data = snapshot(
            coordination=[coord(github_ref=f"PR #12 · HEAD {OLD} · Android CI #88 SUCCESS")],
            prs={"12": pr()},
            runs={"88": {
                "status": "completed", "conclusion": "failure", "head_sha": OLD,
                "created_at": "2026-08-24T23:50:00Z", "updated_at": "2026-08-24T23:59:00Z",
            }},
        )
        self.assertEqual(["A1-01"], [alert.signal for alert in a1.detect(data)])

    def test_a1_01_negative_ci_invalidity_not_provable_at_handoff(self):
        data = snapshot(
            coordination=[coord(github_ref=f"PR #12 · HEAD {OLD} · Android CI #88 SUCCESS")],
            prs={"12": pr()},
            runs={"88": {
                "status": "completed", "conclusion": "failure", "head_sha": OLD,
                "created_at": "2026-08-24T23:50:00Z", "updated_at": "2026-08-25T00:10:00Z",
            }},
        )
        self.assertEqual([], [alert for alert in a1.detect(data) if alert.signal == "A1-01"])

    def test_historical_reference_is_not_a_false_positive(self):
        data = snapshot(
            coordination=[coord(summary=f"Referencia histórica: HEAD {OLD}")],
            prs={"12": pr(head_sha=NEW)},
            comparisons={f"{OLD}..{NEW}": ["app/src/main/Foo.kt"]},
        )
        self.assertEqual([], a1.detect(data))

    def test_red_ci_alone_is_not_health(self):
        data = snapshot(
            coordination=[coord(github_ref="PR #12", next_step="Revisar PR #12")],
            prs={"12": pr()},
            runs={"88": {"status": "completed", "conclusion": "failure", "head_sha": OLD}},
        )
        self.assertEqual([], a1.detect(data))

    def test_a1_02_positive_record_updated_after_merge_but_still_says_open(self):
        data = snapshot(
            coordination=[coord(type="Seguimiento", github_ref="PR #12 sigue abierto/draft", next_step="", last_edited="2026-08-25T01:00:00Z")],
            prs={"12": pr(state="closed", merged=True, merged_at="2026-08-25T00:30:00Z", closed_at="2026-08-25T00:30:00Z")},
        )
        self.assertEqual(["A1-02"], [alert.signal for alert in a1.detect(data)])

    def test_a1_02_negative_updated_record_matches_merge(self):
        data = snapshot(
            coordination=[coord(type="Seguimiento", github_ref="PR #12 fusionado", next_step="", last_edited="2026-08-25T01:00:00Z")],
            prs={"12": pr(state="closed", merged=True, merged_at="2026-08-25T00:30:00Z", closed_at="2026-08-25T00:30:00Z")},
        )
        self.assertEqual([], a1.detect(data))

    def test_a1_02_negative_explicitly_negates_old_open_state(self):
        data = snapshot(
            coordination=[coord(
                type="Seguimiento",
                github_ref="PR #12 ya no está abierto/draft; quedó fusionado",
                next_step="",
                last_edited="2026-08-25T01:00:00Z",
            )],
            prs={"12": pr(
                state="closed",
                merged=True,
                merged_at="2026-08-25T00:30:00Z",
                closed_at="2026-08-25T00:30:00Z",
            )},
        )
        self.assertEqual([], [alert for alert in a1.detect(data) if alert.signal == "A1-02"])

    def test_a1_02_negative_explicitly_negates_old_ci_state(self):
        data = snapshot(
            coordination=[coord(
                type="Seguimiento",
                github_ref="PR #12 · Android CI #88 PENDING; ya no está pendiente",
                next_step="",
                last_edited="2026-08-25T01:00:00Z",
            )],
            prs={"12": pr()},
            runs={"88": {
                "status": "completed",
                "conclusion": "success",
                "head_sha": OLD,
                "created_at": "2026-08-25T00:10:00Z",
                "updated_at": "2026-08-25T00:30:00Z",
            }},
        )
        self.assertEqual([], [alert for alert in a1.detect(data) if alert.signal == "A1-02"])

    def test_a1_03_positive_active_source_reuses_old_qa_as_new_head_gate(self):
        data = snapshot(
            coordination=[qa_gate_for_new_head()],
            qa_rows=[qa()],
            prs={"12": pr(head_sha=NEW)},
            comparisons={f"{OLD}..{NEW}": ["scripts/automation/a1.py"]},
        )
        self.assertEqual(["A1-03"], [alert.signal for alert in a1.detect(data)])

    def test_a1_03_negative_old_qa_and_new_head_without_gate_reuse(self):
        data = snapshot(
            qa_rows=[qa()],
            prs={"12": pr(head_sha=NEW)},
            comparisons={f"{OLD}..{NEW}": ["scripts/automation/a1.py"]},
        )
        self.assertEqual([], [alert for alert in a1.detect(data) if alert.signal == "A1-03"])

    def test_a1_03_negative_explicitly_denies_qa_gate_reuse(self):
        data = snapshot(
            coordination=[qa_gate_for_new_head(
                summary="La validación física QA anterior NO se usa como gate del HEAD actual; requiere revalidación.",
                next_step="Revalidar QA para el HEAD actual",
            )],
            qa_rows=[qa()],
            prs={"12": pr(head_sha=NEW)},
            comparisons={f"{OLD}..{NEW}": ["scripts/automation/a1.py"]},
        )
        self.assertEqual([], [alert for alert in a1.detect(data) if alert.signal == "A1-03"])

    def test_documentation_only_change_does_not_invalidate_qa(self):
        data = snapshot(
            coordination=[qa_gate_for_new_head()],
            qa_rows=[qa()],
            prs={"12": pr(head_sha=NEW)},
            comparisons={f"{OLD}..{NEW}": ["README.md", "docs/qa.md"]},
        )
        self.assertEqual([], a1.detect(data))

    def test_ambiguous_missing_compare_is_silent_for_qa(self):
        data = snapshot(
            coordination=[qa_gate_for_new_head()],
            qa_rows=[qa()],
            prs={"12": pr(head_sha=NEW)},
        )
        self.assertEqual([], a1.detect(data))

    def test_a1_04_positive_active_work_resurrected_while_sources_stay_closed(self):
        closed = coord(state="Resuelto", type="Seguimiento", github_ref="PR #12", next_step="", last_edited="2026-08-25T00:00:00Z")
        active_pr = pr(state="open", head_sha=NEW, events=[])
        data = snapshot(
            coordination=[closed],
            prs={"12": active_pr},
            head_runs={NEW: [head_run(run_number=91, created_at="2026-08-25T01:00:00Z", updated_at="2026-08-25T01:05:00Z")]},
        )
        self.assertEqual(["A1-04"], [alert.signal for alert in a1.detect(data)])

    def test_a1_04_negative_legitimate_github_reopen_is_canonical_trigger(self):
        closed = coord(state="Resuelto", type="Seguimiento", github_ref="PR #12", next_step="", last_edited="2026-08-25T00:00:00Z")
        active_pr = pr(state="open", head_sha=NEW, events=[{"event": "reopened", "created_at": "2026-08-25T00:30:00Z"}])
        data = snapshot(
            coordination=[closed],
            prs={"12": active_pr},
            head_runs={NEW: [head_run(run_number=91, created_at="2026-08-25T01:00:00Z")]},
        )
        self.assertEqual([], [alert for alert in a1.detect(data) if alert.signal == "A1-04"])

    def test_legitimate_reopen_with_regression_is_not_a1_04(self):
        closed = coord(state="Resuelto", type="Seguimiento", github_ref="PR #12", next_step="", last_edited="2026-08-25T00:00:00Z")
        active_pr = pr(state="open", head_sha=NEW, events=[{"event": "reopened", "created_at": "2026-08-25T00:30:00Z"}])
        regression = qa(
            validated_real=False,
            version="PR #12",
            regression="Sí",
            last_edited="2026-08-25T00:40:00Z",
        )
        data = snapshot(
            coordination=[closed], qa_rows=[regression], prs={"12": active_pr},
            head_runs={NEW: [head_run(run_number=91, created_at="2026-08-25T01:00:00Z")]},
        )
        self.assertEqual([], a1.detect(data))

    def test_a1_04_active_coordination_does_not_justify_reactivation(self):
        closed = coord(state="Resuelto", type="Seguimiento", github_ref="PR #12", next_step="", last_edited="2026-08-25T00:00:00Z")
        active = coord(github_ref="PR #12", last_edited="2026-08-25T00:30:00Z")
        active_pr = pr(state="open", head_sha=NEW, events=[])
        data = snapshot(
            coordination=[closed, active],
            prs={"12": active_pr},
        )
        self.assertEqual(["A1-04"], [alert.signal for alert in a1.detect(data) if alert.signal == "A1-04"])

    def test_a1_04_negative_explicit_decision_justifies_reactivation(self):
        closed = coord(state="Resuelto", type="Seguimiento", github_ref="PR #12", next_step="", last_edited="2026-08-25T00:00:00Z")
        decision = coord(
            type="Decisión",
            state="En curso",
            github_ref="PR #12",
            summary="Decisión explícita: reactivar el trabajo de PR #12.",
            next_step="",
            last_edited="2026-08-25T00:30:00Z",
        )
        active_pr = pr(state="open", head_sha=NEW, events=[])
        data = snapshot(
            coordination=[closed, decision], prs={"12": active_pr},
            head_runs={NEW: [head_run(run_number=91, created_at="2026-08-25T01:00:00Z")]},
        )
        self.assertEqual([], [alert for alert in a1.detect(data) if alert.signal == "A1-04"])

    def test_reactivation_with_new_qa_bug_is_not_a1_04(self):
        closed = coord(state="Resuelto", type="Seguimiento", github_ref="PR #12", next_step="", last_edited="2026-08-25T00:00:00Z")
        new_bug = qa(validated_real=False, version="PR #12", last_edited="2026-08-25T00:30:00Z")
        active_pr = pr(state="open", head_sha=NEW, events=[])
        data = snapshot(
            coordination=[closed], qa_rows=[new_bug], prs={"12": active_pr},
            head_runs={NEW: [head_run(run_number=91, created_at="2026-08-25T01:00:00Z")]},
        )
        self.assertEqual([], [alert for alert in a1.detect(data) if alert.signal == "A1-04"])

    def test_multiple_active_generations_audit_area_not_instance(self):
        data = snapshot(
            coordination=[coord()],
            prs={"12": pr(head_sha=NEW)},
            comparisons={f"{OLD}..{NEW}": ["app/src/main/Foo.kt"]},
            specialists=[specialist(name="Código • 8"), specialist(name="Código • 9")],
        )
        alerts = a1.detect(data)
        self.assertEqual("Código y arquitectura", alerts[0].target)

    def test_single_active_generation_resolves_exact_specialist(self):
        data = snapshot(
            coordination=[coord()],
            prs={"12": pr(head_sha=NEW)},
            comparisons={f"{OLD}..{NEW}": ["app/src/main/Foo.kt"]},
        )
        self.assertEqual("Código y arquitectura • 8", a1.detect(data)[0].target)

    def test_infrastructure_error_is_unavailable_not_revisar_salud_or_success(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "a1-alert.json"
            rc = a1.run_live({}, output_path=output)
            self.assertEqual(a1.EXIT_UNAVAILABLE, rc)
            self.assertNotEqual(a1.EXIT_OK, rc)
            self.assertFalse(output.exists())

    def test_no_signal_is_success_and_writes_no_artifact(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "a1-alert.json"
            with mock.patch.object(a1, "collect_snapshot", return_value=snapshot()):
                rc = a1.run_live({"ignored": "value"}, output_path=output)
            self.assertEqual(a1.EXIT_OK, rc)
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
