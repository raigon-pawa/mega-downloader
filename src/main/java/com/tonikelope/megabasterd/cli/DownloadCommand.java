/*
 * mega-downloader
 * Originally MegaBasterd, © tonikelope since 2016. GPLv3.
 */
package com.tonikelope.megabasterd.cli;

import com.tonikelope.megabasterd.Download;
import com.tonikelope.megabasterd.MainPanel;
import com.tonikelope.megabasterd.MegaAPI;
import com.tonikelope.megabasterd.MiscTools;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "download", mixinStandardHelpOptions = true, description = "Download one or more MEGA links, waiting until they finish.")
public class DownloadCommand implements Callable<Integer> {

    @Parameters(paramLabel = "LINK", description = "One or more MEGA links (mega.nz/#!... , mega.nz/file/... , or MegaCrypter links).", arity = "1..*")
    private List<String> links;

    @Option(names = {"--dir"}, description = "Destination directory (defaults to the app's configured default download path).")
    private String dir;

    @Option(names = {"--account"}, description = "Email of a previously-added MEGA account to download with (see 'accounts add'). Omit for anonymous/direct-link downloads.")
    private String account;

    @Option(names = {"--priority"}, description = "Give these downloads priority over already-queued ones.")
    private boolean priority;

    @Override
    public Integer call() throws Exception {

        MainPanel main_panel = CliBootstrap.start();

        MegaAPI ma;

        if (account != null) {
            ma = MiscTools.checkMegaAccountLoginAndShowMasterPassDialog(main_panel, main_panel.getView(), account);
            if (ma == null) {
                System.err.println("Could not log in to MEGA account " + account + " (wrong master password, cancelled, or account not found -- see 'accounts list').");
                main_panel.shutdownForCli(1);
                return 1;
            }
        } else {
            ma = new MegaAPI();
        }

        String download_path = dir != null ? dir : main_panel.getDefault_download_path();

        List<Download> batch = new ArrayList<>();

        for (String link : links) {
            Download download = new Download(main_panel, ma, link, download_path, null, null, null, null, null,
                    main_panel.isUse_slots_down(), false, null, priority);
            batch.add(download);
            main_panel.getDownload_manager().getTransference_provision_queue().add(download);
        }

        main_panel.getDownload_manager().secureNotify();

        ConsoleProgressPrinter.waitAndPrint(batch, main_panel.getDownload_manager().getTransference_finished_queue());

        int failures = 0;
        for (Download d : batch) {
            if (d.getStatus_error() != null) {
                System.err.println("FAILED: " + d.getFile_name() + " -- " + d.getStatus_error());
                failures++;
            } else {
                System.out.println("OK: " + d.getFile_name());
            }
        }

        int exit_code = failures == 0 ? 0 : 1;
        main_panel.shutdownForCli(exit_code);
        return exit_code;
    }
}
