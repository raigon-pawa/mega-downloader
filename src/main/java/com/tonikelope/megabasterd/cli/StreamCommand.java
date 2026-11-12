/*
 * mega-downloader
 * Originally MegaBasterd, © tonikelope since 2016. GPLv3.
 */
package com.tonikelope.megabasterd.cli;

import com.tonikelope.megabasterd.KissVideoStreamServer;
import com.tonikelope.megabasterd.MainPanel;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * MainPanel already starts the KissVideoStreamServer unconditionally --
 * this command's only job is to build the
 * {@code http://localhost:PORT/video/<token>} URL, print it, and keep the
 * process alive so the server keeps serving (an external player -- VLC, a
 * browser -- connects to that URL).
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

        String stream_url = KissVideoStreamServer.streamUrl(account, link);

        if (stream_url == null) {
            System.err.println("Could not resolve folder link.");
            main_panel.shutdownForCli(1);
            return 1;
        }

        System.out.println("Streaming URL (open in a browser or media player): " + stream_url);
        System.out.println("Press Ctrl-C to stop.");

        // Block forever -- the streaming HTTP server runs on daemon threads
        // started inside MainPanel's constructor; this just keeps the
        // process alive to serve requests. Ctrl-C (SIGINT) ends it.
        Thread.currentThread().join();

        return 0;
    }
}
