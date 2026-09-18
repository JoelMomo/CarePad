<div align="center">

# CarePad

Controller-friendly companion app for **AYN Thor** diagnostics, game and BIOS checks, controls, and device tools.

[**Apps & tools**](https://joelmomo.github.io/) · [**Support development**](https://joelmomo.github.io/#support)

</div>

[![Android CI](https://github.com/JoelMomo/CarePad/actions/workflows/android-ci.yml/badge.svg)](https://github.com/JoelMomo/CarePad/actions/workflows/android-ci.yml)

> [!NOTE]
> CarePad is in active development. This repository is the public source snapshot of the current app and module architecture.

## What CarePad is

CarePad is designed around handheld use: touch remains available, but the main shell and supported modules are built to work comfortably with physical controls and D-pad navigation.

The current public snapshot includes:

- **Performance** — session-oriented performance monitoring for emulators.
- **Games & BIOS** — game-library inspection and checks for known file problems.
- **Controls** — controller and input testing, including guided checks.
- **Module system** — a stable host/module contract with discovery, settings and recovery paths.
- **Responsive navigation** — controller-aware rail/bottom navigation depending on available space.
- **Light, dark and system themes**.

## Current status

CarePad is not presented as a finished consumer release yet. The public repository is being used to harden the architecture, controller navigation and module boundaries before wider distribution.

The app targets **Android 7.0+ (API 24)** and currently builds against Android 16 / API 36.

## Controller-first navigation

CarePad tracks touch and controller input separately so switching between them does not leave the interface in an unusable focus state.

The automated test suite covers:

- D-pad navigation across the shell;
- focus transitions between navigation and content;
- touch → controller recovery;
- module actions and settings;
- real controller-dispatch behavior;
- internal controls-module flows.

## Development

The project is split into a host app plus reusable contracts, core Android utilities, runtime modules and test fixtures.

Main areas:

```text
app/                 CarePad host UI and navigation
carepad-contracts/   stable module contracts
core/                shared Android infrastructure
modules/             performance, games/BIOS and controls modules
module-lab/          module integration test surface
test-fixtures/       emulator and controller fixtures
scripts/ci/          CI and emulator smoke tests
```

Build locally with:

```bash
./gradlew assembleDebug
```

Unit tests:

```bash
./gradlew test
```

GitHub Actions also runs build, unit and emulator-level validation.

## Migration note

CarePad was published as a clean public snapshot rather than by exposing the private development history. See [MIGRATION_NOTES.md](MIGRATION_NOTES.md) for what was preserved and intentionally excluded.

Some internal package names and compatibility identifiers still use the earlier `DocThor` / `thordoctor` naming intentionally, so existing app data and module contracts are not broken.

A project license has **not yet been selected**; that remains an explicit product/legal decision.

## Contributing

Bug reports, feature ideas and focused pull requests are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) before opening one.

For security or privacy-sensitive reports, follow [SECURITY.md](SECURITY.md) instead of posting details publicly.

<div align="center">

## Support development

CarePad and my other public tools are free to use. If they have been useful to you, you can support future development.

<p>
  <a href="https://github.com/sponsors/JoelMomo">
    <img src="https://img.shields.io/badge/GitHub%20Sponsors-Sponsor-EA4AAA?style=for-the-badge&logo=githubsponsors&logoColor=white" alt="Sponsor on GitHub">
  </a>
  <a href="https://ko-fi.com/joelmomodev">
    <img src="https://img.shields.io/badge/Ko--fi-One--time%20tip-FF5E5B?style=for-the-badge&logo=kofi&logoColor=white" alt="Leave a tip on Ko-fi">
  </a>
</p>

<sub>Public projects remain free regardless of support.</sub>

</div>
