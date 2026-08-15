/*
 * mega-downloader
 * Originally MegaBasterd, © tonikelope since 2016. GPLv3.
 */
package com.tonikelope.megabasterd;

/**
 * Console stand-in for the handful of blocking Swing dialogs
 * (JOptionPane/JDialog) that core, non-GUI logic (MegaAPI, MegaCrypterAPI,
 * MiscTools, CryptTools) pops up mid-operation for user input or
 * confirmation. In GUI mode these call sites keep using their original
 * Swing dialogs unchanged (gated by MainPanel.isCliMode()); in CLI/Docker
 * mode they go through an implementation of this interface instead, since
 * a real dialog would block forever waiting for a click nobody watching
 * the (possibly virtual/headless-framebuffer) display can ever make.
 */
public interface UserPrompter {

    boolean confirmYesNo(String message);

    char[] promptPassword(String message);

    String prompt2FACode(String email);

    String promptText(String message);

    void showError(String title, String message);
}
