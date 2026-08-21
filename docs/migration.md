# Reversible migration

Use this sequence when replacing an existing Android-to-Mac relay. Do not rotate a shared Cloudflare Tunnel token as part of this migration.

## Prepare

1. Record the currently installed Android package and granted SMS permissions.
2. Back up the existing Mac configuration, RSA private key, Android public key, delivery database, LaunchAgent, and executable files to a protected local location.
3. Verify the backup can be read and identify the exact rollback commands.
4. Create a new dedicated Cloudflare Access service token. Do not reuse the older app's token.
5. Install the new Mac relay in dry-run mode and configure the existing Tunnel with a separate hostname or reversible ingress rule.

## Side-by-side validation

1. Install the new APK. Its package ID allows it to coexist with an older relay.
2. Configure the new endpoint and dedicated Access token.
3. Create a pairing request, compare the code on both devices, and approve locally on the Mac.
4. Test the encrypted channel in dry-run mode.
5. Immediately before the first real SMS test, revoke SMS permission from the older Android app. Never leave two forwarders active.
6. Switch the Mac relay to live mode and send a real ordinary SMS followed by a real six-digit verification-code SMS.

## Acceptance

Verify complete content, delivery within 30 seconds, no duplicate, outage recovery, Android screen-off behavior, Android reboot, Mac reboot, and stable operation for 24 hours. Inspect only body-free queue counters, delivery metadata, process state, and sanitized logs.

## Complete

After 24 hours of successful operation, uninstall the older Android app, disable its LaunchAgent and legacy endpoint, and revoke its Access service token. Retain the rollback backup according to your local retention policy.

## Roll back

If any required check fails, revoke SMS permission from the new app, stop its LaunchAgent, restore the previous Mac files and LaunchAgent, restore SMS permission to the previous Android app, and verify one real SMS. Keep the new dedicated Access token disabled or deleted while investigating.

Tunnel-token rotation is a separate operation. Determine every service that shares the Tunnel and obtain explicit approval before rotating it.
