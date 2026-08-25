#!/usr/bin/env python3

import sys
import tempfile
import unittest
from pathlib import Path

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
        "events": [],
    }
    item.update(overrides)
    return item


def snapshot(*, coordination=None, qa_rows=None, prs=None, runs=None, comparisons=None, specialists=None):
    return {
        "coordination": coordination or [],
        "qa": qa_rows or [],
        "prs": prs or {},
        "runs": runs or {},
        "comparisons": comparisons or {},
        "specialists": specialists if specialists is not None else [specialist()],
    }


class A1SignalTests(unittest.TestCase):
    def test_a1_01_positive_head_gate_invalidated_by_material_change(self):
        data = snapshot(
            coordination=[coord()],
            prs={"12": pr(head_sha=NEW)},
            comparisons={f"{OLD}..{NEW}": ["app/src/main/Foo.kt"]},
        )
        self.assertEqual(["A1-01"], [alert.signal for alert in a1.detect(data)])

    def test_a1_01_positive_claimed_success_ci_is_not_success(self):
        data = snapshot(
            coordination=[coord(github_ref=f"PR #12 · HEAD {OLD} · Android CI #88 SUCCESS")],
            prs={"12": pr()},
            runs={"88": {"status": "completed", "conclusion": "failure", "head_sha": OLD, "updated_at": "2026-08-25T00:01:00Z"}},
        )
        self.assertEqual(["A1-01"], [alert.signal for alert in a1.detect(data)])

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

    def test_a1_03_positive_qa_sha_differs_with_material_change(self):
        data = snapshot(
            qa_rows=[qa()],
            prs={"12": pr(head_sha=NEW)},
            comparisons={f"{OLD}..{NEW}": ["scripts/automation/a1.py"]},
        )
        self.assertEqual(["A1-03"], [alert.signal for alert in a1.detect(data)])

    def test_documentation_only_change_does_not_invalidate_qa(self):
        data = snapshot(
            qa_rows=[qa()],
            prs={"12": pr(head_sha=NEW)},
            comparisons={f"{OLD}..{NEW}": ["README.md", "docs/qa.md"]},
        )
        self.assertEqual([], a1.detect(data))

    def test_ambiguous_missing_compare_is_silent_for_qa(self):
        data = snapshot(qa_rows=[qa()], prs={"12": pr(head_sha=NEW)})
        self.assertEqual([], a1.detect(data))

    def test_a1_04_positive_closed_work_reopened_without_canonical_trigger(self):
        closed = coord(state="Resuelto", type="Seguimiento", github_ref="PR #12", next_step="", last_edited="2026-08-25T00:00:00Z")
        reopened = pr(state="open", events=[{"event": "reopened", "created_at": "2026-08-25T01:00:00Z"}])
        data = snapshot(coordination=[closed], prs={"12": reopened})
        self.assertEqual(["A1-04"], [alert.signal for alert in a1.detect(data)])

    def test_legitimate_reopen_with_regression_is_not_a1_04(self):
        closed = coord(state="Resuelto", type="Seguimiento", github_ref="PR #12", next_step="", last_edited="2026-08-25T00:00:00Z")
        reopened = pr(state="open", events=[{"event": "reopened", "created_at": "2026-08-25T01:00:00Z"}])
        regression = qa(
            validated_real=False,
            version="PR #12",
            regression="Sí",
            last_edited="2026-08-25T01:10:00Z",
        )
        data = snapshot(coordination=[closed], qa_rows=[regression], prs={"12": reopened})
        self.assertEqual([], a1.detect(data))

    def test_reopen_with_active_coordination_trigger_is_not_a1_04(self):
        closed = coord(state="Resuelto", type="Seguimiento", github_ref="PR #12", next_step="", last_edited="2026-08-25T00:00:00Z")
        active = coord(github_ref="PR #12", last_edited="2026-08-25T01:10:00Z")
        reopened = pr(state="open", events=[{"event": "reopened", "created_at": "2026-08-25T01:00:00Z"}])
        data = snapshot(coordination=[closed, active], prs={"12": reopened})
        self.assertEqual([], [a for a in a1.detect(data) if a.signal == "A1-04"])

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

    def test_api_or_credential_error_is_not_revisar_salud(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "a1-alert.json"
            rc = a1.run_live({}, output_path=output)
            self.assertEqual(0, rc)
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
