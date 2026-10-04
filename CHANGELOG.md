# Changelog

All notable changes are documented in this file. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and releases use Semantic Versioning.

## [Unreleased]

## [0.1.2] - 2026-10-04

### Changed

- Updated Android build dependencies, the Gradle wrapper, and CI actions.
- Updated the Cloudflare Terraform provider and dependency lockfile.
- Aligned Android and Mac package versions at 0.1.2.

## [0.1.1] - 2026-08-21

### Changed

- Added an English Android setup-screen screenshot to the README.
- Adapted the Android setup screen to edge-to-edge system bars on current Android releases.

## [0.1.0] - 2026-08-21

### Added

- Fail-closed Android SMS relay for Android 8.0 and newer.
- Local proof-of-possession pairing and Mac approval.
- End-to-end encrypted, signed, idempotent message delivery.
- Bounded Android and Mac persistence with expiring delivery leases and periodic cleanup.
- Keychain recipient storage and FIFO-based Messages delivery.
- Cloudflare Tunnel and Zero Trust Access Terraform template.
- Cross-language protocol tests, CI, CodeQL, dependency review, and secret scanning.

[Unreleased]: https://github.com/ParryQiu/android-imessage-relay/compare/v0.1.2...HEAD
[0.1.2]: https://github.com/ParryQiu/android-imessage-relay/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/ParryQiu/android-imessage-relay/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/ParryQiu/android-imessage-relay/releases/tag/v0.1.0
