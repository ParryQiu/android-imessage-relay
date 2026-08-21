#!/bin/sh
set -eu

scan_root=${1:-.}
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
        run_search rg -n --hidden \
            --glob '!.git/**' \
            --glob '!**/.gradle/**' \
            --glob '!**/build/**' \
            --glob '!**/.venv/**' \
            --glob '!**/.terraform/**' \
            --glob '!**/__pycache__/**' \
            --glob '!**/*.egg-info/**' \
            --glob '!scripts/check_identity_leaks.sh' \
            "$patterns" "$scan_root"
        return
    fi

    run_search grep -R -n -E \
        --exclude-dir=.git \
        --exclude-dir=.gradle \
        --exclude-dir=build \
        --exclude-dir=.venv \
        --exclude-dir=.terraform \
        --exclude-dir=__pycache__ \
        --exclude='*.egg-info' \
        --exclude=check_identity_leaks.sh \
        "$patterns" "$scan_root"
}

scan_operator_strings() {
    if command -v rg >/dev/null 2>&1; then
        run_search rg -n --hidden \
            --glob '!.git/**' \
            --glob '!**/.gradle/**' \
            --glob '!**/build/**' \
            --glob '!**/.venv/**' \
            --glob '!**/.terraform/**' \
            --glob '!**/__pycache__/**' \
            --glob '!**/*.egg-info/**' \
            --glob '!scripts/check_identity_leaks.sh' \
            -F -f "$extra_strings" "$scan_root"
        return
    fi

    run_search grep -R -n -F \
        --exclude-dir=.git \
        --exclude-dir=.gradle \
        --exclude-dir=build \
        --exclude-dir=.venv \
        --exclude-dir=.terraform \
        --exclude-dir=__pycache__ \
        --exclude='*.egg-info' \
        --exclude=check_identity_leaks.sh \
        -f "$extra_strings" "$scan_root"
}

if [ -n "$extra_strings" ]; then
    test -f "$extra_strings"
    if [ ! -s "$extra_strings" ] || grep -Eq '^[[:space:]]*(#|$)' "$extra_strings"; then
        printf '%s\n' 'Identity string files must contain one non-empty literal value per line.' >&2
        exit 2
    fi
fi

if scan_default_patterns; then
    printf '%s\n' 'Prohibited deployment or identity value detected.' >&2
    exit 1
fi

if [ -n "$extra_strings" ] && scan_operator_strings; then
    printf '%s\n' 'Operator-specific identity value detected.' >&2
    exit 1
fi

printf '%s\n' 'Identity and deployment scan passed.'
