#!/bin/sh
# Entrypoint for the mega-downloader CLI container.
#
# Starts a virtual framebuffer (see the Dockerfile comment for why: the app
# keeps MainPanelView/dialogs as never-shown objects instead of deleting
# them, which requires AWT to NOT be headless), starts the ExpressVPN
# daemon, best-effort logs in if an activation code was supplied, enables
# background mode (required for `connect` to work without a GUI client),
# then runs the CLI jar.
set -e

# --- Virtual framebuffer ---
Xvfb :99 -screen 0 1024x768x16 -nolisten tcp &
XVFB_PID=$!
export DISPLAY=:99

# Give Xvfb a moment to start listening. No xdpyinfo dependency (not
# installed by default) -- a fixed short sleep is simpler and Xvfb starts
# in well under this window in practice.
sleep 1

JAVA_PID=""

cleanup() {
    # Not `exec`-ing java below specifically so this trap can run on
    # container stop (SIGTERM) and actually disconnect the VPN / tear down
    # Xvfb -- exec would replace this shell (and its traps) entirely.
    if [ -n "$JAVA_PID" ]; then
        kill "$JAVA_PID" 2>/dev/null || true
    fi
    if [ -n "$EXPRESSVPN_ACTIVATION_CODE" ]; then
        expressvpnctl disconnect >/dev/null 2>&1 || true
    fi
    kill "$XVFB_PID" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

# --- ExpressVPN daemon + activation ---
# Confirmed via a real install (v14.2.0): the CLI control tool is
# `expressvpnctl` (not the classic `expressvpn`), and the daemon binary is
# `expressvpn-daemon` (not `expressvpnd`) -- both under /opt/expressvpn/bin,
# which the Dockerfile adds to PATH; expressvpnctl is also symlinked at
# /usr/local/bin/expressvpnctl. The daemon normally starts via systemd,
# which doesn't run inside a plain container, so start it directly instead.
if command -v expressvpn-daemon >/dev/null 2>&1; then
    expressvpn-daemon >/tmp/expressvpn-daemon.log 2>&1 &
    sleep 2
fi

if [ -n "$EXPRESSVPN_ACTIVATION_CODE" ]; then
    echo "Activating ExpressVPN..."
    # Confirmed via real `expressvpnctl --help` output: there is no
    # `activate <code>` command. Activation is `login <file>`, where the
    # file holds the activation code (or username/password) on its own
    # line(s) -- the code can't be passed directly as an argument. Written
    # to a private tmpfile and removed immediately after use.
    EXPRESSVPN_LOGIN_FILE=$(mktemp)
    printf '%s\n' "$EXPRESSVPN_ACTIVATION_CODE" > "$EXPRESSVPN_LOGIN_FILE"
    chmod 600 "$EXPRESSVPN_LOGIN_FILE"
    expressvpnctl login "$EXPRESSVPN_LOGIN_FILE" || \
        echo "WARNING: ExpressVPN login failed or was already active -- continuing anyway (VPN-dependent commands will fail if it's genuinely not activated)."
    rm -f "$EXPRESSVPN_LOGIN_FILE"
fi

# `connect` explicitly refuses to work unless either the GUI client is
# running or background mode is enabled -- confirmed via real
# `expressvpnctl --help` output: "By default, the ExpressVPN daemon is
# inactive when the GUI client is not running." There is no GUI client in
# this container, so without this, every connect attempt would silently
# fail. Attempted unconditionally (not just alongside a fresh login) since
# this setting's persistence across container restarts isn't confirmed,
# and it's a safe, idempotent call either way.
expressvpnctl background enable || \
    echo "WARNING: Could not enable ExpressVPN background mode -- 'connect' will likely fail without the GUI client running."

# Backgrounded + waited-on (not exec'd) so the trap above can intervene on
# SIGTERM/SIGINT; `wait` also propagates java's own exit code as this
# script's exit code, same as exec would have.
java -jar /app/mega-downloader.jar "$@" &
JAVA_PID=$!
wait "$JAVA_PID"
