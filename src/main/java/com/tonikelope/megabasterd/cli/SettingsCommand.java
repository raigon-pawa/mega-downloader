/*
 * mega-downloader
 * Originally MegaBasterd, © tonikelope since 2016. GPLv3.
 */
package com.tonikelope.megabasterd.cli;

import com.tonikelope.megabasterd.DBTools;
import com.tonikelope.megabasterd.MainPanel;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * Generic key/value pass-through to the same {@code settings} SQLite table
 * the old Swing SettingsDialog read/wrote via DBTools -- every setting the
 * GUI exposed (download/upload slots, speed limits, proxy config, VPN
 * config once Phase 4 lands, etc.) is reachable this way. No CLI-side
 * validation of key names: this is intentionally a thin pass-through, same
 * as the GUI dialog was.
 */
@Command(name = "settings", mixinStandardHelpOptions = true, description = "Read or write app settings (same storage the old Settings dialog used).", subcommands = {
    SettingsCommand.Get.class, SettingsCommand.Set.class
})
public class SettingsCommand {

    @Command(name = "get", mixinStandardHelpOptions = true, description = "Print the value of a setting key (nothing if unset).")
    public static class Get implements Callable<Integer> {

        @Parameters(index = "0", description = "Setting key, e.g. 'default_download_path', 'max_dl', 'vpn_locations'.")
        private String key;

        @Override
        public Integer call() {
            MainPanel.bootstrapDbAndSettings();
            String value = DBTools.selectSettingValue(key);
            if (value != null) {
                System.out.println(value);
            }
            return 0;
        }
    }

    @Command(name = "set", mixinStandardHelpOptions = true, description = "Write a setting key/value pair.")
    public static class Set implements Callable<Integer> {

        @Parameters(index = "0", description = "Setting key.")
        private String key;

        @Parameters(index = "1", description = "Setting value.")
        private String value;

        @Override
        public Integer call() {
            MainPanel.bootstrapDbAndSettings();
            try {
                DBTools.insertSettingValue(key, value);
                return 0;
            } catch (Exception ex) {
                System.err.println("Failed to write setting: " + ex.getMessage());
                return 1;
            }
        }
    }
}
