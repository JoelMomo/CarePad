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

## Migration-only CI adjustment

The source `gradle-wrapper.jar` is a binary blob that could not be copied byte-identically through the connected GitHub migration path. Until it is restored, CI installs Gradle `9.5.0` explicitly through `gradle/actions/setup-gradle` and invokes `gradle` directly. `gradle-wrapper.properties`, `gradlew` and `gradlew.bat` remain preserved.

The legacy raster launcher icon fallbacks are also not included in this draft snapshot; the adaptive vector resources used on current Android versions are preserved. This must be resolved before the migration is considered complete if compatibility with pre-API-26 launcher resources is required.

No architectural refactor or SAF behavior change belongs in this migration PR.
