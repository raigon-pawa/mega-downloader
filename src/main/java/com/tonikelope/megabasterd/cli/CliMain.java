/*
 * mega-downloader
 * Originally MegaBasterd, © tonikelope since 2016. GPLv3.
 */
package com.tonikelope.megabasterd.cli;

import com.tonikelope.megabasterd.MainPanel;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * CLI entry point -- the headless counterpart to MainPanel.main(). Follows
 * LoginDiagnostic's precedent of a standalone main() with no Swing
 * dependency of its own; each subcommand is responsible for constructing
 * MainPanel via {@link CliBootstrap#start()} (which still builds the full
 * existing object graph, including MainPanelView, just never shows it).
 */
@Command(name = "mega-downloader", mixinStandardHelpOptions = true, version = "mega-downloader 8.58",
        description = "Unofficial MEGA downloader/uploader/streamer -- CLI, ExpressVPN-backed quota bypass.",
        subcommands = {
            DownloadCommand.class,
            UploadCommand.class,
            StreamCommand.class,
            FolderListCommand.class,
            AccountsCommand.class,
            SettingsCommand.class,
            VpnCommand.class
        })
public class CliMain implements Runnable {

    public static void main(String[] args) {
        // NOT java.awt.headless=true: MainPanelView and the *Dialog classes
        // are kept as never-shown objects rather than deleted (see the plan
        // doc), and a real JFrame/JDialog cannot be constructed at all under
        // true AWT headless mode (confirmed by testing -- Window's
        // constructor throws HeadlessException immediately). The deployment
        // (Docker) provides a virtual framebuffer so AWT sees a real
        // display and construction succeeds; nothing ever calls
        // setVisible(true) on it from the CLI path. Console-vs-dialog
        // routing uses the independent MainPanel.isCliMode() flag instead.
        MainPanel.setCliMode(true);

        int exit_code = new CommandLine(new CliMain()).execute(args);
        System.exit(exit_code);
    }

    @Override
    public void run() {
        // No subcommand given: behave like `--help`.
        new CommandLine(this).usage(System.out);
    }
}
