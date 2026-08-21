# CarePad clean snapshot migration

Source snapshot: `JoelMomo/ThorDoctor` `main` at `5fa9073e6d024aeeb96b76575d967cf2899b3dbb`.

This migration intentionally starts a new public repository without ThorDoctor Git history.

## Preserved during migration

- `applicationId` and Android package names
- persisted preference names and keys
- diagnostic/session schemas and filenames
- Android actions, manifest contracts, stable module IDs and routes
- current application behavior, including legacy compatibility facades

## Intentionally excluded

- `.idea/` IDE state
- legacy `README.md` and `ROADMAP.md`
- redundant `build-apk.yml`
- local/private artifacts covered by the hardened `.gitignore`
- a project license, pending an explicit product/legal decision

## Reproducible migration adaptations

The private source `gradle-wrapper.jar` could not be copied byte-identically through the connected migration path. The wrapper was regenerated in GitHub Actions using official Gradle `9.5.0`, while the existing `gradle-wrapper.properties` was kept unchanged. CI uses the regenerated `./gradlew` wrapper and must pass before this snapshot can be integrated.

The private legacy raster launcher fallbacks were not copied. Because the app still supports API 24+, the public snapshot provides unqualified XML launcher fallbacks composed from the same existing launcher vector resources; API 26+ continues to use the preserved adaptive icons.

Three large source files had to be reconstructed through chunked connector reads and therefore are not asserted byte-identical to the private Git blobs. Their buildability and the existing module behavior are validated by public CI; this is not treated as evidence for OEM-specific behavior.

No architectural refactor or SAF behavior change belongs in this migration PR.
