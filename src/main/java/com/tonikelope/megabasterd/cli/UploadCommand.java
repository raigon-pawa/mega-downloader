/*
 * mega-downloader
 * Originally MegaBasterd, © tonikelope since 2016. GPLv3.
 */
package com.tonikelope.megabasterd.cli;

import com.tonikelope.megabasterd.MainPanel;
import com.tonikelope.megabasterd.MegaAPI;
import com.tonikelope.megabasterd.MiscTools;
import com.tonikelope.megabasterd.Upload;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Uploads a flat list of local files into one newly-created MEGA folder
 * (no recursive local-subdirectory recreation -- CLI users pass the files
 * they want uploaded directly).
 */
@Command(name = "upload", mixinStandardHelpOptions = true, description = "Upload one or more local files to MEGA, into a newly-created folder.")
public class UploadCommand implements Callable<Integer> {

    @Parameters(paramLabel = "FILE", description = "One or more local files to upload.", arity = "1..*")
    private List<File> files;

    @Option(names = {"--account"}, required = true, description = "Email of a previously-added MEGA account to upload with (see 'accounts add').")
    private String account;

    @Option(names = {"--folder-name"}, description = "Name of the MEGA folder to create for this batch (defaults to the first file's name plus a random suffix).")
    private String folderName;

    @Option(names = {"--public-link"}, description = "Also generate a public share link for the created folder.")
    private boolean publicLink;

    @Override
    public Integer call() throws Exception {

        MainPanel main_panel = CliBootstrap.start();

        MegaAPI ma = MiscTools.checkMegaAccountLoginAndShowMasterPassDialog(main_panel, account);

        if (ma == null) {
            System.err.println("Could not log in to MEGA account " + account + " (wrong master password, cancelled, or account not found -- see 'accounts list').");
            main_panel.shutdownForCli(1);
            return 1;
        }

        try {
            List<Upload> batch = new ArrayList<>();

            String folder_link = main_panel.getUpload_manager().queueFiles(ma, files, folderName, publicLink, false, batch);

            ConsoleProgressPrinter.waitAndPrint(batch, main_panel.getUpload_manager().getTransference_finished_queue());

            int failures = 0;
            for (Upload u : batch) {
                if (u.getStatus_error() != null) {
                    System.err.println("FAILED: " + u.getFile_name() + " -- " + u.getStatus_error());
                    failures++;
                } else {
                    System.out.println("OK: " + u.getFile_name());
                }
            }

            if (folder_link != null) {
                System.out.println("Public folder link: " + folder_link);
            }

            int exit_code = failures == 0 ? 0 : 1;
            main_panel.shutdownForCli(exit_code);
            return exit_code;

        } catch (Exception ex) {
            System.err.println("Upload failed: " + ex.getMessage());
            main_panel.shutdownForCli(1);
            return 1;
        }
    }
}
