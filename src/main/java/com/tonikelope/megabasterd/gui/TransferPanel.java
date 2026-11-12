/*
 * mega-downloader
 * Originally MegaBasterd, © tonikelope since 2016. GPLv3.
 */
package com.tonikelope.megabasterd.gui;

import com.tonikelope.megabasterd.Download;
import com.tonikelope.megabasterd.MainPanel;
import com.tonikelope.megabasterd.MiscTools;
import com.tonikelope.megabasterd.TransferState;
import com.tonikelope.megabasterd.Transference;
import com.tonikelope.megabasterd.TransferenceManager;
import com.tonikelope.megabasterd.Upload;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;

/**
 * One transfer list (downloads or uploads): a table that mirrors the
 * manager's queues, plus the actions that apply to the selected rows.
 * Refreshed by polling from {@link MainWindow}'s timer; the engine never
 * calls into this class.
 */
final class TransferPanel extends JPanel {

    private static final String[] COLUMNS = {"Name", "Size", "Progress", "Speed", "Slots", "Status"};
    private static final int[] WIDTHS = {340, 90, 170, 100, 80, 380};
    private static final Color GREEN = new Color(0x8f, 0xc7, 0x9a), RED = new Color(0xe0, 0x7a, 0x7a), SAND = new Color(0xd8, 0xb8, 0x7a);

    private final TransferenceManager _manager;
    private final boolean _downloads;
    private List<Transference> _rows = new ArrayList<>();
    private final JLabel _summary = new JLabel();
    private final JCheckBoxMenuItem _keep_temp = new JCheckBoxMenuItem("Keep partial file when cancelled");

    private final AbstractTableModel _model = new AbstractTableModel() {
        @Override
        public int getRowCount() {
            return _rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            Transference t = _rows.get(row);
            TransferState state = state(t);
            switch (column) {
                case 0:
                    return t.getFile_name() != null ? t.getFile_name() : (t instanceof Download ? ((Download) t).getUrl() : "");
                case 1:
                    return t.getFile_size() > 0 ? MiscTools.formatBytes(t.getFile_size()) : "";
                case 2:
                    return t.getFile_size() > 0 ? (int) Math.min(100, Math.max(0, t.getProgress()) * 100 / t.getFile_size()) : -1;
                case 3:
                    return state.getSpeed();
                case 4:
                    int slots = t.getSlotsCount();
                    return slots > 0 ? (slots + " " + (t instanceof Download ? ((Download) t).getSlotsStatus() : ((Upload) t).getSlotsStatus())).trim() : "";
                default:
                    return state.getCbc().isEmpty() ? state.getStatus() : state.getStatus() + "  ·  " + state.getCbc();
            }
        }
    };

    private final JTable _table;

    private final Action _pause = action("Pause", () -> each(this::running, Transference::pause));
    private final Action _cancel = action("Cancel", () -> each(this::running, Transference::stop));
    private final Action _restart = action("Retry", () -> each(this::restartable, Transference::restart));
    private final Action _remove = action("Remove", () -> each(this::removable, Transference::close));
    private final Action _up = action("Move up", () -> each(this::waiting, Transference::upWaitQueue));
    private final Action _down = action("Move down", () -> each(this::waiting, Transference::downWaitQueue));
    private final Action _top = action("Move to top", () -> each(this::waiting, Transference::topWaitQueue));
    private final Action _bottom = action("Move to bottom", () -> each(this::waiting, Transference::bottomWaitQueue));
    private final Action _more_slots = action("More connections", () -> slots(1));
    private final Action _less_slots = action("Fewer connections", () -> slots(-1));
    private final Action _start = action("Start now", () -> each(Transference::isFrozen, Transference::unfreeze));
    private final Action _copy = action("Copy link", this::copyLinks);
    private final Action _open = action("Open folder", this::openFolder);
    private final Action _pause_all = action("Pause all", this::pauseAll);
    private final Action _clear = action("Clear finished", () -> manager().closeAllFinished());
    private final Action _cancel_all = action("Cancel everything…", this::cancelAll);
    private final Action _copy_all = action("Copy all links", this::copyAll);

    TransferPanel(TransferenceManager manager, boolean downloads, final String empty_hint) {

        super(new BorderLayout());

        _manager = manager;
        _downloads = downloads;

        _table = new JTable(_model) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (getRowCount() == 0) {
                    g.setColor(getTableHeader().getForeground().darker());
                    FontMetrics fm = g.getFontMetrics();
                    g.drawString(empty_hint, (getWidth() - fm.stringWidth(empty_hint)) / 2, getHeight() / 2);
                }
            }
        };
        _table.setFillsViewportHeight(true);
        _table.setRowHeight(36);
        _table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        _table.getTableHeader().setReorderingAllowed(false);
        if (_table.getTableHeader().getDefaultRenderer() instanceof JLabel) {
            ((JLabel) _table.getTableHeader().getDefaultRenderer()).setHorizontalAlignment(JLabel.LEADING);
        }
        for (int i = 0; i < WIDTHS.length; i++) {
            _table.getColumnModel().getColumn(i).setPreferredWidth(WIDTHS[i]);
        }
        _table.getColumnModel().getColumn(2).setCellRenderer(new ProgressRenderer());
        _table.getColumnModel().getColumn(5).setCellRenderer(new StatusRenderer());
        _table.getSelectionModel().addListSelectionListener((e) -> updateActions());

        JPopupMenu menu = new JPopupMenu();
        for (Action a : new Action[]{_pause, _cancel, _restart, _remove, _start, null, _top, _up, _down, _bottom, null, _more_slots, _less_slots, null, _copy}) {
            if (a == null) {
                menu.addSeparator();
            } else {
                menu.add(a);
            }
        }
        if (downloads) {
            menu.add(_open);
            menu.add(_keep_temp);
            _keep_temp.addActionListener((e) -> {
                for (Transference t : selected()) {
                    state(t).setKeepTemp(_keep_temp.isSelected());
                }
            });
        }
        menu.addSeparator();
        menu.add(_copy_all);
        menu.add(_cancel_all);

        _table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                popup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                popup(e);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e) && _downloads) {
                    openFolder();
                }
            }

            private void popup(MouseEvent e) {
                if (!e.isPopupTrigger()) {
                    return;
                }
                int row = _table.rowAtPoint(e.getPoint());
                if (row >= 0 && !_table.isRowSelected(row)) {
                    _table.setRowSelectionInterval(row, row);
                }
                menu.show(_table, e.getX(), e.getY());
            }
        });

        key(KeyEvent.VK_DELETE, "delete", () -> {
            _cancel.actionPerformed(null);
            _remove.actionPerformed(null);
        });
        key(KeyEvent.VK_SPACE, "pause", () -> _pause.actionPerformed(null));

        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 12));
        for (Action a : new Action[]{_pause, _cancel, _restart, _remove, null, _up, _down, null, _pause_all, _clear}) {
            if (a == null) {
                bar.addSeparator();
            } else {
                bar.add(a);
            }
        }
        bar.add(Box.createHorizontalGlue());
        bar.add(_summary);

        JScrollPane scroll = new JScrollPane(_table);
        scroll.setBorder(BorderFactory.createEmptyBorder());

        add(bar, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);

        updateActions();
    }

    int count() {
        return _rows.size();
    }

    /** Re-reads the manager's queues. EDT only. */
    void refresh() {

        LinkedHashSet<Transference> now = new LinkedHashSet<>(_manager.getTransference_running_list());
        now.addAll(_manager.getTransference_waitstart_queue());
        now.addAll(_manager.getTransference_waitstart_aux_queue());
        now.addAll(_manager.getTransference_provision_queue());
        now.addAll(_manager.getTransference_finished_queue());

        List<Transference> rows = new ArrayList<>(now);

        if (rows.equals(_rows)) {
            if (!rows.isEmpty()) {
                _model.fireTableRowsUpdated(0, rows.size() - 1);
            }
        } else {
            List<Transference> selection = selected();
            _rows = rows;
            _model.fireTableDataChanged();
            for (Transference t : selection) {
                int i = rows.indexOf(t);
                if (i >= 0) {
                    _table.addRowSelectionInterval(i, i);
                }
            }
        }

        _summary.setText(_manager.getStatus());

        updateActions();
    }

    private TransferenceManager manager() {
        return _manager;
    }

    private static TransferState state(Transference t) {
        return (TransferState) t.getView();
    }

    private boolean running(Transference t) {
        return _manager.getTransference_running_list().contains(t);
    }

    private boolean finished(Transference t) {
        return _manager.getTransference_finished_queue().contains(t);
    }

    private boolean waiting(Transference t) {
        return _manager.getTransference_waitstart_queue().contains(t) || _manager.getTransference_waitstart_aux_queue().contains(t);
    }

    private boolean restartable(Transference t) {
        return finished(t) && (t.isStatusError() || t.isCanceled());
    }

    private boolean removable(Transference t) {
        return finished(t) || waiting(t);
    }

    private boolean resizable(Transference t) {
        return running(t) && (!(t instanceof Download) || ((Download) t).isUse_slots());
    }

    private List<Transference> selected() {
        List<Transference> selection = new ArrayList<>();
        for (int row : _table.getSelectedRows()) {
            if (row < _rows.size()) {
                selection.add(_rows.get(row));
            }
        }
        return selection;
    }

    private boolean any(Predicate<Transference> test) {
        for (Transference t : selected()) {
            if (test.test(t)) {
                return true;
            }
        }
        return false;
    }

    /** Runs an engine call for every matching selected row, off the EDT. */
    private void each(Predicate<Transference> when, Consumer<Transference> call) {
        for (Transference t : selected()) {
            if (when.test(t)) {
                MainPanel.THREAD_POOL.execute(() -> call.accept(t));
            }
        }
    }

    private void slots(int delta) {
        each(this::resizable, (t) -> {
            TransferState state = state(t);
            state.setSlots(state.getSlots() + delta);
            t.checkSlotsAndWorkers();
        });
    }

    private void updateActions() {

        boolean any_running = any(this::running);

        _pause.setEnabled(any_running);
        _pause.putValue(Action.NAME, any_running && !any((t) -> running(t) && !t.isPaused()) ? "Resume" : "Pause");
        _cancel.setEnabled(any_running);
        _restart.setEnabled(any(this::restartable));
        _remove.setEnabled(any(this::removable));

        boolean any_waiting = any(this::waiting);
        for (Action a : new Action[]{_up, _down, _top, _bottom}) {
            a.setEnabled(any_waiting);
        }

        boolean any_resizable = any(this::resizable);
        _more_slots.setEnabled(any_resizable);
        _less_slots.setEnabled(any_resizable);

        _start.setEnabled(any(Transference::isFrozen));
        _copy.setEnabled(any((t) -> link(t) != null));
        _open.setEnabled(_table.getSelectedRowCount() > 0);
        _keep_temp.setEnabled(any_running);
        _keep_temp.setSelected(any_running && !any((t) -> running(t) && !state(t).isKeepTempFileSelected()));

        _pause_all.setEnabled(!_manager.getTransference_running_list().isEmpty());
        _pause_all.putValue(Action.NAME, _manager.isPaused_all() ? "Resume all" : "Pause all");
        _clear.setEnabled(!_manager.getTransference_finished_queue().isEmpty());
        _cancel_all.setEnabled(!_rows.isEmpty());
        _copy_all.setEnabled(!_rows.isEmpty());
    }

    private void pauseAll() {
        MainPanel.THREAD_POOL.execute(() -> {
            if (_manager.isPaused_all()) {
                _manager.resumeAll();
            } else {
                _manager.pauseAll();
            }
        });
    }

    private void cancelAll() {
        if (JOptionPane.showConfirmDialog(this, "Cancel every queued and running " + (_downloads ? "download" : "upload") + "?", "Cancel everything", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION) {
            MainPanel.THREAD_POOL.execute(_manager::cancelAllTransferences);
        }
    }

    private static String link(Transference t) {
        if (t instanceof Download) {
            return ((Download) t).getUrl();
        }
        Upload u = (Upload) t;
        String link = u.getFile_link() != null ? u.getFile_link() : u.getFolder_link();
        return link == null ? null : "[" + u.getMa().getEmail() + "] " + link;
    }

    private void copyLinks() {
        List<String> links = new ArrayList<>();
        for (Transference t : selected()) {
            if (link(t) != null) {
                links.add(link(t));
            }
        }
        MainWindow.copy(String.join("\n", links));
    }

    private void copyAll() {
        MainWindow.copy(_downloads ? ((com.tonikelope.megabasterd.DownloadManager) _manager).getAllLinksText() : ((com.tonikelope.megabasterd.UploadManager) _manager).getAllLinksText());
    }

    private void openFolder() {
        for (Transference t : selected()) {
            if (t instanceof Download && t.getFile_name() != null) {
                MainWindow.open(new File(((Download) t).getDownload_path(), t.getFile_name()).getParentFile());
                return;
            }
        }
    }

    private static Action action(String name, final Runnable run) {
        return new AbstractAction(name) {
            @Override
            public void actionPerformed(ActionEvent e) {
                run.run();
            }
        };
    }

    private void key(int key_code, String name, final Runnable run) {
        _table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key_code, 0), name);
        _table.getActionMap().put(name, action(name, run));
    }

    /** A padded bar; the surrounding panel carries the row's background. */
    private static final class ProgressRenderer extends JPanel implements TableCellRenderer {

        private final JProgressBar _bar = new JProgressBar(0, 100);

        ProgressRenderer() {
            super(new BorderLayout());
            setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
            _bar.setStringPainted(true);
            add(_bar);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus, int row, int column) {
            int percent = (Integer) value;
            _bar.setVisible(percent >= 0);
            _bar.setValue(Math.max(0, percent));
            setBackground(selected ? table.getSelectionBackground() : table.getBackground());
            return this;
        }
    }

    private final class StatusRenderer extends DefaultTableCellRenderer {

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus, int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, focus, row, column);
            setToolTipText(value == null ? null : value.toString());
            if (!selected && row < _rows.size()) {
                switch (state(_rows.get(row)).getStatusKind()) {
                    case TransferState.OK:
                        setForeground(GREEN);
                        break;
                    case TransferState.ERROR:
                        setForeground(RED);
                        break;
                    case TransferState.WARNING:
                        setForeground(SAND);
                        break;
                    default:
                        setForeground(table.getForeground());
                }
            }
            return this;
        }
    }
}
