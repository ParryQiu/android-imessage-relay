# Architecture

Android iMessage Relay provides one narrowly scoped path: an Android device receives an SMS, encrypts and signs a delivery request, and sends it through Cloudflare Access and an outbound Cloudflare Tunnel to a trusted Mac. The Mac verifies and decrypts the request locally, then asks Apple Messages to deliver the text to the configured Apple recipient.

![Android iMessage Relay architecture](assets/architecture.svg)

The [standalone HTML diagram](assets/architecture.html) is the editable source of truth for the exported SVG.

## System boundaries

| Boundary | Trusted material | Enforcement |
| --- | --- | --- |
| Android device | Cloudflare service token, paired Mac RSA public key, non-exportable P-256 signing key, encrypted queue | Android Keystore, fail-closed configuration, bounded retry policy |
| Cloudflare edge | Access application, Service Auth policy, Tunnel routing metadata | Dedicated service token and Access policy |
| Trusted Mac user | Mac RSA private key, approved Android public key, Apple recipient, delivery metadata | Local pairing approval, filesystem permissions, macOS Keychain, Unix socket |
| Apple services | Final plaintext recipient and message | Apple Messages, iMessage, and APNs behavior outside this project's control |

The Android-to-Mac SMS envelope is encrypted end to end. Cloudflare authenticates and transports the request but cannot decrypt the SMS body. Apple Messages necessarily receives the final plaintext because it performs the delivery.

## Components

### Android relay

The Android client is disabled until its endpoint is configured and a pairing request has been approved locally on the Mac. It generates a non-exportable P-256 signing key in Android Keystore, signs every API request, creates an independent 256-bit random message ID, encrypts each SMS with a fresh AES-256-GCM key, and wraps that key with the paired Mac RSA-3072 public key.

The encrypted retry queue is capped at 1,000 records, 16 MiB, and seven days. Records that exceed an age or capacity limit are discarded oldest-first, and the app exposes only a body-free discard count.

### Cloudflare Access

Cloudflare Access is the Internet-facing authentication gate. The deployment template creates a dedicated service token and a Service Auth policy for the relay hostname. The token is independent of the Tunnel credential and should not be shared with unrelated services.

An Access decision does not replace the application signature check. Both controls must pass: Cloudflare validates the service identity, while the Mac validates the paired Android device identity and the signed request.

### Cloudflare Tunnel

`cloudflared` establishes an outbound connection from the Mac network. The default ingress rule forwards accepted requests to a mode `0600` Unix socket, so the relay does not need a public IP address, router port forwarding, or a public TCP listener.

The Terraform template references an existing Tunnel. It does not create, read, output, import, or rotate the Tunnel token. Tunnel credential rotation must be handled separately after its impact on other ingress rules is understood.

### Mac relay

The Mac service accepts only bounded HTTP requests, checks the Access assertion, verifies the P-256 request signature, decrypts the RSA/AES envelope in process, and uses the random message ID as the idempotency key. A fixed worker pool, request limits, timeouts, and overload responses constrain resource usage.

The default listener is a Unix socket. TCP migration mode is optional, accepts only loopback IP addresses, and requires TLS certificates; it should be removed after migration unless the operator has a documented reason to retain it.

### Delivery metadata

SQLite stores pairing state and message delivery metadata, not SMS plaintext. Delivery rows have expiring leases so interrupted work can be recovered. A periodic maintenance loop enforces the 30-day delivery-retention window. Delivery and pairing tables have independent hard capacity limits.

### Messages bridge

The destination address is stored in macOS Keychain. After verification and decryption, the relay writes the message to a mode `0600`, short-lived FIFO consumed by AppleScript. The body is not written to a regular temporary file.

The Mac must remain signed in, awake, online, authorized to automate Messages, and able to send to the chosen Apple recipient.

## Message delivery flow

1. Android receives an SMS after pairing and SMS permission are complete.
2. Android persists a randomly identified encrypted queue record before attempting delivery.
3. Android creates a fresh AES-256-GCM envelope, wraps its key for the paired Mac, and signs the canonical request.
4. Cloudflare Access validates the dedicated service token.
5. Cloudflare Tunnel forwards the allowed request through its outbound connector to the local Unix socket.
6. The Mac validates request limits, Access context, device binding, signature, timestamp, and encrypted envelope.
7. The relay claims an expiring delivery lease and returns the stored result for duplicate message IDs.
8. The Messages bridge reads the recipient from Keychain and transfers the plaintext through the FIFO to AppleScript.
9. Messages delivers the text through Apple's services, and the relay persists only the final metadata state.

## Pairing and recovery flow

Pairing is deliberately different from normal message delivery because it creates a device trust binding.

1. Android creates a short-lived `PairingRequest` containing its device public key, nonce, and proof-of-possession signature.
2. Android and the Mac CLI derive and display the same short verification code.
3. A local Mac operator compares the codes and approves or rejects the request.
4. Approval atomically binds the Android key and returns the Mac encryption public key.
5. Approved, rejected, expired, or reused requests cannot be replayed.
6. Replacing the bound Android device requires the Mac CLI's explicit local recovery option.

Cloudflare connectivity can carry a pairing request, but it cannot approve one or replace the bound device remotely.

## Permitted and blocked paths

| Path | Result | Reason |
| --- | --- | --- |
| Paired Android, valid service token, valid signature | Allowed | Both Cloudflare identity and device identity are verified |
| Internet request without an accepted Access identity | Blocked | Service Auth policy denies the request at the edge |
| Request with Access but no valid device signature | Blocked | Mac application verification fails |
| Direct public connection to the Mac relay | Not provided | Default deployment exposes only a local Unix socket |
| Remote paired-device replacement | Blocked | Replacement requires local Mac operator approval |
| Duplicate encrypted message ID | Idempotent response | Existing delivery state is returned without a second send |

## Public API

The API follows resource-oriented naming and camelCase JSON.

| Method | Resource | Authentication | Behavior |
| --- | --- | --- | --- |
| `POST` | `/v1/pairingRequests` | Cloudflare Access plus proof of device-key possession | Creates a five-minute pairing resource |
| `GET` | `/v1/pairingRequests/{id}` | Cloudflare Access plus request ownership | Returns the stable pairing resource state |
| `POST` | `/v1/messages` | Cloudflare Access plus paired-device signature | Creates one idempotent encrypted delivery |
| `GET` | `/v1/health` | Deployment policy | Returns only a generic serving state |

Public failures use a `google.rpc.Status`-style error object and do not expose exception stacks, local paths, keys, credentials, or SMS content.

## Metadata visibility

Cloudflare can observe the hostname, request timing, source network address, approximate encrypted payload size, Access identity metadata, HTTP method and path, and transport status. It cannot decrypt the SMS envelope.

The Mac can access plaintext only after successful authentication, signature verification, and decryption. Apple Messages receives the plaintext and destination address as required for delivery. GitHub hosts source, CI, and release artifacts only; it is not in the runtime message path.

## Availability and failure behavior

- Android retries bounded queue records after transient network errors and uses stable message IDs for idempotency.
- Expired Android queue records and old Mac metadata are removed automatically.
- A crashed Mac worker releases work through an expiring lease rather than leaving a delivery permanently `in_progress`.
- Slow or excessive requests are constrained by body, header, worker, backlog, and timeout limits.
- If the Tunnel, Mac, Messages, Apple account, or APNs is unavailable, delivery is delayed or fails; the system does not claim exactly-once delivery across Apple services.
- A successful build or synthetic API call is not end-to-end acceptance. A real SMS and a 24-hour observation window remain release requirements.

## Infrastructure scope

The only operator-managed cloud infrastructure is Cloudflare Tunnel and Cloudflare Zero Trust Access. The project does not require Cloudflare Workers, D1, KV, R2, Bark, IFTTT, or another project-operated cloud service.

A continuously available Mac, Apple Messages, iMessage, and APNs are runtime dependencies, not operator-managed cloud infrastructure. GitHub is used for source, CI, security analysis, and release distribution.

## Non-goals

- MMS images or attachments
- Notification-listener forwarding
- WeChat or other application notifications
- Root access or Android system modification
- A hosted relay service operated by this project
- Circumventing Apple Messages, Cloudflare Access, or local pairing controls

## Related documentation

- [Cloudflare deployment](../infra/cloudflare/README.md)
- [Migration and rollback](migration.md)
- [Security audit](security-audit.md)
- [Security policy](../SECURITY.md)
- [Release process](../RELEASING.md)
