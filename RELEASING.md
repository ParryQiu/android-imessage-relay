# Releasing

Releases are built and signed locally. The Android signing private key must never be copied to this repository, GitHub Actions, GitHub Secrets, shared storage, shell history, or issue content.

## Prerequisites

- A clean `main` checkout at the intended release commit
- JDK 17 and Android SDK build tools
- Python 3.12
- Terraform 1.10 or newer
- `gitleaks`, `apksigner`, `syft`, and GitHub CLI
- A locally protected Android keystore and passwords supplied without command-line arguments

## Preflight

1. Run all CI-equivalent checks.
2. Run gitleaks against the working tree and the complete Git history.
3. Scan for phone numbers, live hostnames, private network addresses, usernames, Tunnel tokens, Access credentials, private keys, certificates, keystores, state, plans, and deployment public keys.
4. Confirm the generic APK makes no network request and does not register active forwarding before configuration and approved pairing.
5. Confirm the migration backup and rollback path on the target Mac and Android device.
6. Complete the real-SMS and 24-hour acceptance test in README.

The built-in identity scan detects generic phone-number, user-path, private-network, and key patterns. Put operator-specific hostnames, domains, legacy package names, and other private identifiers in a `0600` file outside the repository, with one non-empty literal value per line. Use the same file for source and APK inspection without printing its contents:

```shell
IDENTITY_STRINGS_FILE=/secure/path/release-identity-strings \
  ./scripts/check_identity_leaks.sh .
IDENTITY_STRINGS_FILE=/secure/path/release-identity-strings \
  ./scripts/inspect_apk.sh /secure/path/android-imessage-relay-v0.1.1.apk
```

## Local signing

Create the signing key once and store it in a protected local location outside the repository:

```shell
keytool -genkeypair \
  -keystore /secure/path/android-imessage-relay-release.jks \
  -alias android-imessage-relay \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000
```

Build an unsigned release APK:

```shell
cd android
./gradlew clean assembleRelease
```

Use `zipalign` and `apksigner`. Supply passwords through protected files, an interactive prompt, or another local secret mechanism supported by the tool. Do not pass passwords directly in the process arguments.

```shell
zipalign -f -p 4 app/build/outputs/apk/release/app-release-unsigned.apk \
  android-imessage-relay-v0.1.1.apk
apksigner sign \
  --ks /secure/path/android-imessage-relay-release.jks \
  --ks-key-alias android-imessage-relay \
  android-imessage-relay-v0.1.1.apk
apksigner verify --verbose --print-certs android-imessage-relay-v0.1.1.apk
```

## Artifact verification

```shell
shasum -a 256 android-imessage-relay-v0.1.1.apk \
  > android-imessage-relay-v0.1.1.apk.sha256
apksigner verify --print-certs android-imessage-relay-v0.1.1.apk \
  > android-imessage-relay-v0.1.1-certificate.txt
syft dir:. -o spdx-json=android-imessage-relay-v0.1.1.spdx.json
```

Inspect the APK with `apkanalyzer`, `aapt2`, `jadx`, string scans, and `zipinfo`. The package must be `io.github.parryqiu.androidimessagerelay`, the version must match the tag, and no prohibited value may be present.

## Publish

1. Confirm required checks are green on the exact commit.
2. Create the signed tag `v0.1.1` locally and push it.
3. Create a GitHub Release with installation, upgrade, rollback, and known-limitations notes.
4. Upload only the signed APK, SHA-256 file, SPDX JSON SBOM, and certificate fingerprint file.
5. Download every artifact from the Release and re-verify its checksum, APK signature, package, version, and certificate fingerprint.

Never publish a release before real external SMS delivery and the 24-hour stability window are complete.
