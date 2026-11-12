/*
 * mega-downloader
 * Originally MegaBasterd, © tonikelope since 2016. GPLv3.
 */
package com.tonikelope.megabasterd;

import static com.tonikelope.megabasterd.DBTools.*;
import static com.tonikelope.megabasterd.MainPanel.*;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import static java.util.logging.Level.SEVERE;
import java.util.logging.Logger;

/**
 *
 * @author tonikelope
 */
public class DownloadManager extends TransferenceManager {

    private static final Logger LOG = Logger.getLogger(DownloadManager.class.getName());

    public DownloadManager(MainPanel main_panel) {

        super(main_panel, main_panel.getMax_dl());
    }

    /**
     * #774 -- when the download queue transitions from non-empty to empty
     * with at least one OK finish, dispatch the user's configured
     * post-download-finish command. Fires independently of the upload-side
     * variant and of the legacy 509 command; if all three converge they
     * run concurrently. {@link MainPanel#run_dl_finish_command} no-ops when
     * the feature is disabled, so no per-call gate is needed here.
     */
    @Override
    protected void onAllTransferencesFinished() {
        MainPanel.run_dl_finish_command();
    }

    public void forceResetAllChunks() {
        // No synchronized: the actual work runs on THREAD_POOL, so the keyword
        // only serialized the submission of the runnable -- not the work
        // itself. Misleading. The per-worker RESET_CURRENT_CHUNK is the real
        // boundary and is independently safe.
        THREAD_POOL.execute(() -> {

            ConcurrentLinkedQueue<Transference> transference_running_list = getMain_panel().getDownload_manager().getTransference_running_list();

            if (!transference_running_list.isEmpty()) {
                transference_running_list.forEach((transference) -> {

                    ArrayList<ChunkDownloader> chunkworkers = ((Download) transference).getChunkworkers();

                    chunkworkers.forEach((worker) -> {
                        worker.RESET_CURRENT_CHUNK();
                    });

                });

            }

        });
    }

    @Override
    public void closeAllFinished() {

        _transference_finished_queue.stream().filter((t) -> (!t.isCanceled())).map((t) -> {
            _transference_finished_queue.remove(t);
            return t;
        }).forEachOrdered((t) -> {
            _transference_remove_queue.add(t);
        });

        secureNotify();
    }

    /** Every queued/running/finished download link, grouped by state. */
    public String getAllLinksText() {

        ArrayList<String> links = new ArrayList<>();

        String out = "***PROVISIONING DOWNLOADS***\r\n\r\n";

        for (Transference t : _transference_provision_queue) {

            links.add(((Download) t).getUrl());
        }

        out += String.join("\r\n", links);

        links.clear();

        out += "\r\n\r\n***WAITING DOWNLOADS***\r\n\r\n";

        for (Transference t : _transference_waitstart_aux_queue) {

            links.add(((Download) t).getUrl());
        }

        for (Transference t : _transference_waitstart_queue) {

            links.add(((Download) t).getUrl());
        }

        out += String.join("\r\n", links);

        links.clear();

        out += "\r\n\r\n***RUNNING DOWNLOADS***\r\n\r\n";

        for (Transference t : _transference_running_list) {

            links.add(((Download) t).getUrl());
        }

        out += String.join("\r\n", links);

        links.clear();

        out += "\r\n\r\n***FINISHED DOWNLOADS***\r\n\r\n";

        for (Transference t : _transference_finished_queue) {

            links.add(((Download) t).getUrl());
        }

        out += String.join("\r\n", links);

        return out;

    }

    @Override
    public void remove(Transference[] downloads) {

        ArrayList<String> delete_down = new ArrayList<>();

        for (final Transference d : downloads) {

            getTransference_waitstart_queue().remove(d);

            getTransference_running_list().remove(d);

            getTransference_finished_queue().remove(d);

            if (((Download) d).isProvision_ok()) {

                ((Download) d).finalizeTotals();
            }

            // Always remove the DB row when the user removes the transfer.
            // The previous "!d.isCanceled() || d.isClosed()" gate AND the
            // outer "if provision_ok" check let cancelled-but-not-closed and
            // failed-to-provision downloads survive in the downloads table,
            // so they kept being resurrected on every restart via
            // resumeDownloads -> selectDownloads. Closes #699.
            delete_down.add(((Download) d).getUrl());
        }

        try {
            deleteDownloads(delete_down.toArray(new String[delete_down.size()]));
        } catch (SQLException ex) {
            LOG.log(SEVERE, null, ex);
        }

        secureNotify();
    }

    @Override
    public void provision(final Transference download) {

        try {

            _provision((Download) download, false);

            secureNotify();

        } catch (APIException ex) {

            LOG.log(Level.INFO, "{0} Provision failed! Retrying in separated thread...", Thread.currentThread().getName());

            THREAD_POOL.execute(() -> {
                try {

                    _provision((Download) download, true);

                } catch (APIException ex1) {

                    LOG.log(SEVERE, null, ex1);
                }

                secureNotify();
            });
        }

    }

    private void _provision(Download download, boolean retry) throws APIException {

        download.provisionIt(retry);

        if (download.isProvision_ok()) {

            increment_total_size(download.getFile_size());

            getTransference_waitstart_aux_queue().add(download);

        } else {
            getTransference_finished_queue().add(download);
        }
    }

}
