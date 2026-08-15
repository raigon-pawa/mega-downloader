/*
 * mega-downloader
 * Originally MegaBasterd, © tonikelope since 2016. GPLv3.
 */
package com.tonikelope.megabasterd.cli;

import com.tonikelope.megabasterd.MainPanel;
import com.tonikelope.megabasterd.MegaAPI;
import com.tonikelope.megabasterd.MegaErrorMessages;
import com.tonikelope.megabasterd.MiscTools;
import com.tonikelope.megabasterd.Upload;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Uploads a flat list of local files into one newly-created MEGA folder,
 * mirroring MainPanelView's upload flow (see the "add link" action there)
 * minus its recursive local-subdirectory recreation -- CLI users pass the
 * files they want uploaded directly.
 */
@Command(name = "upload", mixinStandardHelpOptions = true, description = "Upload one or more local files to MEGA, into a newly-created folder.")
public class UploadCommand implements Callable<Integer> {

    @Parameters(paramLabel = "FILE", description = "One or more local files to upload.", arity = "1..*")
    private List<File> files;

    @Option(names = {"--account"}, required = true, description = "Email of a previously-added MEGA account to upload with (see 'accounts add').")
    private String account;

    @Option(names = {"--folder-name"}, description = "Name of the MEGA folder to create for this batch (defaults to the first file's name plus a random suffix, matching the GUI's default).")
    private String folderName;

    @Option(names = {"--public-link"}, description = "Also generate a public share link for the created folder.")
    private boolean publicLink;

    @Override
    @SuppressWarnings("unchecked")
    public Integer call() throws Exception {

        MainPanel main_panel = CliBootstrap.start();

        MegaAPI ma = MiscTools.checkMegaAccountLoginAndShowMasterPassDialog(main_panel, main_panel.getView(), account);

        if (ma == null) {
            System.err.println("Could not log in to MEGA account " + account + " (wrong master password, cancelled, or account not found -- see 'accounts list').");
            main_panel.shutdownForCli(1);
            return 1;
        }

        try {
            byte[] parent_key = ma.genFolderKey();
            byte[] share_key = ma.genShareKey();

            String root_name = (folderName != null && !folderName.isEmpty())
                    ? folderName
                    : files.get(0).getName() + "_" + MiscTools.genID(10);

            HashMap<String, Object> res = ma.createDir(root_name, ma.getRoot_id(), parent_key, MiscTools.i32a2bin(ma.getMaster_key()));

            if (res == null) {
                int code = ma.getLastApiErrorCode();
                if (code != 0) {
                    MegaErrorMessages.showPopup(null, code, ma.getFull_email(), "while creating the upload folder on MEGA", MegaErrorMessages.Source.ACCOUNT);
                }
                System.err.println("Could not create the upload folder on MEGA (see log for details).");
                main_panel.shutdownForCli(1);
                return 1;
            }

            String parent_node = (String) ((Map) ((List) res.get("f")).get(0)).get("h");

            String folder_link = null;
            if (publicLink) {
                ma.shareFolder(parent_node, parent_key, share_key);
                folder_link = ma.getPublicFolderLink(parent_node, share_key);
            }

            List<Upload> batch = new ArrayList<>();

            for (File f : files) {
                Upload upload = new Upload(main_panel, ma, f.getAbsolutePath(), parent_node, null, null, parent_node, share_key, folder_link, false);
                batch.add(upload);
                main_panel.getUpload_manager().getTransference_provision_queue().add(upload);
            }

            main_panel.getUpload_manager().secureNotify();

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
