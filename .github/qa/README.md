# QA signing material

`l3-qa-debug.keystore.b64` is a **test-only, publicly reproducible Android debug keystore** used solely to produce installable QA pairs for physical migration/update gates.

It is intentionally not secret and must never be trusted as a release or production identity. It is not the future official CarePad signing key and must not be reused for distribution.

Current certificate SHA-256 fingerprint:

`85:13:B3:0C:18:D3:BB:75:74:83:0A:83:04:61:C8:62:FE:26:C1:24:DD:1B:1C:69:4F:7F:58:AF:27:16:62:E6`

Credentials are the standard Android debug credentials (`android` / `android`, alias `androiddebugkey`).
