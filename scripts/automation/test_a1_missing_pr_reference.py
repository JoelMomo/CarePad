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


if __name__ == "__main__":
    unittest.main()
