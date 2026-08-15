/*
 * mega-downloader
 * Originally MegaBasterd, © tonikelope since 2016. GPLv3.
 */
package com.tonikelope.megabasterd;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Replaces {@code SmartMegaProxyManager}'s open-proxy pool with ExpressVPN
 * server-location rotation, wrapping the {@code expressvpn} Linux CLI via
 * {@link ProcessBuilder}. Mirrors the old manager's two-mode behaviour
 * (PASSIVE: only engage after a 509, staying "armed" for a recheck window;
 * FORCE: always engaged) and its reactive-rotation-on-failure pattern, but
 * the mechanism underneath is fundamentally different:
 * {@code SmartMegaProxyManager} handed out a different proxy IP to each of
 * N concurrent worker threads simultaneously; ExpressVPN gives the whole
 * machine ONE exit IP at a time, and switching it takes a real
 * disconnect/reconnect (seconds, not free). So instead of "give me a
 * proxy", callers ask "make sure protective routing is engaged right now"
 * ({@link #ensureProtectiveRouting}) -- the first caller that needs a
 * connection pays for it, concurrent callers piggyback on the same one via
 * {@code _connect_lock} -- and report failures via
 * {@link #reportQuotaError} to trigger a rotation to a different location.
 * Once connected, ExpressVPN routes ALL of the machine's traffic through
 * the tunnel at the OS level, so callers no longer need to build a
 * {@code java.net.Proxy} object per request the way the proxy pool
 * required -- a plain {@code url.openConnection()} is enough.
 */
public final class VpnRotationManager {

    private static final Logger LOG = Logger.getLogger(VpnRotationManager.class.getName());

    public static final int RECHECK_509_WINDOW_DEFAULT = 3600;
    public static final int VPN_COOLDOWN_DEFAULT = 300;
    public static final int VPN_CONNECT_TIMEOUT_DEFAULT = 30;
    public static final boolean FORCE_VPN_DEFAULT = false;

    /**
     * Compatibility constants only: the (unreachable, GUI-only) Settings
     * dialog's SmartProxy tab still references these under their original
     * SmartMegaProxyManager names for its default spinner/checkbox values.
     * Since that dialog is never shown in the CLI/Docker product, exact
     * values don't matter -- these just need to exist so the dead code
     * compiles. Real VPN defaults are the constants above.
     */
    public static final int PROXY_AUTO_REFRESH_TIME = 60;
    public static final int PROXY_BLOCK_TIME = VPN_COOLDOWN_DEFAULT;
    public static final boolean RESET_SLOT_PROXY = true;
    public static final boolean RANDOM_SELECT = true;

    /**
     * Compatibility stub: QuotaRecoveryPanel's "test proxy pool" diagnostic
     * feature has no VPN equivalent (there's nothing to batch-test against
     * a single machine-wide VPN connection). Always empty -- that dead-code
     * path already handles an empty snapshot gracefully ("no proxies to
     * test").
     */
    public List<String[]> getProxySnapshot() {
        return Collections.emptyList();
    }

    /**
     * Compatibility stub for the same dead-code path's "save working
     * proxies" button. No-op.
     */
    public int saveWorkingProxiesToCustomList(java.util.Collection<String> working_addrs) throws java.sql.SQLException {
        return 0;
    }

    private final MainPanel _main_panel;
    private final Object _connect_lock = new Object();
    private final Map<String, Long> _location_cooldown_until = new ConcurrentHashMap<>();

    private volatile String _current_location; // null == not connected
    private volatile List<String> _locations = Collections.emptyList();
    private volatile boolean _force_vpn;
    private volatile int _cooldown_ms;
    private volatile int _connect_timeout_ms;
    private volatile int _recheck_509_window;

    public VpnRotationManager(MainPanel main_panel) {
        _main_panel = main_panel;
        refreshVpnSettings();
    }

    /**
     * Reloads settings from the DB. Same trigger points SmartMegaProxyManager
     * had (constructor + runtime settings-save), reused as-is: {@code
     * vpn_enabled}/{@code force_vpn}/{@code vpn_locations}/{@code
     * vpn_cooldown}/{@code vpn_connect_timeout}/{@code vpn_509_recheck_window}
     * -- deliberately NEW key names, independent of the old {@code
     * smart_proxy}/{@code custom_proxy_list}/{@code smartproxy_*} keys the
     * (now unreachable) GUI Settings dialog still reads/writes, so there is
     * no ambiguity about which keys actually drive behaviour.
     */
    public void refreshVpnSettings() {

        String force = DBTools.selectSettingValue("force_vpn");
        _force_vpn = force != null ? force.equals("yes") : FORCE_VPN_DEFAULT;

        String locations_raw = DBTools.selectSettingValue("vpn_locations");
        if (locations_raw == null || locations_raw.trim().isEmpty()) {
            _locations = Collections.emptyList(); // empty -> let expressvpn pick ("smart" location)
        } else {
            List<String> parsed = new ArrayList<>();
            for (String loc : locations_raw.split(",")) {
                String trimmed = loc.trim();
                if (!trimmed.isEmpty()) {
                    parsed.add(trimmed);
                }
            }
            _locations = parsed;
        }

        _cooldown_ms = MiscTools.parseIntOr(DBTools.selectSettingValue("vpn_cooldown"), VPN_COOLDOWN_DEFAULT) * 1000;
        _connect_timeout_ms = MiscTools.parseIntOr(DBTools.selectSettingValue("vpn_connect_timeout"), VPN_CONNECT_TIMEOUT_DEFAULT) * 1000;

        int recheck = MiscTools.parseIntOr(DBTools.selectSettingValue("vpn_509_recheck_window"), RECHECK_509_WINDOW_DEFAULT);
        _recheck_509_window = Math.max(60, Math.min(86_400, recheck));

        LOG.log(Level.INFO, "[VPN] FORCE: {0}   LOCATIONS: {1}   COOLDOWN: {2}s   CONNECT_TIMEOUT: {3}s   RECHECK-509: {4}s",
                new Object[]{_force_vpn, _locations.isEmpty() ? "(smart)" : _locations, _cooldown_ms / 1000, _connect_timeout_ms / 1000, _recheck_509_window});
    }

    public boolean isForceVpn() {
        return _force_vpn;
    }

    public int getConnectTimeoutMs() {
        return _connect_timeout_ms;
    }

    public int getRecheck509Window() {
        return _recheck_509_window;
    }

    public boolean isConnected() {
        return _current_location != null;
    }

    public String getCurrentLocation() {
        return _current_location;
    }

    /**
     * Ensures ExpressVPN is connected. Cheap/non-blocking if already
     * connected; otherwise spawns {@code expressvpn connect} and waits up
     * to {@link #getConnectTimeoutMs()}. Safe to call concurrently from
     * many worker threads -- only the first one to arrive actually
     * connects, via {@code _connect_lock}; the rest observe the result.
     *
     * @return the location now active (opaque -- pass it back to {@link
     * #reportQuotaError} if a request made through it later fails), or
     * null if not connected (unavailable/failed)
     */
    public String ensureProtectiveRouting() {
        String loc = _current_location;
        if (loc != null) {
            return loc;
        }
        synchronized (_connect_lock) {
            if (_current_location != null) {
                return _current_location;
            }
            return doConnect(pickLocation(null)) ? _current_location : null;
        }
    }

    /**
     * Called when a request failed while protective routing was (or should
     * be) engaged -- analogous to {@code SmartMegaProxyManager.blockProxy}.
     * Puts the failed location on cooldown and rotates to a different one.
     *
     * @param observed_location the location the caller was actually routed
     * through when its request failed (the value {@link
     * #ensureProtectiveRouting} returned earlier) -- REQUIRED for
     * correctness, not just bookkeeping: MEGA's 509 is IP-wide, so when it
     * fires, every one of a download's 6-20 concurrent chunk workers tends
     * to fail together, not one at a time. Without this check every one of
     * them would trigger its own full reconnect for what is really the
     * same single problem -- {@code _connect_lock} only serializes those
     * reconnects, it doesn't prevent them. Comparing against the CURRENT
     * location (not just "are we connected") means only the first caller's
     * report actually rotates; by the time the 2nd-6th callers arrive
     * (already serialized behind the lock), {@code _current_location} has
     * moved on and their reports become correctly-recognized no-ops.
     */
    public void reportQuotaError(String observed_location, String cause) {
        synchronized (_connect_lock) {

            if (observed_location == null || !observed_location.equals(_current_location)) {
                // Stale report: either this caller was never actually
                // routed (observed_location null), or another worker
                // already rotated away from the location this report is
                // about. Either way, rotating again here would be a
                // redundant reconnect for an already-solved problem.
                LOG.log(Level.FINE, "[VPN] Ignoring stale quota-error report for {0} (current is {1})",
                        new Object[]{observed_location, _current_location});
                return;
            }

            // observed_location == _current_location here (checked above).
            _location_cooldown_until.put(observed_location, System.currentTimeMillis() + _cooldown_ms);
            LOG.log(Level.WARNING, "[VPN] Rotating away from {0} ({1})", new Object[]{observed_location, cause});
            _current_location = null;
            doConnect(pickLocation(observed_location));
        }
    }

    public void disconnect() {
        synchronized (_connect_lock) {
            if (_current_location == null) {
                return;
            }
            try {
                Process p = new ProcessBuilder("expressvpn", "disconnect").redirectErrorStream(true).start();
                p.waitFor(_connect_timeout_ms, TimeUnit.MILLISECONDS);
            } catch (Exception ex) {
                LOG.log(Level.WARNING, "[VPN] disconnect failed: {0}", ex.getMessage());
            } finally {
                _current_location = null;
            }
        }
    }

    /**
     * Picks a location not on cooldown and not equal to {@code exclude},
     * shuffled so concurrent rotations across different downloads don't
     * all pile onto the same "first" entry. Falls back to {@code exclude}
     * itself (or "smart") if every configured location is currently
     * cooling down -- better to retry the least-recently-failed one than
     * to refuse to reconnect at all.
     */
    private String pickLocation(String exclude) {
        if (_locations.isEmpty()) {
            return null; // null location arg -> `expressvpn connect` with no location -> ExpressVPN's own "smart location"
        }
        List<String> candidates = new ArrayList<>(_locations);
        Collections.shuffle(candidates);
        long now = System.currentTimeMillis();
        for (String loc : candidates) {
            if (loc.equals(exclude)) {
                continue;
            }
            Long cooldown_until = _location_cooldown_until.get(loc);
            if (cooldown_until == null || cooldown_until < now) {
                return loc;
            }
        }
        return candidates.get(0);
    }

    /**
     * Must be called with {@code _connect_lock} held.
     */
    private boolean doConnect(String location) {
        List<String> cmd = new ArrayList<>(Arrays.asList("expressvpn", "connect"));
        if (location != null) {
            cmd.add(location);
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }

            boolean finished = p.waitFor(_connect_timeout_ms, TimeUnit.MILLISECONDS);

            if (!finished) {
                p.destroyForcibly();
                LOG.log(Level.WARNING, "[VPN] connect timed out after {0}s (location={1})",
                        new Object[]{_connect_timeout_ms / 1000, location != null ? location : "smart"});
                return false;
            }

            if (p.exitValue() != 0) {
                LOG.log(Level.WARNING, "[VPN] connect failed (location={0}, exit={1}): {2}",
                        new Object[]{location != null ? location : "smart", p.exitValue(), output.toString().trim()});
                return false;
            }

            _current_location = location != null ? location : "smart";
            LOG.log(Level.INFO, "[VPN] Connected ({0})", _current_location);
            return true;

        } catch (Exception ex) {
            // Covers "expressvpn" not on PATH (IOException) as well as
            // InterruptedException from waitFor -- either way, fall back to
            // direct connections exactly like the old getProxy()==null path.
            LOG.log(Level.WARNING, "[VPN] expressvpn CLI unavailable or failed: {0}", ex.getMessage());
            return false;
        }
    }
}
