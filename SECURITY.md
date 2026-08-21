# Security Policy

## Supported versions

Only the latest published release receives security fixes.

## Reporting a vulnerability

Use GitHub private vulnerability reporting for this repository. Do not open a public issue and do not include live credentials, SMS content, phone numbers, private keys, Terraform state, pairing databases, or Tunnel tokens in a report.

Include the affected version, component, reproduction steps using synthetic data, impact, and any suggested mitigation. You should receive an initial response within seven days.

## Deployment responsibilities

- Use a dedicated Cloudflare Access service token and a Service Auth policy.
- Protect Terraform state as a secret.
- Keep the default Unix socket origin where possible.
- Keep the Mac patched, signed in, awake, and physically protected.
- Verify APK checksum and signing certificate fingerprint from the same release.
- Never install a generic APK that contains a third party's endpoint or public key.
- Do not share logs that include SMS content or authentication headers.

The project encrypts message content between Android and Mac, but endpoints and platform operators still process necessary metadata. Apple Messages receives plaintext by design.
