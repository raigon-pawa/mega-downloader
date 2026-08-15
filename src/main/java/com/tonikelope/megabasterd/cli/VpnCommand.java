/*
 * mega-downloader
 * Originally MegaBasterd, © tonikelope since 2016. GPLv3.
 */
package com.tonikelope.megabasterd.cli;

import com.tonikelope.megabasterd.MainPanel;
import com.tonikelope.megabasterd.VpnRotationManager;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;

/**
 * Manual override/diagnostics for VpnRotationManager. Normal operation
 * never needs these -- the manager connects/rotates on its own from the
 * download/upload/stream/API code paths -- but they're useful to verify
 * ExpressVPN is reachable and configured correctly before relying on it.
 */
@Command(name = "vpn", mixinStandardHelpOptions = true, description = "ExpressVPN status/control.", subcommands = {
    VpnCommand.Status.class, VpnCommand.Connect.class, VpnCommand.Disconnect.class
})
public class VpnCommand {

    @Command(name = "status", mixinStandardHelpOptions = true, description = "Show whether VPN routing is enabled and currently connected.")
    public static class Status implements Callable<Integer> {
        @Override
        public Integer call() {
            MainPanel main_panel = CliBootstrap.start();
            try {
                if (!MainPanel.isUse_smart_proxy()) {
                    System.out.println("VPN routing is disabled (settings set vpn_enabled yes).");
                    return 0;
                }
                VpnRotationManager vpn = MainPanel.getProxy_manager();
                if (vpn == null) {
                    System.out.println("VPN routing is enabled but the manager hasn't started yet.");
                    return 1;
                }
                System.out.println("Force mode: " + (vpn.isForceVpn() ? "ON (always connected)" : "OFF (connects reactively on 509)"));
                System.out.println("Connected: " + vpn.isConnected() + (vpn.isConnected() ? " (" + vpn.getCurrentLocation() + ")" : ""));
                return 0;
            } finally {
                main_panel.shutdownForCli(0);
            }
        }
    }

    @Command(name = "connect", mixinStandardHelpOptions = true, description = "Force-connect ExpressVPN now (useful to verify the CLI/activation is working).")
    public static class Connect implements Callable<Integer> {
        @Override
        public Integer call() {
            MainPanel main_panel = CliBootstrap.start();
            VpnRotationManager vpn = MainPanel.getProxy_manager();
            if (vpn == null) {
                vpn = new VpnRotationManager(main_panel);
                MainPanel.setProxy_manager(vpn);
            }
            String location = vpn.ensureProtectiveRouting();
            System.out.println(location != null ? "Connected (" + location + ")" : "Failed to connect -- check that expressvpnctl is installed, activated, and on PATH.");
            int exit_code = location != null ? 0 : 1;
            main_panel.shutdownForCli(exit_code);
            return exit_code;
        }
    }

    @Command(name = "disconnect", mixinStandardHelpOptions = true, description = "Disconnect ExpressVPN now.")
    public static class Disconnect implements Callable<Integer> {
        @Override
        public Integer call() {
            MainPanel main_panel = CliBootstrap.start();
            VpnRotationManager vpn = MainPanel.getProxy_manager();
            if (vpn != null) {
                vpn.disconnect();
            }
            System.out.println("Disconnected.");
            main_panel.shutdownForCli(0);
            return 0;
        }
    }
}
