/*
 * mega-downloader
 * Originally MegaBasterd, © tonikelope since 2016. GPLv3.
 */
package com.tonikelope.megabasterd;

/**
 * Every blocking question or error that core, toolkit-free logic (MegaAPI,
 * MegaCrypterAPI, MiscTools, CryptTools, MainPanel) has for the user
 * mid-operation. The front-end installs its implementation with
 * {@link MainPanel#setUserPrompter}: console for the CLI (the default),
 * dialogs for the GUI.
 */
public interface UserPrompter {

    boolean confirmYesNo(String message);

    char[] promptPassword(String message);

    String prompt2FACode(String email);

    String promptText(String message);

    void showError(String title, String message);
}
