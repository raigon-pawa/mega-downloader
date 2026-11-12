/*
 * mega-downloader
 * Originally MegaBasterd, © tonikelope since 2016. GPLv3.
 */
package com.tonikelope.megabasterd.gui;

import com.tonikelope.megabasterd.AccountStore;
import com.tonikelope.megabasterd.CryptTools;
import com.tonikelope.megabasterd.DBTools;
import com.tonikelope.megabasterd.Download;
import com.tonikelope.megabasterd.DownloadManager;
import com.tonikelope.megabasterd.KissVideoStreamServer;
import com.tonikelope.megabasterd.MainPanel;
import com.tonikelope.megabasterd.MegaAPI;
import com.tonikelope.megabasterd.MiscTools;
import com.tonikelope.megabasterd.Transference;
import com.tonikelope.megabasterd.TransferenceManager;
import com.tonikelope.megabasterd.Upload;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.io.File;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;

/**
 * The window's dialogs. Each one only collects input on the EDT and then
 * hands the work to the engine on {@link MainPanel#THREAD_POOL}.
 */
final class Dialogs {

    private static final Logger LOG = Logger.getLogger(Dialogs.class.getName());
    private static final String ANONYMOUS = "Anonymous (no account)";
    private static final String LINK_REGEX = "(?:https?|mega)://[^\r\n]+(#[^\r\n!]*?)?![^\r\n!]+![^\\?\r\n/]+";

    private Dialogs() {
    }

    // ---------------------------------------------------------------- helpers
    /** Two-column form: label, component, label, component... (null label = full-width row). */
    private static JPanel form(Object... rows) {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 4, 5, 4);
        c.anchor = GridBagConstraints.WEST;
        for (int i = 0; i < rows.length; i += 2) {
            c.gridy = i / 2;
            c.gridx = 0;
            c.weightx = 0;
            c.fill = GridBagConstraints.NONE;
            if (rows[i] != null) {
                panel.add(new JLabel((String) rows[i]), c);
            }
            c.gridx = 1;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            panel.add((Component) rows[i + 1], c);
        }
        return panel;
    }

    private static boolean ask(Component parent, String title, JComponent content, String ok) {
        Object[] options = {ok, "Cancel"};
        return JOptionPane.showOptionDialog(parent, content, title, JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0]) == 0;
    }

    static void error(final Component parent, final String message) {
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(parent, message, "mega-downloader", JOptionPane.ERROR_MESSAGE));
    }

    private static JComboBox<String> accountBox(MainPanel mp, boolean anonymous, String selected) {
        JComboBox<String> box = new JComboBox<>();
        if (anonymous) {
            box.addItem(ANONYMOUS);
        }
        for (String email : new TreeSet<>(mp.getMega_accounts().keySet())) {
            box.addItem(email);
        }
        if (selected != null) {
            box.setSelectedItem(selected);
        }
        return box;
    }

    /** The chosen account's email, or null for anonymous. */
    private static String account(JComboBox<String> box) {
        Object selected = box.getSelectedItem();
        return selected == null || ANONYMOUS.equals(selected) ? null : (String) selected;
    }

    private static JPanel folderField(final Component parent, final JTextField field) {
        JButton browse = new JButton("Browse…");
        browse.addActionListener((e) -> {
            JFileChooser chooser = new JFileChooser(field.getText());
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
                field.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });
        JPanel panel = new JPanel(new BorderLayout(6, 0));
        panel.add(field, BorderLayout.CENTER);
        panel.add(browse, BorderLayout.EAST);
        return panel;
    }

    /**
     * The configured download folder. The engine's unset default "." (the
     * launch directory) becomes ~/Downloads here.
     */
    private static String downloadDir(MainPanel mp) {
        String path = mp.getDefault_download_path();
        if (path == null || ".".equals(path)) {
            File downloads = new File(System.getProperty("user.home"), "Downloads");
            return (downloads.isDirectory() ? downloads : downloads.getParentFile()).getAbsolutePath();
        }
        return new File(path).getAbsolutePath();
    }

    private static String clipboardMegaText() {
        try {
            String text = (String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
            return text != null && text.contains("mega") ? text : "";
        } catch (Exception ex) {
            return "";
        }
    }

    private static MegaAPI login(Component parent, MainPanel mp, String email) {
        try {
            MegaAPI ma = MiscTools.checkMegaAccountLoginAndShowMasterPassDialog(mp, email);
            if (ma == null) {
                error(parent, "Could not log in to " + email + " (wrong master password or cancelled).");
            }
            return ma;
        } catch (Exception ex) {
            error(parent, "Could not log in to " + email + ": " + ex.getMessage());
            return null;
        }
    }

    private static void showLink(Component parent, String title, String link) {
        JTextField field = new JTextField(link, 48);
        field.setEditable(false);
        Object[] options = {"Copy", "Open", "Close"};
        int choice = JOptionPane.showOptionDialog(parent, field, title, JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
        if (choice == 0) {
            MainWindow.copy(link);
        } else if (choice == 1) {
            MainWindow.browse(link);
        }
    }

    // ---------------------------------------------------------- add downloads
    static boolean addLinks(Component parent, MainPanel mp) {

        JTextArea links = new JTextArea(clipboardMegaText(), 10, 60);
        links.setLineWrap(true);
        JTextField dir = new JTextField(downloadDir(mp));
        JComboBox<String> account = accountBox(mp, true, mp.isUse_mega_account_down() ? mp.getMega_account_down() : null);
        JCheckBox priority = new JCheckBox("Start before everything already queued");

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(new JLabel("Paste MEGA file or folder links, one per line:"), BorderLayout.NORTH);
        panel.add(new JScrollPane(links), BorderLayout.CENTER);
        panel.add(form("Save to", folderField(parent, dir), "Account", account, null, priority), BorderLayout.SOUTH);

        if (!ask(parent, "Add downloads", panel, "Download") || links.getText().trim().isEmpty()) {
            return false;
        }

        startDownloads(parent, mp, links.getText(), dir.getText().trim(), account(account), priority.isSelected());

        return true;
    }

    /**
     * Turns pasted text into queued downloads (plain, MegaCrypter, ELC/DLC
     * and folder links), on the download manager's preprocess thread.
     */
    private static void startDownloads(final Component parent, final MainPanel mp, final String text, final String dl_path, final String account, final boolean priority) {

        final DownloadManager dm = mp.getDownload_manager();

        mp.resumeDownloads();

        dm.getTransference_preprocess_queue().add(() -> {

            MegaAPI ma = account == null ? new MegaAPI() : login(parent, mp, account);

            if (ma == null) {
                return;
            }

            String link_data = MiscTools.extractMegaLinksFromString(text);

            Set<String> urls = new HashSet<>(MiscTools.findAllRegex(LINK_REGEX, link_data, 0));

            for (String link : MiscTools.findAllRegex("mega://enc[^\r\n]+", link_data, 0)) {
                try {
                    urls.add(CryptTools.decryptMegaDownloaderLink(link));
                } catch (Exception ex) {
                    LOG.log(Level.SEVERE, null, ex);
                }
            }

            for (String link : MiscTools.findAllRegex("mega://elc[^\r\n]+", link_data, 0)) {
                urls.addAll(CryptTools.decryptELC(link, mp));
            }

            for (String dlc : MiscTools.findAllRegex("dlc://([^\r\n]+)", link_data, 1)) {
                for (String link : CryptTools.decryptDLC(dlc, mp)) {
                    if (MiscTools.findFirstRegex(LINK_REGEX, link, 0) != null) {
                        urls.add(link);
                    }
                }
            }

            if (urls.isEmpty()) {
                error(parent, "No MEGA links found in that text.");
                return;
            }

            // Links to a single file inside a folder ("#F*") become normal file links first.
            Set<String> folder_file_links = new HashSet<>(MiscTools.findAllRegex("(?:https?|mega)://[^\r\n]+#F\\*[^\r\n!]*?![^\r\n!]+![^\\?\r\n/]+", link_data, 0));

            if (!folder_file_links.isEmpty()) {
                dm.getTransference_preprocess_global_queue().addAll(folder_file_links);
                dm.secureNotify();
                urls.removeAll(folder_file_links);
                urls.addAll(ma.GENERATE_N_LINKS(folder_file_links));
                dm.getTransference_preprocess_global_queue().removeAll(folder_file_links);
            }

            // The global preprocess queue is the "still working" marker;
            // "cancel everything" empties it, which stops this loop.
            dm.getTransference_preprocess_global_queue().addAll(urls);
            dm.secureNotify();

            String chunks_dir = mp.isUse_custom_chunks_dir() ? mp.getCustom_chunks_dir() : null;

            for (String original : urls) {

                if (mp.isExit()) {
                    break;
                }

                try {

                    String url = URLDecoder.decode(original, "UTF-8").replaceAll("^mega://", "https://mega.nz").trim();

                    if (MiscTools.findFirstRegex("#F!", url, 0) != null) {

                        List<HashMap<String, Object>> files = pickFolderFiles(parent, mp, url);

                        if (files != null) {
                            for (HashMap<String, Object> file : files) {
                                waitForRoom(dm);
                                if (dm.getTransference_preprocess_global_queue().isEmpty()) {
                                    break;
                                }
                                dm.getTransference_provision_queue().add(new Download(mp, ma, (String) file.get("url"), dl_path, (String) file.get("filename"), (String) file.get("filekey"), (Long) file.get("filesize"), null, null, mp.isUse_slots_down(), false, chunks_dir, priority));
                                dm.secureNotify();
                            }
                        }

                    } else {
                        waitForRoom(dm);
                        dm.getTransference_provision_queue().add(new Download(mp, ma, url, dl_path, null, null, null, null, null, mp.isUse_slots_down(), false, chunks_dir, priority));
                    }

                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception ex) {
                    LOG.log(Level.SEVERE, "Skipping link after unexpected error: " + original, ex);
                }

                dm.getTransference_preprocess_global_queue().remove(original);
                dm.secureNotify();
            }
        });

        dm.secureNotify();
    }

    /** Blocks while the manager's wait queues are full (huge folders). */
    private static void waitForRoom(TransferenceManager manager) throws InterruptedException {
        while (manager.getTransference_waitstart_queue().size() >= TransferenceManager.MAX_WAIT_QUEUE || manager.getTransference_waitstart_aux_queue().size() >= TransferenceManager.MAX_WAIT_QUEUE) {
            synchronized (manager.getWait_queue_lock()) {
                manager.getWait_queue_lock().wait(1000);
            }
        }
    }

    // ----------------------------------------------------------- folder links
    /**
     * Lists a MEGA folder link and lets the user tick the files to download.
     * Call off the EDT (it does network I/O, then shows a dialog).
     *
     * @return one {url, filename, filekey, filesize} map per chosen file, or
     * null when the folder cannot be read or the user cancels
     */
    @SuppressWarnings("unchecked")
    private static List<HashMap<String, Object>> pickFolderFiles(final Component parent, MainPanel mp, String url) {

        final List<HashMap<String, Object>> files = new ArrayList<>();

        try {

            String folder_id = MiscTools.findFirstRegex("#F!([^!]+)", url, 1);
            String folder_key = MiscTools.findFirstRegex("#F![^!]+!(.+)", url, 1);
            String subfolder_id = null;

            if (folder_id.contains("@")) {
                String[] ids = folder_id.split("@");
                folder_id = ids[0];
                subfolder_id = ids[1];
            }

            HashMap<String, Object> nodes = new MegaAPI().getFolderNodes(folder_id, folder_key, false);

            for (Object o : nodes.values()) {

                HashMap<String, Object> node = (HashMap<String, Object>) o;

                if (node.get("size") == null) {
                    continue; // a folder
                }

                String path = (String) node.get("name");
                boolean inside = subfolder_id == null;
                HashMap<String, Object> dir = (HashMap<String, Object>) nodes.get((String) node.get("parent"));

                while (dir != null) {
                    path = dir.get("name") + File.separator + path;
                    if (subfolder_id != null && subfolder_id.equals(dir.get("h"))) {
                        inside = true;
                        break;
                    }
                    dir = (HashMap<String, Object>) nodes.get((String) dir.get("parent"));
                }

                if (inside) {
                    HashMap<String, Object> file = new HashMap<>();
                    file.put("url", "https://mega.nz/#N!" + node.get("h") + "!" + node.get("key") + "###n=" + folder_id);
                    file.put("filename", MiscTools.cleanFilePath(path));
                    file.put("filekey", node.get("key"));
                    file.put("filesize", node.get("size"));
                    files.add(file);
                }
            }

        } catch (Exception ex) {
            LOG.log(Level.SEVERE, null, ex);
            error(parent, "Could not read the folder link: " + ex.getMessage());
            return null;
        }

        if (files.isEmpty()) {
            error(parent, "That folder link has no files.");
            return null;
        }

        files.sort((a, b) -> MiscTools.naturalCompare((String) a.get("filename"), (String) b.get("filename"), true));

        if (mp.isAuto_download_folders()) {
            return files;
        }

        final boolean[] checked = new boolean[files.size()];
        Arrays.fill(checked, true);
        final boolean[] ok = {false};

        try {
            SwingUtilities.invokeAndWait(() -> ok[0] = showFolderPicker(parent, files, checked));
        } catch (Exception ex) {
            return null;
        }

        if (!ok[0]) {
            return null;
        }

        List<HashMap<String, Object>> chosen = new ArrayList<>();
        for (int i = 0; i < checked.length; i++) {
            if (checked[i]) {
                chosen.add(files.get(i));
            }
        }
        return chosen;
    }

    private static boolean showFolderPicker(Component parent, final List<HashMap<String, Object>> files, final boolean[] checked) {

        final JLabel total = new JLabel();

        final AbstractTableModel model = new AbstractTableModel() {
            @Override
            public int getRowCount() {
                return files.size();
            }

            @Override
            public int getColumnCount() {
                return 3;
            }

            @Override
            public String getColumnName(int column) {
                return new String[]{"", "File", "Size"}[column];
            }

            @Override
            public Class<?> getColumnClass(int column) {
                return new Class<?>[]{Boolean.class, String.class, Long.class}[column];
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 0;
            }

            @Override
            public Object getValueAt(int row, int column) {
                return column == 0 ? (Object) checked[row] : files.get(row).get(column == 1 ? "filename" : "filesize");
            }

            @Override
            public void setValueAt(Object value, int row, int column) {
                checked[row] = (Boolean) value;
                fireTableCellUpdated(row, column);
            }
        };

        final Runnable update_total = () -> {
            int count = 0;
            long bytes = 0;
            for (int i = 0; i < checked.length; i++) {
                if (checked[i]) {
                    count++;
                    bytes += (Long) files.get(i).get("filesize");
                }
            }
            total.setText(count + " of " + checked.length + " files  ·  " + MiscTools.formatBytes(bytes));
        };
        model.addTableModelListener((e) -> update_total.run());
        update_total.run();

        final JTable table = new JTable(model);
        table.setRowHeight(28);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.getColumnModel().getColumn(0).setMaxWidth(40);
        table.getColumnModel().getColumn(2).setMaxWidth(110);
        table.getColumnModel().getColumn(2).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            protected void setValue(Object value) {
                setText(MiscTools.formatBytes((Long) value));
            }
        });

        final TableRowSorter<AbstractTableModel> sorter = new TableRowSorter<>(model);
        table.setRowSorter(sorter);

        final JTextField filter = new JTextField();
        filter.putClientProperty("JTextField.placeholderText", "Filter by name…");
        filter.getDocument().addDocumentListener(new DocumentListener() {
            private void apply() {
                String text = filter.getText().trim();
                sorter.setRowFilter(text.isEmpty() ? null : RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote(text), 1));
            }

            @Override
            public void insertUpdate(DocumentEvent e) {
                apply();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                apply();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                apply();
            }
        });

        // Tick/untick applies to the rows the filter currently shows.
        JButton all = new JButton("Tick shown");
        JButton none = new JButton("Untick shown");
        for (final JButton button : new JButton[]{all, none}) {
            button.addActionListener((e) -> {
                for (int view_row = 0; view_row < table.getRowCount(); view_row++) {
                    checked[table.convertRowIndexToModel(view_row)] = button == all;
                }
                model.fireTableRowsUpdated(0, files.size() - 1);
            });
        }

        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.add(filter, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(all);
        buttons.add(none);
        top.add(buttons, BorderLayout.EAST);

        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(760, 420));

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(top, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(total, BorderLayout.SOUTH);

        return ask(parent, "Choose files to download", panel, "Download ticked");
    }

    // ---------------------------------------------------------------- uploads
    static boolean upload(final Component parent, final MainPanel mp) {

        if (mp.getMega_accounts().isEmpty()) {
            JOptionPane.showMessageDialog(parent, "Uploading needs a MEGA account. Add one first.", "Upload", JOptionPane.INFORMATION_MESSAGE);
            accounts(parent, mp);
            if (mp.getMega_accounts().isEmpty()) {
                return false;
            }
        }

        JFileChooser chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(true);
        chooser.setDialogTitle("Choose files to upload");

        if (chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION || chooser.getSelectedFiles().length == 0) {
            return false;
        }

        final List<File> files = Arrays.asList(chooser.getSelectedFiles());

        final JComboBox<String> account = accountBox(mp, false, null);
        final JTextField folder = new JTextField(28);
        folder.putClientProperty("JTextField.placeholderText", "automatic (first file's name)");
        final JCheckBox public_link = new JCheckBox("Create a public link for the folder");
        final JCheckBox priority = new JCheckBox("Start before everything already queued");

        JPanel panel = form(null, new JLabel(files.size() + (files.size() == 1 ? " file" : " files") + " into a new MEGA folder"), "Account", account, "Folder name", folder, null, public_link, null, priority);

        if (!ask(parent, "Upload to MEGA", panel, "Upload")) {
            return false;
        }

        final String email = account(account), folder_name = folder.getText().trim();
        final boolean make_link = public_link.isSelected(), first = priority.isSelected();

        mp.resumeUploads();

        MainPanel.THREAD_POOL.execute(() -> {

            MegaAPI ma = login(parent, mp, email);

            if (ma == null) {
                return;
            }

            try {
                final String link = mp.getUpload_manager().queueFiles(ma, files, folder_name, make_link, first, new ArrayList<Upload>());
                if (link != null) {
                    SwingUtilities.invokeLater(() -> showLink(parent, "Public folder link", link));
                }
            } catch (Exception ex) {
                LOG.log(Level.SEVERE, null, ex);
                error(parent, "Upload failed: " + ex.getMessage());
            }
        });

        return true;
    }

    // -------------------------------------------------------------- streaming
    static void stream(final Component parent, final MainPanel mp) {

        JTextField link = new JTextField(MiscTools.extractFirstMegaLinkFromString(clipboardMegaText()), 48);
        JComboBox<String> account = accountBox(mp, true, null);

        if (!ask(parent, "Stream a MEGA video", form("Video link", link, "Account", account), "Get stream URL") || link.getText().trim().isEmpty()) {
            return;
        }

        final String video = MiscTools.extractFirstMegaLinkFromString(link.getText().trim()), email = account(account);

        if (video.isEmpty()) {
            error(parent, "That is not a MEGA link.");
            return;
        }

        MainPanel.THREAD_POOL.execute(() -> {
            final String url = KissVideoStreamServer.streamUrl(email, video);
            if (url == null) {
                error(parent, "Could not resolve that folder link.");
            } else {
                SwingUtilities.invokeLater(() -> showLink(parent, "Stream URL (open it in a media player)", url));
            }
        });
    }

    // --------------------------------------------------------------- accounts
    static void accounts(final Component parent, final MainPanel mp) {

        final DefaultListModel<String> model = new DefaultListModel<>();
        final Runnable reload = () -> {
            model.clear();
            for (String email : new TreeSet<>(mp.getMega_accounts().keySet())) {
                model.addElement(email);
            }
        };
        reload.run();

        final JList<String> list = new JList<>(model);
        final JLabel status = new JLabel(" ");
        final JButton add = new JButton("Add account…"), remove = new JButton("Remove");

        add.addActionListener((e) -> {

            JTextField email = new JTextField(26);
            JPasswordField password = new JPasswordField(26);

            if (!ask(list, "Add MEGA account", form("Email", email, "Password", password), "Log in") || email.getText().trim().isEmpty()) {
                return;
            }

            final String mail = email.getText().trim(), pass = new String(password.getPassword());

            add.setEnabled(false);
            status.setText("Logging in to " + mail + "…");

            MainPanel.THREAD_POOL.execute(() -> {

                String result = "Added " + mail;

                try {
                    MegaAPI ma = new MegaAPI();
                    String pincode = null;
                    if (ma.check2FA(mail)) {
                        pincode = mp.getUserPrompter().prompt2FACode(mail);
                        if (pincode == null || pincode.isEmpty()) {
                            throw new Exception("a 2FA code is required");
                        }
                    }
                    ma.login(mail, pass, pincode);
                    new AccountStore(mp).persistMegaLogin(mail, pass, ma);
                } catch (Exception ex) {
                    LOG.log(Level.WARNING, "Login failed for " + mail, ex);
                    result = "Login failed: " + ex.getMessage();
                }

                final String text = result;
                SwingUtilities.invokeLater(() -> {
                    reload.run();
                    status.setText(text);
                    add.setEnabled(true);
                });
            });
        });

        remove.addActionListener((e) -> {
            String email = list.getSelectedValue();
            if (email != null && JOptionPane.showConfirmDialog(list, "Remove " + email + " from this app?", "Remove account", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                try {
                    new AccountStore(mp).deleteMegaAccount(email);
                    status.setText("Removed " + email);
                } catch (Exception ex) {
                    status.setText("Could not remove: " + ex.getMessage());
                }
                reload.run();
            }
        });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.add(add);
        buttons.add(remove);

        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(420, 220));

        JPanel south = new JPanel(new BorderLayout(0, 6));
        south.add(buttons, BorderLayout.NORTH);
        south.add(status, BorderLayout.SOUTH);

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(south, BorderLayout.SOUTH);

        JOptionPane.showOptionDialog(parent, panel, "MEGA accounts", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, new Object[]{"Close"}, null);
    }

    // --------------------------------------------------------------- settings
    private static JSpinner spinner(int value, int min, int max) {
        return new JSpinner(new SpinnerNumberModel(Math.max(min, Math.min(max, value)), min, max, 1));
    }

    private static String yes(JCheckBox box) {
        return box.isSelected() ? "yes" : "no";
    }

    static void settings(final Component parent, final MainPanel mp) {

        JTextField dir = new JTextField(downloadDir(mp), 30);
        JSpinner max_dl = spinner(mp.getMax_dl(), 1, Transference.MAX_SIM_TRANSFERENCES);
        JSpinner max_ul = spinner(mp.getMax_ul(), 1, Transference.MAX_SIM_TRANSFERENCES);
        JCheckBox use_slots = new JCheckBox("Split each download into several connections", mp.isUse_slots_down());
        JSpinner slots_dl = spinner(mp.getDefault_slots_down(), Transference.MIN_WORKERS, Transference.MAX_WORKERS);
        JSpinner slots_ul = spinner(mp.getDefault_slots_up(), Transference.MIN_WORKERS, Transference.MAX_WORKERS);
        JCheckBox limit_dl = new JCheckBox("Limit download speed (KB/s)", mp.isLimit_download_speed());
        JSpinner speed_dl = spinner(mp.getMax_dl_speed(), 1, 10_000_000);
        JCheckBox limit_ul = new JCheckBox("Limit upload speed (KB/s)", mp.isLimit_upload_speed());
        JSpinner speed_ul = spinner(mp.getMax_up_speed(), 1, 10_000_000);
        JComboBox<String> account = accountBox(mp, true, mp.isUse_mega_account_down() ? mp.getMega_account_down() : null);
        JCheckBox vpn = new JCheckBox("Use ExpressVPN to get past MEGA's transfer quota", MainPanel.isUse_smart_proxy());
        JCheckBox force_vpn = new JCheckBox("Keep the VPN connected all the time", "yes".equals(DBTools.selectSettingValue("force_vpn")));
        String locations = DBTools.selectSettingValue("vpn_locations");
        JTextField vpn_locations = new JTextField(locations == null ? "" : locations);
        vpn_locations.putClientProperty("JTextField.placeholderText", "automatic, or e.g. smart, usa-new-york");

        JPanel panel = form(
                "Download folder", folderField(parent, dir),
                "Downloads at once", max_dl,
                "Uploads at once", max_ul,
                null, use_slots,
                "Connections per download", slots_dl,
                "Connections per upload", slots_ul,
                null, limit_dl,
                "Download limit", speed_dl,
                null, limit_ul,
                "Upload limit", speed_ul,
                "Download with account", account,
                null, vpn,
                null, force_vpn,
                "VPN locations", vpn_locations);

        if (!ask(parent, "Settings", panel, "Save")) {
            return;
        }

        String email = account(account);

        String[][] values = {
            {"default_down_dir", dir.getText().trim()},
            {"max_downloads", max_dl.getValue().toString()},
            {"max_uploads", max_ul.getValue().toString()},
            {"use_slots_down", yes(use_slots)},
            {"default_slots_down", slots_dl.getValue().toString()},
            {"default_slots_up", slots_ul.getValue().toString()},
            {"limit_download_speed", yes(limit_dl)},
            {"max_download_speed", speed_dl.getValue().toString()},
            {"limit_upload_speed", yes(limit_ul)},
            {"max_upload_speed", speed_ul.getValue().toString()},
            {"use_mega_account_down", email != null ? "yes" : "no"},
            {"mega_account_down", email != null ? email : ""},
            {"vpn_enabled", yes(vpn)},
            {"force_vpn", yes(force_vpn)},
            {"vpn_locations", vpn_locations.getText().trim()}
        };

        try {
            for (String[] value : values) {
                DBTools.insertSettingValue(value[0], value[1]);
            }
        } catch (Exception ex) {
            LOG.log(Level.SEVERE, null, ex);
            error(parent, "Could not save the settings: " + ex.getMessage());
            return;
        }

        // Off the EDT: starting the VPN manager can take a while.
        MainPanel.THREAD_POOL.execute(mp::applyUserSettings);
    }
}
