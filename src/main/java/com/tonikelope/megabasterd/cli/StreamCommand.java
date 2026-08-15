/*
 * mega-downloader
 * Originally MegaBasterd, © tonikelope since 2016. GPLv3.
 */
package com.tonikelope.megabasterd.cli;

import com.tonikelope.megabasterd.MainPanel;
import com.tonikelope.megabasterd.MegaAPI;
import com.tonikelope.megabasterd.MiscTools;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * MainPanel already starts the KissVideoStreamServer unconditionally (same
 * as the GUI build) -- this command's only job is to build the same
 * {@code http://localhost:PORT/video/<token>} URL StreamerDialog used to
 * show the user, print it, and keep the process alive so the server keeps
 * serving (an external player -- VLC, a browser -- connects to that URL).
 */
@Command(name = "stream", mixinStandardHelpOptions = true, description = "Print a streamable URL for a MEGA video link and keep serving it until interrupted.")
public class StreamCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "MEGA video link.")
    private String link;

    @Option(names = {"--account"}, description = "Email of a previously-added MEGA account to use, if the link requires one.")
    private String account;

    @Override
    public Integer call() throws Exception {

        MainPanel main_panel = CliBootstrap.start();

        String resolved_link = link;

        if (resolved_link.contains("#F*")) {
            MegaAPI ma = new MegaAPI();
            Set<String> links = new HashSet<>();
            links.add(resolved_link);
            List<String> nlinks = ma.GENERATE_N_LINKS(links);
            if (nlinks.isEmpty()) {
                System.err.println("Could not resolve folder link.");
                main_panel.shutdownForCli(1);
                return 1;
            }
            resolved_link = nlinks.get(0);
        }

        String data = MiscTools.Bin2UrlBASE64(((account != null ? account : "") + "|" + resolved_link).getBytes(StandardCharsets.UTF_8));

        String stream_url = "http://localhost:" + MainPanel.STREAMER_PORT + "/video/" + data;

        System.out.println("Streaming URL (open in a browser or media player): " + stream_url);
        System.out.println("Press Ctrl-C to stop.");

        // Block forever -- the streaming HTTP server runs on daemon threads
        // started inside MainPanel's constructor; this just keeps the
        // process alive to serve requests. Ctrl-C (SIGINT) ends it.
        Thread.currentThread().join();

        return 0;
    }
}
