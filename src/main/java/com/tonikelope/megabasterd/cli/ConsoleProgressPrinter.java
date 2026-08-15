/*
 * mega-downloader
 * Originally MegaBasterd, © tonikelope since 2016. GPLv3.
 */
package com.tonikelope.megabasterd.cli;

import com.tonikelope.megabasterd.MiscTools;
import com.tonikelope.megabasterd.Transference;
import java.util.Collection;
import java.util.List;

/**
 * Polls a batch of {@link Transference} (Download/Upload) instances on the
 * calling thread and prints one throttled progress line per second until
 * every one of them lands in the manager's finished queue. Deliberately
 * simple (no GUI progress bar / SpeedMeter wiring) -- CLI output is a status
 * line, not a widget.
 *
 * Completion is "present in {@code finished_queue}", NOT
 * {@link Transference#isStopped()}: a Download/Upload that fails during
 * *provisioning* (bad/expired link, MEGA API error before the transfer even
 * starts) is routed straight to the finished queue and never runs -- so
 * isStopped()/isExit() would stay false forever and this would hang. See
 * DownloadManager._provision().
 */
public class ConsoleProgressPrinter {

    private static final long POLL_INTERVAL_MS = 1000L;

    private ConsoleProgressPrinter() {
    }

    /**
     * Blocks until every transference in {@code batch} appears in
     * {@code finished_queue}, printing progress every second.
     */
    public static void waitAndPrint(List<? extends Transference> batch, Collection<? extends Transference> finished_queue) {

        if (batch.isEmpty()) {
            return;
        }

        long last_total_progress = -1L;

        while (true) {

            boolean all_finished = true;
            long total_progress = 0L;
            StringBuilder line = new StringBuilder();

            for (Transference t : batch) {

                if (!finished_queue.contains(t)) {
                    all_finished = false;
                }

                total_progress += Math.max(0L, t.getProgress());

                line.append(t.getFile_name() != null ? t.getFile_name() : "?")
                        .append(": ")
                        .append(MiscTools.formatBytes(t.getProgress()))
                        .append(" / ")
                        .append(MiscTools.formatBytes(t.getFile_size()))
                        .append("  ");
            }

            if (total_progress != last_total_progress) {
                System.out.print("\r" + line + "                    ");
                System.out.flush();
                last_total_progress = total_progress;
            }

            if (all_finished) {
                System.out.println();
                break;
            }

            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
