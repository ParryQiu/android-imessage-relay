# Initial security audit

The pre-publication review identified one high-risk, four medium-risk, and one low-risk finding. All six were treated as release blockers.

| Finding | Initial severity | Remediation |
| --- | --- | --- |
| Deployment endpoint and Mac public key embedded in the APK | High | Removed all deployment defaults; the generic APK is unconfigured and fail-closed |
| Pairing authorization depended on proxy source IP | Medium | Replaced with P-256 proof of possession, matching verification codes, expiring resources, and local Mac approval |
| Message IDs were deterministic hashes of SMS data | Medium | Replaced with persisted, independent 256-bit random identifiers |
| HTTP threads and delivery subprocesses were unbounded | Medium | Added fixed workers, request/header/time limits, backlog limits, and overload handling |
| Android queue and Mac delivery records were unbounded | Medium | Added explicit record, byte, age, attempt, and retention limits plus expiring leases |
| Plaintext could remain in regular temporary files after a crash | Low | Replaced regular files with a mode `0600` short-lived FIFO |

Additional release controls remove ADB credential provisioning, encrypt Android configuration and queue data with Keystore keys, store the recipient in macOS Keychain, prohibit sensitive error responses, and scan source, Git history, and APK contents before publication.

The originating scan recorded total usage of 4,940,295 tokens. This number describes the audit run only and has no runtime meaning.

This summary does not claim that the software is free of vulnerabilities. Report suspected vulnerabilities through GitHub private vulnerability reporting.
