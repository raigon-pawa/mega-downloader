/*
 * mega-downloader
 * Originally MegaBasterd, © tonikelope since 2016. GPLv3.
 */
package com.tonikelope.megabasterd.cli;

import com.tonikelope.megabasterd.MainPanel;

/**
 * Shared startup for every CLI subcommand: run the DB-setup/migration
 * sequence, then construct MainPanel (DownloadManager, UploadManager, the
 * streaming server, resuming any in-flight transfers, etc.). The engine
 * constructs no windows, so no display is needed.
 */
final class CliBootstrap {

    private CliBootstrap() {
    }

    static MainPanel start() {
        MainPanel.bootstrapDbAndSettings();
        return new MainPanel();
    }
}
