#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_scan="$script_dir/check_identity_leaks.sh"
apk_scan="$script_dir/inspect_apk.sh"
test_dir=$(mktemp -d)
scan_root="$test_dir/source"
payload_dir="$test_dir/payload"
fake_apk="$test_dir/test.apk"
mkdir -p "$scan_root" "$payload_dir" "$test_dir/bin"
trap 'rm -rf "$test_dir"' EXIT HUP INT TERM

expect_failure() {
    if "$@" >/dev/null 2>&1; then
        printf '%s\n' "Expected failure: $*" >&2
        exit 1
    fi
}

printf '%s\n' 'Android relay documentation' > "$scan_root/input.txt"
"$repo_scan" "$scan_root" >/dev/null
PATH=/usr/bin:/bin "$repo_scan" "$scan_root" >/dev/null

printf '%s\n' 'Contact 1390000''0000' > "$scan_root/input.txt"
expect_failure "$repo_scan" "$scan_root"

printf '%s\n' '/Users/'example'/relay' > "$scan_root/input.txt"
expect_failure "$repo_scan" "$scan_root"

printf '%s\n' 'origin=10.1.''2.3' > "$scan_root/input.txt"
expect_failure "$repo_scan" "$scan_root"

printf '%s\n' 'BEGIN PRI''VATE KEY' > "$scan_root/input.txt"
expect_failure "$repo_scan" "$scan_root"

printf '%s\n' 'legacy.example.test' > "$test_dir/private-strings"
chmod 600 "$test_dir/private-strings"
printf '%s\n' 'legacy.example.test' > "$scan_root/input.txt"
expect_failure env IDENTITY_STRINGS_FILE="$test_dir/private-strings" "$repo_scan" "$scan_root"

: > "$test_dir/empty-strings"
expect_failure env IDENTITY_STRINGS_FILE="$test_dir/empty-strings" "$repo_scan" "$scan_root"

printf '%s\n' '#!/bin/sh' 'exit 0' > "$test_dir/bin/apksigner"
printf '%s\n' '#!/bin/sh' 'case "$2" in' \
    'application-id) printf "%s\n" io.github.parryqiu.androidimessagerelay ;;' \
    'version-name) printf "%s\n" 0.1.0 ;;' \
    '*) exit 2 ;;' \
    'esac' > "$test_dir/bin/apkanalyzer"
chmod 700 "$test_dir/bin/apksigner" "$test_dir/bin/apkanalyzer"

printf '%s\n' 'Safe APK payload' > "$payload_dir/payload.txt"
(cd "$payload_dir" && zip -q "$fake_apk" payload.txt)
APKSIGNER_BIN="$test_dir/bin/apksigner" APKANALYZER_BIN="$test_dir/bin/apkanalyzer" \
    "$apk_scan" "$fake_apk" >/dev/null

rm -f "$fake_apk"
printf '%s\n' 'Contact 1390000''0000' > "$payload_dir/payload.txt"
(cd "$payload_dir" && zip -q "$fake_apk" payload.txt)
expect_failure env APKSIGNER_BIN="$test_dir/bin/apksigner" \
    APKANALYZER_BIN="$test_dir/bin/apkanalyzer" "$apk_scan" "$fake_apk"

printf '%s\n' 'Identity scanner tests passed.'
