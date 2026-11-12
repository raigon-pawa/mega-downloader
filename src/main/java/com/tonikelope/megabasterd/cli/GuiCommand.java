/*
 * mega-downloader
 * Originally MegaBasterd, © tonikelope since 2016. GPLv3.
 */
package com.tonikelope.megabasterd.cli;

import com.tonikelope.megabasterd.gui.GuiMain;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;

@Command(name = "gui", mixinStandardHelpOptions = true, description = "Open the desktop window (needs a display).")
public class GuiCommand implements Callable<Integer> {

    @Override
    public Integer call() throws Exception {

        GuiMain.launch();

        // The window lives on AWT's threads and the engine exits the JVM
        // itself; returning would make CliMain call System.exit().
        Thread.currentThread().join();

        return 0;
    }
}
