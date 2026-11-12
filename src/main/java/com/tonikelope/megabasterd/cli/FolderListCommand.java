/*
 * mega-downloader
 * Originally MegaBasterd, © tonikelope since 2016. GPLv3.
 */
package com.tonikelope.megabasterd.cli;

import com.tonikelope.megabasterd.MainPanel;
import com.tonikelope.megabasterd.MegaAPI;
import com.tonikelope.megabasterd.MiscTools;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "folder-list", mixinStandardHelpOptions = true, description = "List the files inside a MEGA folder link.")
public class FolderListCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "MEGA folder link (mega.nz/#F!ID!KEY form).")
    private String link;

    @Override
    @SuppressWarnings("unchecked")
    public Integer call() {

        MainPanel main_panel = CliBootstrap.start();

        String folder_id = MiscTools.findFirstRegex("#F!([^!]+)", link, 1);
        String folder_key = MiscTools.findFirstRegex("#F![^!]+!(.+)", link, 1);

        if (folder_id == null || folder_key == null) {
            System.err.println("Not a recognisable MEGA folder link (expected .../#F!ID!KEY).");
            main_panel.shutdownForCli(1);
            return 1;
        }

        // A leading "@subfolder_id" after the folder id (a deep-link into a
        // subfolder) is not meaningful here -- strip it so
        // getFolderNodes gets a clean handle, and just list the whole tree.
        if (folder_id.contains("@")) {
            folder_id = folder_id.split("@")[0];
        }

        try {
            MegaAPI ma = new MegaAPI();

            Map<String, Object> folder_nodes = ma.getFolderNodes(folder_id, folder_key, true);

            if (folder_nodes == null || folder_nodes.isEmpty()) {
                System.out.println("(empty or inaccessible folder)");
                main_panel.shutdownForCli(0);
                return 0;
            }

            for (Map.Entry<String, Object> entry : folder_nodes.entrySet()) {
                HashMap<String, Object> node = (HashMap<String, Object>) entry.getValue();
                Object name = node.get("name");
                Object size = node.get("size");
                if (name != null) {
                    System.out.println((size != null ? "[" + MiscTools.formatBytes(((Number) size).longValue()) + "] " : "") + name + "  (" + entry.getKey() + ")");
                }
            }

            main_panel.shutdownForCli(0);
            return 0;

        } catch (Exception ex) {
            System.err.println("Failed to list folder: " + ex.getMessage());
            main_panel.shutdownForCli(1);
            return 1;
        }
    }
}
