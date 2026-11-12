/*
 * mega-downloader
 * Originally MegaBasterd, © tonikelope since 2016. GPLv3.
 */
package com.tonikelope.megabasterd.gui;

import com.tonikelope.megabasterd.UserPrompter;
import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import javax.swing.JOptionPane;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;

/**
 * Dialog-based {@link UserPrompter}: the engine asks its questions (master
 * password, 2FA code, confirmations) from worker threads; each one is shown
 * on the EDT and the worker waits for the answer.
 */
final class SwingPrompter implements UserPrompter {

    private static Component parent() {
        return KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
    }

    private static <T> T ask(Callable<T> question) {
        try {
            if (SwingUtilities.isEventDispatchThread()) {
                return question.call();
            }
            FutureTask<T> task = new FutureTask<>(question);
            SwingUtilities.invokeLater(task);
            return task.get();
        } catch (Exception ex) {
            return null;
        }
    }

    @Override
    public boolean confirmYesNo(String message) {
        Boolean yes = ask(() -> JOptionPane.showConfirmDialog(parent(), message, "mega-downloader", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE) == JOptionPane.YES_OPTION);
        return yes != null && yes;
    }

    @Override
    public char[] promptPassword(String message) {
        return ask(() -> {
            JPasswordField field = new JPasswordField(24);
            int r = JOptionPane.showConfirmDialog(parent(), new Object[]{message, field}, "mega-downloader", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            return r == JOptionPane.OK_OPTION ? field.getPassword() : null;
        });
    }

    @Override
    public String prompt2FACode(String email) {
        return promptText("Enter the 2FA code for " + email + ":");
    }

    @Override
    public String promptText(String message) {
        return ask(() -> JOptionPane.showInputDialog(parent(), message, "mega-downloader", JOptionPane.QUESTION_MESSAGE));
    }

    @Override
    public void showError(String title, String message) {
        // Not waited for: an error popup must never stall an engine thread.
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(parent(), message, title, JOptionPane.ERROR_MESSAGE));
    }
}
