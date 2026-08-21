#!/bin/zsh
set -euo pipefail

script_dir=${0:A:h}
install_mode=${1:-dry-run}
relay_root=${ANDROID_IMESSAGE_RELAY_HOME:-"$HOME/Library/Application Support/AndroidIMessageRelay"}
python_bin=${PYTHON_BIN:-/opt/homebrew/bin/python3.12}
launch_agent="$HOME/Library/LaunchAgents/io.github.parryqiu.android-imessage-relay.plist"
venv_dir="$relay_root/venv"
bin_dir="$relay_root/bin"
log_dir="$relay_root/logs"
run_dir="$relay_root/run"

if [[ "$install_mode" != "dry-run" && "$install_mode" != "live" ]]; then
    print -u2 "Usage: $0 [dry-run|live]"
    exit 2
fi
if [[ ! -x "$python_bin" ]]; then
    print -u2 "Python 3.10 or newer is required. Set PYTHON_BIN to its absolute path."
    exit 2
fi

mkdir -p "$bin_dir" "$log_dir" "$run_dir" "$HOME/Library/LaunchAgents"
chmod 700 "$relay_root" "$bin_dir" "$log_dir" "$run_dir"
"$python_bin" -m venv "$venv_dir"
"$venv_dir/bin/python" -m pip install --disable-pip-version-check --require-hashes \
    -r "$script_dir/requirements.lock"
"$venv_dir/bin/python" -m pip install --disable-pip-version-check --no-deps "$script_dir"
/usr/bin/install -m 600 "$script_dir/assets/send_message.applescript" "$bin_dir/send_message.applescript"
/usr/bin/xcrun swiftc "$script_dir/assets/RecipientKeychain.swift" -o "$bin_dir/recipient-keychain"
chmod 700 "$bin_dir/recipient-keychain"

dry_run_argument=""
if [[ "$install_mode" == "dry-run" ]]; then
    dry_run_argument="        <string>--dry-run</string>"
fi

cat > "$launch_agent" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>io.github.parryqiu.android-imessage-relay</string>
    <key>ProgramArguments</key>
    <array>
        <string>$venv_dir/bin/android-imessage-relay</string>
        <string>--data-dir</string>
        <string>$relay_root/data</string>
        <string>serve</string>
        <string>--socket</string>
        <string>$run_dir/relay.sock</string>
        <string>--messages-script</string>
        <string>$bin_dir/send_message.applescript</string>
        <string>--recipient-helper</string>
        <string>$bin_dir/recipient-keychain</string>
$dry_run_argument
    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
    <key>ProcessType</key>
    <string>Background</string>
    <key>StandardOutPath</key>
    <string>$log_dir/stdout.log</string>
    <key>StandardErrorPath</key>
    <string>$log_dir/stderr.log</string>
</dict>
</plist>
PLIST

plutil -lint "$launch_agent"
launchctl bootout "gui/$(id -u)/io.github.parryqiu.android-imessage-relay" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$launch_agent"
launchctl enable "gui/$(id -u)/io.github.parryqiu.android-imessage-relay"
print "Installed Android iMessage Relay in $install_mode mode."
