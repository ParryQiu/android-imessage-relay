#!/bin/sh
set -eu

apk=${1:?Usage: inspect_apk.sh PATH_TO_APK}
apksigner_bin=${APKSIGNER_BIN:-apksigner}
apkanalyzer_bin=${APKANALYZER_BIN:-apkanalyzer}
extra_strings=${IDENTITY_STRINGS_FILE:-}
patterns='(^|[^0-9])(\+?86[- ]?)?1[3-9][0-9]{9}([^0-9]|$)|/(Users|home)/[A-Za-z0-9._-]+/|192\.168\.[0-9]{1,3}\.[0-9]{1,3}|10\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}|172\.(1[6-9]|2[0-9]|3[01])\.[0-9]{1,3}\.[0-9]{1,3}|BEGIN (RSA |EC |OPENSSH )?(PUBLIC|PRIVATE) KEY'

run_search() {
    set +e
    "$@"
    status=$?
    set -e
    if [ "$status" -gt 1 ]; then
        exit "$status"
    fi
    return "$status"
}

scan_default_patterns() {
    if command -v rg >/dev/null 2>&1; then
        run_search rg -n "$patterns" "$strings_file"
    else
        run_search grep -n -E "$patterns" "$strings_file"
    fi
}

scan_operator_strings() {
    if command -v rg >/dev/null 2>&1; then
        run_search rg -n -F -f "$extra_strings" "$strings_file"
    else
        run_search grep -n -F -f "$extra_strings" "$strings_file"
    fi
}

if [ -n "$extra_strings" ]; then
    test -f "$extra_strings"
    if [ ! -s "$extra_strings" ] || grep -Eq '^[[:space:]]*(#|$)' "$extra_strings"; then
        printf '%s\n' 'Identity string files must contain one non-empty literal value per line.' >&2
        exit 2
    fi
fi

test -f "$apk"
"$apksigner_bin" verify --verbose --print-certs "$apk"
package_name=$("$apkanalyzer_bin" manifest application-id "$apk")
version_name=$("$apkanalyzer_bin" manifest version-name "$apk")
test "$package_name" = 'io.github.parryqiu.androidimessagerelay'
test "$version_name" = '0.1.0'

scan_dir=$(mktemp -d)
strings_file=$(mktemp)
trap 'rm -rf "$scan_dir"; rm -f "$strings_file"' EXIT HUP INT TERM
unzip -qq "$apk" -d "$scan_dir"
find "$scan_dir" -type f -exec strings {} + > "$strings_file"
if scan_default_patterns; then
    printf '%s\n' 'Prohibited value detected in APK.' >&2
    exit 1
fi

if [ -n "$extra_strings" ] && scan_operator_strings; then
    printf '%s\n' 'Operator-specific identity value detected in APK.' >&2
    exit 1
fi

printf '%s\n' "APK inspection passed for $package_name $version_name."
