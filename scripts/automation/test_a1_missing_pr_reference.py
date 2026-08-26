#!/usr/bin/env python3

import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

import a1_operational_risk as a1
import a1_operational_risk_live as live


class A1MissingPrReferenceTests(unittest.TestCase):
    def setUp(self):
        self.client = object.__new__(a1.GitHubClient)
        self.client.repository = "JoelMomo/CarePad"
        live._PR_REFERENCE_PROVENANCE.clear()

    def missing_pr_error(self, number: int = 22):
        return a1.OperationalDataUnavailable(
            f"API request failed for /repos/JoelMomo/CarePad/pulls/{number}: "
            "HTTP Error 404: Not Found"
        )

    def test_historical_missing_pr_reference_is_treated_as_absent_evidence(self):
        live.register_reference_provenance(
            coordination=[
                {
                    "state": "Resuelto",
                    "github_ref": "PR #22",
                    "summary": "",
                    "next_step": "",
                    "subject": "Referencia cerrada",
                }
            ]
        )
        with mock.patch.object(
            live,
            "_ORIGINAL_GET_PR",
            side_effect=self.missing_pr_error(),
        ):
            self.assertEqual(
                {},
                live.get_pr_tolerating_historical_not_found(self.client, 22),
            )

    def test_active_missing_pr_reference_remains_unavailable(self):
        live.register_reference_provenance(
            coordination=[
                {
                    "state": "En curso",
                    "github_ref": "PR #22",
                    "summary": "",
                    "next_step": "",
                    "subject": "Trabajo activo",
                }
            ]
        )
        with mock.patch.object(
            live,
            "_ORIGINAL_GET_PR",
            side_effect=self.missing_pr_error(),
        ):
            with self.assertRaises(a1.OperationalDataUnavailable):
                live.get_pr_tolerating_historical_not_found(self.client, 22)

    def test_mixed_historical_and_active_reference_remains_unavailable(self):
        live.register_reference_provenance(
            coordination=[
                {
                    "state": "Resuelto",
                    "github_ref": "PR #22",
                    "summary": "",
                    "next_step": "",
                    "subject": "Referencia cerrada",
                },
                {
                    "state": "En curso",
                    "github_ref": "PR #22",
                    "summary": "",
                    "next_step": "",
                    "subject": "Trabajo activo",
                },
            ]
        )
        with mock.patch.object(
            live,
            "_ORIGINAL_GET_PR",
            side_effect=self.missing_pr_error(),
        ):
            with self.assertRaises(a1.OperationalDataUnavailable):
                live.get_pr_tolerating_historical_not_found(self.client, 22)

    def test_other_github_failures_remain_unavailable(self):
        live.register_reference_provenance(
            coordination=[
                {
                    "state": "Resuelto",
                    "github_ref": "PR #22",
                    "summary": "",
                    "next_step": "",
                    "subject": "Referencia cerrada",
                }
            ]
        )
        error = a1.OperationalDataUnavailable(
            "API request failed for /repos/JoelMomo/CarePad/pulls/22: "
            "HTTP Error 403: Forbidden"
        )
        with mock.patch.object(live, "_ORIGINAL_GET_PR", side_effect=error):
            with self.assertRaises(a1.OperationalDataUnavailable):
                live.get_pr_tolerating_historical_not_found(self.client, 22)

    def test_404_for_a_different_resource_is_not_silenced(self):
        live.register_reference_provenance(
            coordination=[
                {
                    "state": "Resuelto",
                    "github_ref": "PR #22",
                    "summary": "",
                    "next_step": "",
                    "subject": "Referencia cerrada",
                }
            ]
        )
        error = a1.OperationalDataUnavailable(
            "API request failed for /repos/JoelMomo/CarePad/actions/runs/22: "
            "HTTP Error 404: Not Found"
        )
        with mock.patch.object(live, "_ORIGINAL_GET_PR", side_effect=error):
            with self.assertRaises(a1.OperationalDataUnavailable):
                live.get_pr_tolerating_historical_not_found(self.client, 22)


class A1LiveA102ContextTests(unittest.TestCase):
    def snapshot(self, number, record, *, merged_at="2026-08-24T00:00:00Z"):
        item = {
            "subject": "Seguimiento",
            "type": "Seguimiento",
            "state": "Resuelto",
            "owner": "Código y arquitectura",
            "github_ref": f"PR #{number}",
            "summary": "",
            "next_step": "",
            "last_edited": "2026-08-24T01:00:00Z",
        }
        item.update(record)
        return {
            "repository": "JoelMomo/CarePad",
            "coordination": [item],
            "qa": [],
            "specialists": [],
            "prs": {
                str(number): {
                    "number": number,
                    "state": "closed",
                    "merged": True,
                    "head_sha": "1" * 40,
                    "merged_at": merged_at,
                    "closed_at": merged_at,
                    "updated_at": merged_at,
                    "events": [],
                }
            },
            "runs": {},
            "head_runs": {},
            "comparisons": {},
        }

    def signals(self, data):
        return [alert.signal for alert in live.detect_a1_02_contextual(data)]

    def test_pr7_closed_handoff_word_open_is_not_a_pr_state_claim(self):
        data = self.snapshot(
            7,
            {
                "github_ref": (
                    "CarePad PR #7 MERGED · source HEAD " + "0" * 40 +
                    " · merge commit " + "2" * 40 + " · CI #82 SUCCESS · QA PASS 16/16"
                ),
                "summary": (
                    "PR #7 está fusionado en main tras CI pre-merge completa y QA PASS 16/16. "
                    "No queda bloqueo ni handoff transversal abierto por recuperación de módulos."
                ),
                "next_step": "Ninguno en Coordinación. Reabrir solo si aparece una regresión real.",
                "last_edited": "2026-08-24T03:26:00Z",
            },
            merged_at="2026-08-24T02:45:04Z",
        )
        self.assertEqual([], self.signals(data))

    def test_pr5_closed_handoff_word_open_is_not_a_pr_state_claim(self):
        data = self.snapshot(
            5,
            {
                "github_ref": (
                    "CarePad PR #5 · head " + "3" * 40 +
                    " · merge " + "4" * 40 + " · QA físico BUG-2 PASS 3/3"
                ),
                "summary": (
                    "BUG-2 quedó validado físicamente en Pixel 6 con PASS en los tres casos SAF "
                    "y PR #5 fue fusionado en main. No queda bloqueo ni handoff transversal abierto por este defecto."
                ),
                "next_step": "Ninguno en Coordinación. Reabrir solo si aparece una regresión real.",
                "last_edited": "2026-08-25T01:23:00Z",
            },
            merged_at="2026-08-24T23:35:27Z",
        )
        self.assertEqual([], self.signals(data))

    def test_pr17_pre_merge_narrative_is_not_a_current_pr_state_claim(self):
        data = self.snapshot(
            17,
            {
                "state": "En curso",
                "github_ref": (
                    "CarePad PR #17 MERGED · merge commit main@" + "5" * 40 +
                    " · HEAD fusionado " + "6" * 40 +
                    " · A1 Operational Risk #27 SUCCESS · Android CI #125 SUCCESS"
                ),
                "summary": (
                    "Joël autorizó explícitamente el merge de PR #17 y Coordinación revalidó inmediatamente "
                    "que el PR seguía OPEN/no fusionado, mergeable, antes de ejecutar el merge autorizado."
                ),
                "last_edited": "2026-08-26T14:28:00Z",
            },
            merged_at="2026-08-26T14:26:25Z",
        )
        self.assertEqual([], self.signals(data))

    def test_current_summary_claim_that_pr_still_open_remains_a1_02(self):
        data = self.snapshot(
            12,
            {
                "github_ref": "PR #12 · HEAD " + "7" * 40,
                "summary": "PR #12 sigue OPEN/no fusionado y pendiente del gate final.",
            },
        )
        self.assertEqual(["A1-02"], self.signals(data))

    def test_unrelated_negation_does_not_hide_current_pr_state_claim(self):
        data = self.snapshot(
            12,
            {
                "github_ref": "PR #12 · HEAD " + "8" * 40,
                "summary": "PR #12 sigue abierto. El handoff ya no está abierto.",
            },
        )
        self.assertEqual(["A1-02"], self.signals(data))

    def test_current_github_reference_that_pr_is_open_remains_a1_02(self):
        data = self.snapshot(
            12,
            {
                "github_ref": "PR #12 sigue abierto/draft",
                "summary": "",
            },
        )
        self.assertEqual(["A1-02"], self.signals(data))

    def test_post_merge_next_step_to_merge_remains_a1_02(self):
        data = self.snapshot(
            12,
            {
                "github_ref": "PR #12 MERGED",
                "summary": "PR #12 quedó fusionado.",
                "next_step": "Fusionar PR #12.",
            },
        )
        self.assertEqual(["A1-02"], self.signals(data))

    def test_current_next_step_claim_that_pr_still_open_remains_a1_02(self):
        data = self.snapshot(
            12,
            {
                "github_ref": "PR #12 MERGED",
                "summary": "PR #12 quedó fusionado.",
                "next_step": "PR #12 sigue OPEN/no fusionado; esperar gate.",
            },
        )
        self.assertEqual(["A1-02"], self.signals(data))

    def test_current_claim_pr_is_still_open_with_adverb_remains_a1_02(self):
        data = self.snapshot(
            12,
            {
                "github_ref": "PR #12 MERGED",
                "summary": "PR #12 está todavía abierto.",
            },
        )
        self.assertEqual(["A1-02"], self.signals(data))

    def test_explicit_current_state_claim_remains_a1_02(self):
        data = self.snapshot(
            12,
            {
                "github_ref": "PR #12 MERGED",
                "summary": "El estado actual del PR #12 es OPEN.",
            },
        )
        self.assertEqual(["A1-02"], self.signals(data))

    def test_completed_ci_still_claimed_pending_remains_a1_02(self):
        data = self.snapshot(
            12,
            {
                "github_ref": "PR #12 MERGED · CI #129 PENDING",
                "summary": "PR #12 quedó fusionado; CI #129 sigue pendiente.",
            },
        )
        data["runs"]["129"] = {
            "run_number": 129,
            "status": "completed",
            "conclusion": "success",
            "head_sha": "1" * 40,
            "created_at": "2026-08-24T00:10:00Z",
            "updated_at": "2026-08-24T00:30:00Z",
        }
        self.assertEqual(["A1-02"], self.signals(data))


if __name__ == "__main__":
    unittest.main()
