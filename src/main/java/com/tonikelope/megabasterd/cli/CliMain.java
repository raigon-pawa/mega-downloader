/*
 * mega-downloader
 * Originally MegaBasterd, © tonikelope since 2016. GPLv3.
 */
package com.tonikelope.megabasterd.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * CLI entry point. Each subcommand is responsible for constructing
 * MainPanel via {@link CliBootstrap#start()}.
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
            VpnCommand.class,
            GuiCommand.class
        })
public class CliMain implements Runnable {

    public static void main(String[] args) {
        int exit_code = new CommandLine(new CliMain()).execute(args);
        System.exit(exit_code);
    }

    @Override
    public void run() {
        // No subcommand given: behave like `--help`.
        new CommandLine(this).usage(System.out);
    }
}
