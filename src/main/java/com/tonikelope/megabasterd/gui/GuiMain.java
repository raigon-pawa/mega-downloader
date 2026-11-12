/*
 * mega-downloader
 * Originally MegaBasterd, © tonikelope since 2016. GPLv3.
 */
package com.tonikelope.megabasterd.gui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.tonikelope.megabasterd.MainPanel;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.util.HashMap;
import java.util.Map;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Desktop GUI entry point (`mega-downloader gui`). A thin front-end over the
 * same engine the CLI drives: it only reads engine state by polling and
 * calls the engine's public methods.
 */
public final class GuiMain {

    static final String ACCENT = "#7fb2d0";

    private GuiMain() {
    }

    public static void main(String[] args) {
        launch();
    }

    public static void launch() {

        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("No display available: the GUI needs a desktop session. Use the CLI subcommands instead (--help).");
            System.exit(1);
        }

        Map<String, String> theme = new HashMap<>();
        theme.put("@background", "#17191c");
        theme.put("@accentColor", ACCENT);
        FlatLaf.setGlobalExtraDefaults(theme);
        FlatDarkLaf.setup();

        UIManager.put("Component.arc", 10);
        UIManager.put("Button.arc", 10);
        UIManager.put("TextComponent.arc", 10);
        UIManager.put("ProgressBar.arc", 999);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.width", 12);
        UIManager.put("ScrollBar.showButtons", false);
        UIManager.put("Table.showHorizontalLines", true);
        UIManager.put("Table.showVerticalLines", false);
        UIManager.put("Table.intercellSpacing", new Dimension(0, 1));
        UIManager.put("Table.cellMargins", new Insets(0, 12, 0, 12));
        UIManager.put("TableHeader.cellMargins", new Insets(0, 12, 0, 12));
        UIManager.put("TableHeader.height", 32);
        UIManager.put("TabbedPane.showTabSeparators", false);
        UIManager.put("TabbedPane.tabHeight", 38);

        // Before the engine starts: resuming saved transfers may already
        // need the master password or a 2FA code.
        MainPanel.setUserPrompter(new SwingPrompter());

        MainPanel.bootstrapDbAndSettings();

        // Off the EDT: the constructor does DB and network work.
        final MainPanel main_panel = new MainPanel();

        SwingUtilities.invokeLater(() -> new MainWindow(main_panel).setVisible(true));
    }
}
