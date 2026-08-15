/*
 * mega-downloader
 * Originally MegaBasterd, © tonikelope since 2016. GPLv3.
 */
package com.tonikelope.megabasterd;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Console-driven {@link UserPrompter} used when the JVM is running headless
 * (CLI/Docker). Prefers {@link System#console()} for password entry (no
 * echo); falls back to a plain stdin read (echoed, with a warning) when no
 * real console is attached -- e.g. `docker run` without `-it`.
 */
public class ConsoleUserPrompter implements UserPrompter {

    private static final Logger LOG = Logger.getLogger(ConsoleUserPrompter.class.getName());

    @Override
    public boolean confirmYesNo(String message) {
        System.out.print(message + " [y/N] ");
        System.out.flush();
        String line = readLine();
        return line != null && line.trim().equalsIgnoreCase("y");
    }

    @Override
    public char[] promptPassword(String message) {
        Console console = System.console();
        if (console != null) {
            return console.readPassword("%s ", message);
        }
        LOG.log(Level.WARNING, "No interactive console attached; password input will be echoed to the terminal.");
        System.out.print(message + " ");
        System.out.flush();
        String line = readLine();
        return line == null ? null : line.toCharArray();
    }

    @Override
    public String prompt2FACode(String email) {
        System.out.print("Enter 2FA code for " + email + ": ");
        System.out.flush();
        return readLine();
    }

    @Override
    public String promptText(String message) {
        System.out.print(message + " ");
        System.out.flush();
        return readLine();
    }

    @Override
    public void showError(String title, String message) {
        LOG.log(Level.SEVERE, "{0}: {1}", new Object[]{title, message});
    }

    private String readLine() {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line = reader.readLine();
            return line == null ? null : line.trim();
        } catch (IOException ex) {
            LOG.log(Level.SEVERE, "Failed reading console input: {0}", ex.getMessage());
            return null;
        }
    }
}
