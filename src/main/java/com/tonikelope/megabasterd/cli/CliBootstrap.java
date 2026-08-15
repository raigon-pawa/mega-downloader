/*
 * mega-downloader
 * Originally MegaBasterd, © tonikelope since 2016. GPLv3.
 */
package com.tonikelope.megabasterd.cli;

import com.tonikelope.megabasterd.MainPanel;

/**
 * Shared startup for every CLI subcommand: run the same DB-setup/migration
 * sequence and MainPanel construction the GUI's main() uses -- MainPanel's
 * constructor itself is unmodified (it still builds MainPanelView,
 * DownloadManager, UploadManager, the streaming server, resumes any
 * in-flight transfers, etc.); it just never gets shown. This deliberately
 * does NOT set java.awt.headless=true: a real JFrame/JDialog cannot be
 * constructed at all under true AWT headless mode (confirmed by testing),
 * so the deployment must provide AWT with a real display (a virtual
 * framebuffer in Docker) even though nothing is ever actually shown on it.
 */
final class CliBootstrap {

    private CliBootstrap() {
    }

    static MainPanel start() {
        MainPanel.bootstrapDbAndSettings();
        return new MainPanel();
    }
}
