/*
 * mega-downloader
 * Originally MegaBasterd, © tonikelope since 2016. GPLv3.
 */
package com.tonikelope.megabasterd;

import java.sql.SQLException;
import java.util.ArrayList;
import static java.util.logging.Level.SEVERE;
import java.util.logging.Logger;

/**
 *
 * @author tonikelope
 */
public class UploadManager extends TransferenceManager {

    private static final Logger LOG = Logger.getLogger(UploadManager.class.getName());

    private final Object _log_file_lock;

    public UploadManager(MainPanel main_panel) {

        super(main_panel, main_panel.getMax_ul());

        _log_file_lock = new Object();
    }

    public Object getLog_file_lock() {
        return _log_file_lock;
    }

    /**
     * #774 -- when the upload queue transitions from non-empty to empty
     * with at least one OK finish, dispatch the user's configured
     * post-upload-finish command. Fires independently of the download-side
     * variant and of the legacy 509 command; if all three converge they
     * run concurrently. {@link MainPanel#run_ul_finish_command} no-ops when
     * the feature is disabled, so no per-call gate is needed here.
     */
    @Override
    protected void onAllTransferencesFinished() {
        MainPanel.run_ul_finish_command();
    }

    @Override
    public void provision(final Transference upload) {

        try {
            ((Upload) upload).provisionIt();
        } catch (RuntimeException ex) {
            // Belt-and-braces: provisionIt now catches its own runtime
            // exceptions, but if a future change adds a code path that
            // throws, we must NOT leave the BoundedExecutor task without
            // routing the transference to either aux_queue or
            // finished_queue -- otherwise the UI shows a perpetual
            // "Provisioning..." with no way to recover.
            LOG.log(SEVERE, "Upload provisionIt threw -- routing to finished_queue", ex);
        }

        if (((Upload) upload).isProvision_ok()) {

            increment_total_size(upload.getFile_size());

            getTransference_waitstart_aux_queue().add(upload);

        } else {

            getTransference_finished_queue().add(upload);
        }

        secureNotify();
    }

    @Override
    public void remove(Transference[] uploads) {

        ArrayList<String[]> delete_up = new ArrayList<>();

        for (final Transference u : uploads) {

            getTransference_waitstart_queue().remove(u);

            getTransference_running_list().remove(u);

            getTransference_finished_queue().remove(u);

            ((Upload) u).finalizeTotals();

            // Always remove the DB row. Same fix as DownloadManager.remove --
            // cancelled-but-not-closed uploads kept getting resurrected on
            // restart. See #699.
            delete_up.add(new String[]{u.getFile_name(), ((Upload) u).getMa().getFull_email()});
        }

        try {
            DBTools.deleteUploads(delete_up.toArray(new String[delete_up.size()][]));
        } catch (SQLException ex) {
            LOG.log(SEVERE, null, ex);
        }

        secureNotify();
    }

    /** Every queued/running/finished upload with its MEGA links. */
    public String getAllLinksText() {

        ArrayList<String> links = new ArrayList<>();

        String out = "";

        for (Transference t : _transference_waitstart_aux_queue) {
            Upload up = (Upload) t;
            links.add(up.getFile_name() + " [" + up.getMa().getEmail() + "] " + (up.getFolder_link() != null ? up.getFolder_link() : ""));
        }

        for (Transference t : _transference_waitstart_queue) {

            Upload up = (Upload) t;
            links.add(up.getFile_name() + " [" + up.getMa().getEmail() + "] " + (up.getFolder_link() != null ? up.getFolder_link() : ""));
        }

        out += String.join("\r\n", links);

        links.clear();

        for (Transference t : _transference_running_list) {

            Upload up = (Upload) t;
            links.add(up.getFile_name() + " [" + up.getMa().getEmail() + "] " + (up.getFolder_link() != null ? up.getFolder_link() : "") + (up.getFile_link() != null ? " " + up.getFile_link() : ""));
        }

        out += String.join("\r\n", links);

        links.clear();

        for (Transference t : _transference_finished_queue) {

            Upload up = (Upload) t;
            links.add("(UPLOAD FINISHED) " + up.getFile_name() + " [" + up.getMa().getEmail() + "] " + (up.getFolder_link() != null ? up.getFolder_link() : "") + (up.getFile_link() != null ? " " + up.getFile_link() : ""));
        }

        out += String.join("\r\n", links);

        return out;

    }

    /**
     * Creates a new folder on MEGA and queues one upload per local file into
     * it (flat: local sub-directories are not recreated). Shared by the CLI
     * and the GUI.
     *
     * @param folder_name name of the MEGA folder; null/empty = first file's
     * name plus a random suffix
     * @param queued receives the created uploads, in order
     * @return the public folder link when {@code public_link}, else null
     */
    @SuppressWarnings("unchecked")
    public String queueFiles(MegaAPI ma, java.util.List<java.io.File> files, String folder_name, boolean public_link, boolean priority, java.util.List<Upload> queued) throws Exception {

        byte[] parent_key = ma.genFolderKey();
        byte[] share_key = ma.genShareKey();

        String root_name = (folder_name != null && !folder_name.isEmpty())
                ? folder_name
                : files.get(0).getName() + "_" + MiscTools.genID(10);

        java.util.HashMap<String, Object> res = ma.createDir(root_name, ma.getRoot_id(), parent_key, MiscTools.i32a2bin(ma.getMaster_key()));

        if (res == null) {
            int code = ma.getLastApiErrorCode();
            throw new java.io.IOException("Could not create the upload folder on MEGA" + (code != 0 ? " (" + MegaErrorMessages.getTitle(code, MegaErrorMessages.Source.ACCOUNT) + ")" : ""));
        }

        String parent_node = (String) ((java.util.Map) ((java.util.List) res.get("f")).get(0)).get("h");

        String folder_link = null;

        if (public_link) {
            ma.shareFolder(parent_node, parent_key, share_key);
            folder_link = ma.getPublicFolderLink(parent_node, share_key);
        }

        for (java.io.File f : files) {
            Upload upload = new Upload(getMain_panel(), ma, f.getAbsolutePath(), parent_node, null, null, parent_node, share_key, folder_link, priority);
            queued.add(upload);
            getTransference_provision_queue().add(upload);
        }

        secureNotify();

        return folder_link;
    }
}
