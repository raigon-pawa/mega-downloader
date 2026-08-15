#!/bin/sh
# Entrypoint for the MegaBasterd CLI container.
#
# Starts a virtual framebuffer (see the Dockerfile comment for why: the app
# keeps MainPanelView/dialogs as never-shown objects instead of deleting
# them, which requires AWT to NOT be headless), best-effort activates
# ExpressVPN if an activation code was supplied, then runs the CLI jar.
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
        expressvpn disconnect >/dev/null 2>&1 || true
    fi
    kill "$XVFB_PID" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

# --- ExpressVPN daemon + activation ---
# NOTE: the ExpressVPN Linux app normally starts its background daemon
# (expressvpnd) via systemd, which doesn't run inside a plain container.
# This starts it directly instead. The exact binary name/path can vary by
# version -- if `expressvpn status` fails below, find where the .run
# installer actually put it (`find / -iname 'expressvpnd*' 2>/dev/null`,
# commonly /usr/sbin or /opt/expressvpn) and adjust this line. Untested
# against a real ExpressVPN install in this environment (no installer or
# activation code was available) -- verify this against your own install.
if command -v expressvpnd >/dev/null 2>&1; then
    expressvpnd >/tmp/expressvpnd.log 2>&1 &
    sleep 2
fi

if [ -n "$EXPRESSVPN_ACTIVATION_CODE" ]; then
    echo "Activating ExpressVPN..."
    expressvpn activate "$EXPRESSVPN_ACTIVATION_CODE" || \
        echo "WARNING: ExpressVPN activation failed or was already active -- continuing anyway (VPN-dependent commands will fail if it's genuinely not activated)."
fi

# Backgrounded + waited-on (not exec'd) so the trap above can intervene on
# SIGTERM/SIGINT; `wait` also propagates java's own exit code as this
# script's exit code, same as exec would have.
java -jar /app/megabasterd.jar "$@" &
JAVA_PID=$!
wait "$JAVA_PID"
