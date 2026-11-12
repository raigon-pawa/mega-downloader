/*
 * mega-downloader
 * Originally MegaBasterd, © tonikelope since 2016. GPLv3.
 */
package com.tonikelope.megabasterd.gui;

import com.tonikelope.megabasterd.DebugLogBus;
import com.tonikelope.megabasterd.MainPanel;
import com.tonikelope.megabasterd.SpeedMeter;
import com.tonikelope.megabasterd.TransferenceManager;
import com.tonikelope.megabasterd.VpnRotationManager;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.net.URI;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.Timer;

/**
 * The application window: action bar, Downloads / Uploads / Log tabs and a
 * status bar. Everything shown is polled from the engine twice a second.
 */
final class MainWindow extends JFrame {

    private static final Logger LOG = Logger.getLogger(MainWindow.class.getName());

    private final MainPanel _mp;
    private final TransferPanel _downloads, _uploads;
    private final JTabbedPane _tabs = new JTabbedPane();
    private final JLabel _down = new JLabel(), _up = new JLabel(), _vpn = new JLabel(), _stream = new JLabel();

    MainWindow(MainPanel mp) {

        super("mega-downloader " + MainPanel.VERSION);

        _mp = mp;

        setIconImage(Toolkit.getDefaultToolkit().getImage(getClass().getResource(MainPanel.ICON_FILE)));

        _downloads = new TransferPanel(mp.getDownload_manager(), true, "No downloads yet. Press Ctrl+N to add MEGA links.");
        _uploads = new TransferPanel(mp.getUpload_manager(), false, "No uploads yet. Press Ctrl+U to choose files.");

        JTextArea log = new JTextArea();
        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        DebugLogBus.bind(log);
        JScrollPane log_scroll = new JScrollPane(log);
        log_scroll.setBorder(BorderFactory.createEmptyBorder());

        _tabs.addTab("Downloads", _downloads);
        _tabs.addTab("Uploads", _uploads);
        _tabs.addTab("Log", log_scroll);

        JButton add = button("Add links", "Ctrl+N", this::addLinks);
        add.putClientProperty("FlatLaf.style", "background: " + GuiMain.ACCENT + "; foreground: #101214; hoverBackground: #93c0da; pressedBackground: #6ea3c2; borderWidth: 0; focusWidth: 0");

        JPanel actions = new JPanel();
        actions.setLayout(new BoxLayout(actions, BoxLayout.X_AXIS));
        actions.setBorder(BorderFactory.createEmptyBorder(12, 12, 6, 12));
        actions.add(add);
        actions.add(Box.createHorizontalStrut(8));
        actions.add(button("Upload files", "Ctrl+U", this::upload));
        actions.add(Box.createHorizontalStrut(8));
        actions.add(button("Stream video", null, () -> Dialogs.stream(this, _mp)));
        actions.add(Box.createHorizontalGlue());
        actions.add(button("Accounts", null, () -> Dialogs.accounts(this, _mp)));
        actions.add(Box.createHorizontalStrut(8));
        actions.add(button("Settings", "Ctrl+,", () -> Dialogs.settings(this, _mp)));

        JPanel status = new JPanel();
        status.setLayout(new BoxLayout(status, BoxLayout.X_AXIS));
        status.setBorder(BorderFactory.createEmptyBorder(6, 12, 8, 12));
        status.add(_down);
        status.add(Box.createHorizontalStrut(24));
        status.add(_up);
        status.add(Box.createHorizontalGlue());
        status.add(_stream);
        status.add(Box.createHorizontalStrut(24));
        status.add(_vpn);

        _vpn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        _vpn.setToolTipText("Click for VPN actions");
        _vpn.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                vpnMenu().show(_vpn, 0, -50);
            }
        });

        add(actions, BorderLayout.NORTH);
        add(_tabs, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);

        shortcut(KeyEvent.VK_N, this::addLinks);
        shortcut(KeyEvent.VK_U, this::upload);
        shortcut(KeyEvent.VK_COMMA, () -> Dialogs.settings(this, _mp));

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                exit();
            }
        });

        refresh();
        new Timer(500, (e) -> refresh()).start();

        setSize(1180, 700);
        setLocationRelativeTo(null);
    }

    private void addLinks() {
        if (Dialogs.addLinks(this, _mp)) {
            _tabs.setSelectedIndex(0);
        }
    }

    private void upload() {
        if (Dialogs.upload(this, _mp)) {
            _tabs.setSelectedIndex(1);
        }
    }

    private void refresh() {

        _downloads.refresh();
        _uploads.refresh();

        _tabs.setTitleAt(0, _downloads.count() > 0 ? "Downloads (" + _downloads.count() + ")" : "Downloads");
        _tabs.setTitleAt(1, _uploads.count() > 0 ? "Uploads (" + _uploads.count() + ")" : "Uploads");

        _down.setText(speed("↓", _mp.getGlobal_dl_speed(), _mp.isLimit_download_speed() ? _mp.getMax_dl_speed() : 0));
        _up.setText(speed("↑", _mp.getGlobal_up_speed(), _mp.isLimit_upload_speed() ? _mp.getMax_up_speed() : 0));

        String stream = _mp.getStreamserver() != null ? _mp.getStreamserver().getStatus() : null;
        _stream.setText(stream != null ? stream : "");

        VpnRotationManager vpn = MainPanel.getProxy_manager();

        if (!MainPanel.isUse_smart_proxy()) {
            _vpn.setText("VPN off");
        } else if (vpn == null) {
            _vpn.setText("VPN starting…");
        } else if (vpn.isConnected()) {
            _vpn.setText("VPN " + vpn.getCurrentLocation());
        } else {
            _vpn.setText(vpn.isForceVpn() ? "VPN connecting…" : "VPN standby (connects on quota)");
        }
    }

    private static String speed(String arrow, SpeedMeter meter, int limit_kbs) {
        String text = meter.getSpeedText().isEmpty() ? arrow + "  idle" : arrow + "  " + meter.getSpeedText() + "    " + meter.getRemainingText();
        return limit_kbs > 0 ? text + "    (limit " + limit_kbs + " KB/s)" : text;
    }

    private JPopupMenu vpnMenu() {
        JPopupMenu menu = new JPopupMenu();
        menu.add(action("Connect VPN now", () -> MainPanel.THREAD_POOL.execute(() -> {
            VpnRotationManager vpn = MainPanel.getProxy_manager();
            if (vpn == null) {
                vpn = new VpnRotationManager(_mp);
                MainPanel.setProxy_manager(vpn);
            }
            if (vpn.ensureProtectiveRouting() == null) {
                Dialogs.error(this, "Could not connect. Check that expressvpnctl is installed, activated and on PATH.");
            }
        })));
        menu.add(action("Disconnect VPN", () -> MainPanel.THREAD_POOL.execute(() -> {
            VpnRotationManager vpn = MainPanel.getProxy_manager();
            if (vpn != null) {
                vpn.disconnect();
            }
        })));
        return menu;
    }

    private static boolean busy(TransferenceManager manager) {
        return !manager.getTransference_running_list().isEmpty() || !manager.getTransference_provision_queue().isEmpty() || !manager.getTransference_preprocess_queue().isEmpty() || !manager.getTransference_preprocess_global_queue().isEmpty();
    }

    private void exit() {

        boolean streaming = _mp.getStreamserver() != null && !_mp.getStreamserver().getWorking_threads().isEmpty();

        if ((streaming || busy(_mp.getDownload_manager()) || busy(_mp.getUpload_manager()))
                && JOptionPane.showConfirmDialog(this, "Transfers are still active.\n\nRunning ones are stopped safely and continue next time; links still being prepared are lost.\n\nExit anyway?", "Exit", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            return;
        }

        setTitle("mega-downloader: stopping transfers safely…");
        setEnabled(false);

        // The engine drains its workers (30 s at most) and exits the JVM.
        MainPanel.THREAD_POOL.execute(() -> _mp.byebye(false));
    }

    private static AbstractAction action(String name, final Runnable run) {
        return new AbstractAction(name) {
            @Override
            public void actionPerformed(ActionEvent e) {
                run.run();
            }
        };
    }

    private static JButton button(String text, String shortcut, Runnable run) {
        JButton button = new JButton(action(text, run));
        if (shortcut != null) {
            button.setToolTipText(shortcut);
        }
        return button;
    }

    private void shortcut(int key_code, Runnable run) {
        String name = "shortcut-" + key_code;
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key_code, InputEvent.CTRL_DOWN_MASK), name);
        getRootPane().getActionMap().put(name, action(name, run));
    }

    static void copy(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    /** Opens a folder in the file manager. */
    static void open(File folder) {
        launch(folder.getAbsolutePath(), () -> Desktop.getDesktop().open(folder));
    }

    /** Opens a URL in the default browser / player. */
    static void browse(String url) {
        launch(url, () -> Desktop.getDesktop().browse(URI.create(url)));
    }

    private interface DesktopCall {

        void run() throws Exception;
    }

    // java.awt.Desktop is unsupported on some Linux desktops; xdg-open is the fallback.
    private static void launch(String target, DesktopCall call) {
        try {
            if (Desktop.isDesktopSupported()) {
                call.run();
            } else {
                new ProcessBuilder("xdg-open", target).start();
            }
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Could not open {0}: {1}", new Object[]{target, ex.getMessage()});
        }
    }
}
