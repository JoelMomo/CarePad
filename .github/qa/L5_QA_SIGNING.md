# L5 QA same-signer pair

Temporary QA-only signing material and workflow for the L5 onboarding update gate.

- The keystore is public test material, not a secret.
- Alias: `androiddebugkey`; store/key password: `android`.
- Certificate SHA-256: `8513b30c18d3bb7574830a830461c862fe26c124dd1b1c694f7f58af271662e6`.
- It is not a release key and must never be trusted for production or future official CarePad signing.
- The workflow builds the exact L5 baseline and candidate source SHAs, then explicitly re-signs both APKs with this QA identity so `adb install -r` can exercise preservation across a real in-place update.
- This infrastructure is temporary and must not be merged into `main` by inertia.
