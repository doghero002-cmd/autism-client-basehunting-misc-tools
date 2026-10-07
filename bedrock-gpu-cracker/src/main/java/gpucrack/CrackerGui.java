package gpucrack;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;

/**
 * Swing GUI for the GPU bedrock cracker: paint a multi-layer bedrock pattern, pick a device,
 * search. All GPU work runs on a SwingWorker so the UI never blocks; matches stream into the
 * table nearest-first as tiles complete.
 */
public final class CrackerGui extends JFrame {

    private static final int GRID = 16;
    private static final int LAYERS = 4;
    private static final Color BG = new Color(24, 24, 30);
    private static final Color CELL_EMPTY = new Color(32, 32, 40);
    private static final Color CELL_BEDROCK = new Color(46, 125, 50);
    private static final Color CELL_NOT = new Color(198, 40, 40);
    private static final Color CELL_GHOST_B = new Color(28, 52, 30);
    private static final Color CELL_GHOST_N = new Color(52, 28, 28);
    private static final Color GRID_LINE = new Color(58, 58, 70);

    /** [layer][col][row]: 0 unknown, 1 bedrock, 2 not-bedrock. */
    private final int[][][] grids = new int[LAYERS][GRID][GRID];
    private int activeLayer = 0;
    private boolean roofMode = false;

    /** Rotation mode: [col][row] rotation 0-3 or -1 unknown. */
    private final int[][] rotGrid = new int[GRID][GRID];
    private boolean rotationMode = false;
    {
        for (int c = 0; c < GRID; c++) java.util.Arrays.fill(rotGrid[c], -1);
    }

    private final JToggleButton modeBedrock = new JToggleButton("Bedrock", true);
    private final JToggleButton modeRotation = new JToggleButton("Rotation");
    private final JToggleButton[] layerTabs = new JToggleButton[LAYERS];
    private final GridCanvas canvas = new GridCanvas();
    private final JTextField seedField = new JTextField("0", 14);
    private final JTextField rotYField = new JTextField("64", 5);
    private final JCheckBox legacyBox = new JCheckBox("Legacy formula (old clients)");
    private final JTextField centerXField = new JTextField("0", 7);
    private final JTextField centerZField = new JTextField("0", 7);
    private final JTextField radiusField = new JTextField("100000", 9);
    private final JCheckBox fullWorldBox = new JCheckBox("Full world (\u00b130M, hours!)");
    private final JToggleButton roofToggle = new JToggleButton("Floor mode");
    private final JComboBox<GpuSearch.Device> deviceBox = new JComboBox<>();
    private final JButton searchButton = new JButton("Search");
    private final JButton cancelButton = new JButton("Cancel");
    private final JProgressBar progressBar = new JProgressBar(0, 1000);
    private final DefaultTableModel matchModel = new DefaultTableModel(
        new Object[]{"X", "Z", "Rotation", "Distance"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JTable matchTable = new JTable(matchModel);
    private final JLabel statusLabel = new JLabel("Ready");
    private final JLabel deviceStatus = new JLabel(" ");

    private SearchWorker worker;

    public CrackerGui() {
        super("Bedrock GPU Cracker");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(940, 640));
        setLayout(new BorderLayout(8, 8));

        add(buildLeftPanel(), BorderLayout.CENTER);
        add(buildRightPanel(), BorderLayout.EAST);
        add(buildBottomPanel(), BorderLayout.SOUTH);

        modeBedrock.addActionListener(e -> setMode(false));
        modeRotation.addActionListener(e -> setMode(true));
        javax.swing.ButtonGroup modeGroup = new javax.swing.ButtonGroup();
        modeGroup.add(modeBedrock);
        modeGroup.add(modeRotation);
        setMode(false);

        refreshLayerTabs();
        loadDevices();
        pack();
        setLocationRelativeTo(null);
    }

    /** Switch between the bedrock painter and the rotation painter. */
    private void setMode(boolean rotation) {
        this.rotationMode = rotation;
        modeBedrock.setSelected(!rotation);
        modeRotation.setSelected(rotation);
        for (JToggleButton tab : layerTabs) tab.setVisible(!rotation);
        roofToggle.setVisible(!rotation);
        seedField.setEnabled(!rotation);
        rotYField.setVisible(rotation);
        legacyBox.setVisible(rotation);
        refreshLayerTabs();
        canvas.repaint();
    }

    // ---- layout ----

    private JPanel buildLeftPanel() {
        JPanel left = new JPanel(new BorderLayout(4, 4));
        left.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 0));

        JPanel tabs = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        for (int i = 0; i < LAYERS; i++) {
            final int layer = i;
            layerTabs[i] = new JToggleButton();
            layerTabs[i].addActionListener(e -> {
                activeLayer = layer;
                refreshLayerTabs();
                canvas.repaint();
            });
            tabs.add(layerTabs[i]);
        }
        JButton clearLayer = new JButton("Clear layer");
        clearLayer.addActionListener(e -> {
            if (rotationMode) {
                for (int c = 0; c < GRID; c++) java.util.Arrays.fill(rotGrid[c], -1);
            } else {
                grids[activeLayer] = new int[GRID][GRID];
            }
            canvas.repaint();
        });
        JButton clearAll = new JButton("Clear all");
        clearAll.addActionListener(e -> {
            for (int i = 0; i < LAYERS; i++) grids[i] = new int[GRID][GRID];
            for (int c = 0; c < GRID; c++) java.util.Arrays.fill(rotGrid[c], -1);
            canvas.repaint();
        });
        JPanel modeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        modeRow.add(new JLabel("Mode:"));
        modeRow.add(modeBedrock);
        modeRow.add(modeRotation);
        JPanel top = new JPanel(new BorderLayout());
        top.add(modeRow, BorderLayout.NORTH);
        tabs.add(Box.createHorizontalStrut(12));
        tabs.add(clearLayer);
        tabs.add(clearAll);
        top.add(tabs, BorderLayout.CENTER);
        left.add(top, BorderLayout.NORTH);
        left.add(canvas, BorderLayout.CENTER);

        JLabel legend = new JLabel("Bedrock: L-click bedrock / R-click not. Rotation: click cycles 0-3 / R-click clear.");
        legend.setFont(legend.getFont().deriveFont(Font.PLAIN, 11f));
        left.add(legend, BorderLayout.SOUTH);
        return left;
    }

    private JPanel buildRightPanel() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.gridy = 0;
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(2, 2, 2, 2);

        form.add(new JLabel("World seed (bedrock):"), g);
        g.gridy++;
        form.add(seedField, g);
        g.gridy++;
        JPanel rotRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        rotRow.add(new JLabel("Rotation Y:"));
        rotRow.add(rotYField);
        form.add(rotRow, g);
        g.gridy++;
        form.add(legacyBox, g);
        g.gridy++;
        form.add(new JLabel("Center X / Z:"), g);
        g.gridy++;
        JPanel center = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        center.add(centerXField);
        center.add(centerZField);
        form.add(center, g);
        g.gridy++;
        form.add(new JLabel("Radius (blocks):"), g);
        g.gridy++;
        form.add(radiusField, g);
        g.gridy++;
        form.add(fullWorldBox, g);
        fullWorldBox.addActionListener(e -> radiusField.setEnabled(!fullWorldBox.isSelected()));
        g.gridy++;
        roofToggle.addActionListener(e -> {
            roofMode = roofToggle.isSelected();
            roofToggle.setText(roofMode ? "Nether roof mode" : "Floor mode");
            refreshLayerTabs();
        });
        form.add(roofToggle, g);
        g.gridy++;
        form.add(new JLabel("GPU device:"), g);
        g.gridy++;
        form.add(deviceBox, g);
        g.gridy++;
        form.add(deviceStatus, g);
        g.gridy++;
        JButton importBtn = new JButton("Import pattern...");
        importBtn.addActionListener(e -> importPattern());
        form.add(importBtn, g);
        g.gridy++;
        JButton saveBtn = new JButton("Save pattern...");
        saveBtn.addActionListener(e -> savePattern());
        form.add(saveBtn, g);
        g.gridy++;
        g.weighty = 1;
        form.add(Box.createVerticalGlue(), g);
        return form;
    }

    private JPanel buildBottomPanel() {
        JPanel bottom = new JPanel();
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        bottom.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        searchButton.addActionListener(e -> startSearch());
        cancelButton.addActionListener(e -> {
            if (worker != null) worker.cancelSearch();
        });
        cancelButton.setEnabled(false);
        JButton saveMatches = new JButton("Save matches...");
        saveMatches.addActionListener(e -> saveMatches());
        controls.add(searchButton);
        controls.add(cancelButton);
        controls.add(saveMatches);
        controls.add(statusLabel);
        bottom.add(controls);

        progressBar.setStringPainted(true);
        progressBar.setString("");
        bottom.add(progressBar);

        matchTable.setAutoCreateRowSorter(true);
        JScrollPane scroll = new JScrollPane(matchTable);
        scroll.setPreferredSize(new Dimension(100, 150));
        bottom.add(scroll);

        JPopupMenu menu = new JPopupMenu();
        JMenuItem copyCoords = new JMenuItem("Copy coords");
        copyCoords.addActionListener(e -> copySelectedRow(false));
        JMenuItem copyTp = new JMenuItem("Copy /tp command");
        copyTp.addActionListener(e -> copySelectedRow(true));
        menu.add(copyCoords);
        menu.add(copyTp);
        matchTable.setComponentPopupMenu(menu);
        return bottom;
    }

    private void refreshLayerTabs() {
        for (int i = 0; i < LAYERS; i++) {
            int y = roofMode ? 126 - i : -60 - i;
            layerTabs[i].setText("Y " + y);
            layerTabs[i].setSelected(i == activeLayer);
        }
    }

    // ---- device handling ----

    private void loadDevices() {
        List<GpuSearch.Device> devices = GpuSearch.listDevices();
        deviceBox.removeAllItems();
        for (GpuSearch.Device d : devices) deviceBox.addItem(d);
        if (devices.isEmpty()) {
            deviceStatus.setText("<html><font color='red'>No OpenCL device found!</font></html>");
            searchButton.setEnabled(false);
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                """
                No OpenCL-capable device was found.

                Install your GPU vendor's driver (NVIDIA/AMD/Intel all
                include OpenCL) and restart this tool.""",
                "No GPU available", JOptionPane.WARNING_MESSAGE));
        } else {
            deviceStatus.setText(devices.get(0).gpu() ? "GPU ready" : "CPU device only (slow)");
        }
    }

    // ---- pattern I/O ----

    private PatternFile buildPattern() {
        // Shared bounding box across layers keeps columns aligned.
        int minR = GRID, maxR = -1, minC = GRID, maxC = -1;
        for (int l = 0; l < LAYERS; l++) {
            for (int c = 0; c < GRID; c++) {
                for (int r = 0; r < GRID; r++) {
                    if (grids[l][c][r] == 0) continue;
                    if (r < minR) minR = r;
                    if (r > maxR) maxR = r;
                    if (c < minC) minC = c;
                    if (c > maxC) maxC = c;
                }
            }
        }
        if (maxR == -1) return null;

        List<String> lines = new ArrayList<>();
        if (roofMode) lines.add("roof");
        for (int l = 0; l < LAYERS; l++) {
            boolean has = false;
            outer:
            for (int c = 0; c < GRID; c++) {
                for (int r = 0; r < GRID; r++) {
                    if (grids[l][c][r] != 0) { has = true; break outer; }
                }
            }
            if (!has) continue;
            lines.add("layer " + (roofMode ? 126 - l : -60 - l));
            for (int r = minR; r <= maxR; r++) {
                StringBuilder sb = new StringBuilder();
                for (int c = minC; c <= maxC; c++) {
                    int v = grids[l][c][r];
                    sb.append(v == 1 ? 'B' : v == 2 ? 'N' : '.');
                }
                lines.add(sb.toString());
            }
        }
        return PatternFile.parse(lines);
    }

    private void importPattern() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Import pattern (bedrock-pattern.txt)");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            PatternFile p = PatternFile.load(chooser.getSelectedFile().toPath());
            for (int i = 0; i < LAYERS; i++) grids[i] = new int[GRID][GRID];
            roofMode = p.roof();
            roofToggle.setSelected(roofMode);
            roofToggle.setText(roofMode ? "Nether roof mode" : "Floor mode");
            for (PatternFile.Layer layer : p.layers()) {
                int idx = roofMode ? 126 - layer.y() : -60 - layer.y();
                if (idx < 0 || idx >= LAYERS) continue;
                for (int c = 0; c < p.cols() && c < GRID; c++) {
                    for (int r = 0; r < p.rows() && r < GRID; r++) {
                        grids[idx][c][r] = layer.grid()[c][r];
                    }
                }
            }
            refreshLayerTabs();
            canvas.repaint();
            statusLabel.setText("Imported " + p.layers().size() + " layer(s), " + p.markedCells() + " cell(s)");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Import failed: " + ex.getMessage(),
                "Import error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void savePattern() {
        PatternFile p = buildPattern();
        if (p == null) {
            JOptionPane.showMessageDialog(this, "Draw a pattern first.", "Empty pattern", JOptionPane.WARNING_MESSAGE);
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new java.io.File("bedrock-pattern.txt"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            p.save(chooser.getSelectedFile().toPath());
            statusLabel.setText("Pattern saved");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Save failed: " + ex.getMessage(),
                "Save error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void saveMatches() {
        if (matchModel.getRowCount() == 0) {
            JOptionPane.showMessageDialog(this, "No matches to save.", "Empty", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new java.io.File("matches.txt"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < matchModel.getRowCount(); i++) {
                lines.add(matchModel.getValueAt(i, 0) + ", " + matchModel.getValueAt(i, 1)
                    + ", " + matchModel.getValueAt(i, 2));
            }
            Files.write(Path.of(chooser.getSelectedFile().toURI()), lines);
            statusLabel.setText("Matches saved");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Save failed: " + ex.getMessage(),
                "Save error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void copySelectedRow(boolean asTp) {
        int row = matchTable.getSelectedRow();
        if (row < 0) return;
        row = matchTable.convertRowIndexToModel(row);
        Object x = matchModel.getValueAt(row, 0);
        Object z = matchModel.getValueAt(row, 1);
        String text = asTp ? "/tp " + x + " ~ " + z : x + " " + z;
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        statusLabel.setText("Copied: " + text);
    }

    // ---- search ----

    /** Build the rotation pattern (bounding box of known cells). */
    private RotationPattern buildRotationPattern() {
        int minR = GRID, maxR = -1, minC = GRID, maxC = -1;
        for (int c = 0; c < GRID; c++) {
            for (int r = 0; r < GRID; r++) {
                if (rotGrid[c][r] < 0) continue;
                if (r < minR) minR = r;
                if (r > maxR) maxR = r;
                if (c < minC) minC = c;
                if (c > maxC) maxC = c;
            }
        }
        if (maxR == -1) return null;
        int cols = maxC - minC + 1, rows = maxR - minR + 1;
        int[][] grid = new int[cols][rows];
        List<RotationPattern.Cell> cells = new ArrayList<>();
        for (int c = minC; c <= maxC; c++) {
            for (int r = minR; r <= maxR; r++) {
                int v = rotGrid[c][r];
                grid[c - minC][r - minR] = v;
                if (v >= 0) cells.add(new RotationPattern.Cell(c - minC, r - minR, v));
            }
        }
        return new RotationPattern(cols, rows, List.copyOf(cells), grid);
    }

    private void startSearch() {
        PatternFile pattern = null;
        RotationPattern rotPattern = null;
        if (rotationMode) {
            rotPattern = buildRotationPattern();
            if (rotPattern == null) {
                JOptionPane.showMessageDialog(this, "Draw a rotation grid first.", "Empty grid", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (rotPattern.knownCells() < 10) {
                int ok = JOptionPane.showConfirmDialog(this,
                    rotPattern.knownCells() + " known cells is weak evidence - expect false matches.\nSearch anyway?",
                    "Weak grid", JOptionPane.YES_NO_OPTION);
                if (ok != JOptionPane.YES_OPTION) return;
            }
        } else {
            pattern = buildPattern();
            if (pattern == null) {
                JOptionPane.showMessageDialog(this, "Draw a pattern first.", "Empty pattern", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (pattern.markedCells() < 12) {
                int ok = JOptionPane.showConfirmDialog(this,
                    pattern.markedCells() + " marked cells is weak evidence - expect many false matches.\nSearch anyway?",
                    "Weak pattern", JOptionPane.YES_NO_OPTION);
                if (ok != JOptionPane.YES_OPTION) return;
            }
        }
        long seed = 0;
        int rotY = 64;
        int cx, cz;
        long radius;
        try {
            if (!rotationMode) seed = Long.parseLong(seedField.getText().trim());
            else rotY = Integer.parseInt(rotYField.getText().trim());
            cx = Integer.parseInt(centerXField.getText().trim());
            cz = Integer.parseInt(centerZField.getText().trim());
            radius = fullWorldBox.isSelected() ? 30_000_000L : Long.parseLong(radiusField.getText().trim());
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Seed/Y, center and radius must be numbers.",
                "Bad input", JOptionPane.ERROR_MESSAGE);
            return;
        }
        // World border cap: beyond +-30M the int tile coordinates would overflow.
        if (radius > 30_000_000L) {
            radius = 30_000_000L;
            statusLabel.setText("Radius clamped to 30,000,000 (world border)");
        }
        if (radius < 1) {
            JOptionPane.showMessageDialog(this, "Radius must be positive.", "Bad input", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (fullWorldBox.isSelected()) {
            int ok = JOptionPane.showConfirmDialog(this,
                "A full-world search covers ~3.6e15 positions and can take HOURS even on a fast GPU.\nContinue?",
                "Full world search", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (ok != JOptionPane.OK_OPTION) return;
            cx = 0;
            cz = 0;
        }
        GpuSearch.Device device = (GpuSearch.Device) deviceBox.getSelectedItem();
        if (device == null) return;

        matchModel.setRowCount(0);
        searchButton.setEnabled(false);
        cancelButton.setEnabled(true);
        statusLabel.setText("Compiling kernel + self-test...");
        worker = rotationMode
            ? new SearchWorker(rotPattern, rotY, legacyBox.isSelected(), cx, cz, radius, device)
            : new SearchWorker(pattern, seed, cx, cz, radius, device);
        worker.execute();
    }

    private final class SearchWorker extends SwingWorker<List<?>, Object[]> {
        private final PatternFile pattern;
        private final RotationPattern rotPattern;
        private final long seed;
        private final int rotY;
        private final boolean legacy;
        private final int cx, cz;
        private final long radius;
        private final GpuSearch.Device device;
        private volatile GpuSearch search;
        private volatile RotationGpuSearch rotSearch;
        private volatile boolean cancelFlag = false;
        private String error;

        SearchWorker(PatternFile pattern, long seed, int cx, int cz, long radius, GpuSearch.Device device) {
            this.pattern = pattern;
            this.rotPattern = null;
            this.seed = seed;
            this.rotY = 0;
            this.legacy = false;
            this.cx = cx;
            this.cz = cz;
            this.radius = radius;
            this.device = device;
        }

        SearchWorker(RotationPattern rotPattern, int rotY, boolean legacy, int cx, int cz, long radius, GpuSearch.Device device) {
            this.pattern = null;
            this.rotPattern = rotPattern;
            this.seed = 0;
            this.rotY = rotY;
            this.legacy = legacy;
            this.cx = cx;
            this.cz = cz;
            this.radius = radius;
            this.device = device;
        }

        void cancelSearch() {
            cancelFlag = true;
            GpuSearch s = search;
            if (s != null) s.cancel();
            RotationGpuSearch rs = rotSearch;
            if (rs != null) rs.cancel();
        }

        @Override
        protected List<?> doInBackground() {
            try {
                if (rotPattern != null) {
                    try (RotationGpuSearch s = new RotationGpuSearch(device)) {
                        rotSearch = s;
                        return s.search(rotPattern, rotY, legacy, cx, cz, radius, 8192, new RotationGpuSearch.Progress() {
                            @Override
                            public boolean onProgress(long done, long total, int matches, double rate) {
                                publish(new Object[]{done, total, matches, rate});
                                return !cancelFlag;
                            }

                            @Override
                            public void onMatch(RotationGpuSearch.Match m) {
                                publish(new Object[]{m});
                            }
                        });
                    }
                }
                try (GpuSearch s = new GpuSearch(device)) {
                    search = s;
                    return s.search(pattern, seed, cx, cz, radius, 8192, new GpuSearch.Progress() {
                        @Override
                        public boolean onProgress(long done, long total, int matches, double rate) {
                            publish(new Object[]{done, total, matches, rate});
                            return !cancelFlag;
                        }

                        @Override
                        public void onMatch(GpuSearch.Match m) {
                            publish(new Object[]{m});
                        }
                    });
                }
            } catch (Throwable t) {
                error = t.getMessage() == null ? t.toString() : t.getMessage();
                return null;
            } finally {
                search = null;
                rotSearch = null;
            }
        }

        @Override
        protected void process(List<Object[]> chunks) {
            for (Object[] chunk : chunks) {
                if (chunk.length == 1 && chunk[0] instanceof GpuSearch.Match m) {
                    long dist = Math.round(Math.hypot((double) m.x() - cx, (double) m.z() - cz));
                    matchModel.addRow(new Object[]{m.x(), m.z(), "Rot " + m.rotation() * 90 + "\u00b0", dist});                } else if (chunk.length == 1 && chunk[0] instanceof RotationGpuSearch.Match rm) {
                    long dist = Math.round(Math.hypot((double) rm.x() - cx, (double) rm.z() - cz));
                    matchModel.addRow(new Object[]{rm.x(), rm.z(), "Rot " + rm.rotation() * 90 + "°", dist});                } else if (chunk.length == 4) {
                    long done = (Long) chunk[0];
                    long total = (Long) chunk[1];
                    int matches = (Integer) chunk[2];
                    double rate = (Double) chunk[3];
                    int permille = (int) (done * 1000 / Math.max(1, total));
                    progressBar.setValue(permille);
                    long etaSec = rate > 1 ? (long) ((total - done) / rate) : -1;
                    progressBar.setString(String.format("%.1f%%  |  %,.0f anchors/s  |  %d match(es)%s",
                        permille / 10.0, rate, matches,
                        etaSec >= 0 ? String.format("  |  ETA %d:%02d:%02d", etaSec / 3600, etaSec % 3600 / 60, etaSec % 60) : ""));
                }
            }
        }

        @Override
        protected void done() {
            searchButton.setEnabled(true);
            cancelButton.setEnabled(false);
            if (error != null) {
                statusLabel.setText("Failed");
                JOptionPane.showMessageDialog(CrackerGui.this,
                    "Search failed:\n" + error, "Error", JOptionPane.ERROR_MESSAGE);
            } else if (cancelFlag) {
                statusLabel.setText("Cancelled - " + matchModel.getRowCount() + " match(es)");
            } else {
                statusLabel.setText("Done - " + matchModel.getRowCount() + " match(es)");
                progressBar.setValue(1000);
            }
        }
    }

    // ---- paint canvas ----

    private final class GridCanvas extends JPanel {
        private int paintValue = -1;

        GridCanvas() {
            setBackground(BG);
            setPreferredSize(new Dimension(GRID * 30 + 1, GRID * 30 + 1));
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    int[] cell = cellAt(e);
                    if (cell == null) return;
                    if (rotationMode) {
                        // Left-click cycles 0->1->2->3->clear; right-click clears.
                        if (SwingUtilities.isRightMouseButton(e)) {
                            rotGrid[cell[0]][cell[1]] = -1;
                            paintValue = -2; // drag-clear
                        } else {
                            rotGrid[cell[0]][cell[1]] = (rotGrid[cell[0]][cell[1]] + 1) % 4;
                            paintValue = rotGrid[cell[0]][cell[1]];
                        }
                        repaint();
                        return;
                    }
                    int target = SwingUtilities.isRightMouseButton(e) ? 2 : 1;
                    paintValue = grids[activeLayer][cell[0]][cell[1]] == target ? 0 : target;
                    grids[activeLayer][cell[0]][cell[1]] = paintValue;
                    repaint();
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    int[] cell = cellAt(e);
                    if (cell == null) return;
                    if (rotationMode) {
                        if (paintValue == -2) rotGrid[cell[0]][cell[1]] = -1;
                        else if (paintValue >= 0) rotGrid[cell[0]][cell[1]] = paintValue;
                        repaint();
                        return;
                    }
                    if (paintValue < 0) return;
                    grids[activeLayer][cell[0]][cell[1]] = paintValue;
                    repaint();
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    paintValue = -1;
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        private int cellSize() {
            return Math.max(12, Math.min(getWidth(), getHeight()) / GRID);
        }

        private int[] cellAt(MouseEvent e) {
            int cs = cellSize();
            int c = e.getX() / cs;
            int r = e.getY() / cs;
            return (c >= 0 && c < GRID && r >= 0 && r < GRID) ? new int[]{c, r} : null;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            int cs = cellSize();
            if (rotationMode) {
                java.awt.Graphics2D g2 = (java.awt.Graphics2D) g;
                g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                for (int c = 0; c < GRID; c++) {
                    for (int r = 0; r < GRID; r++) {
                        int v = rotGrid[c][r];
                        Color fill = v < 0 ? CELL_EMPTY : new Color(40, 60, 110);
                        g.setColor(fill);
                        g.fillRect(c * cs + 1, r * cs + 1, cs - 1, cs - 1);
                        g.setColor(GRID_LINE);
                        g.drawRect(c * cs, r * cs, cs, cs);
                        if (v >= 0) {
                            g.setColor(Color.WHITE);
                            g.setFont(getFont().deriveFont(Font.BOLD, cs * 0.55f));
                            String s = String.valueOf(v);
                            int sw = g.getFontMetrics().stringWidth(s);
                            g.drawString(s, c * cs + (cs - sw) / 2, r * cs + (cs + g.getFontMetrics().getAscent()) / 2 - 2);
                        }
                    }
                }
                return;
            }
            for (int c = 0; c < GRID; c++) {
                for (int r = 0; r < GRID; r++) {
                    int v = grids[activeLayer][c][r];
                    Color fill = v == 1 ? CELL_BEDROCK : v == 2 ? CELL_NOT : CELL_EMPTY;
                    if (v == 0) {
                        for (int l = 0; l < LAYERS; l++) {
                            if (l == activeLayer || grids[l][c][r] == 0) continue;
                            fill = grids[l][c][r] == 1 ? CELL_GHOST_B : CELL_GHOST_N;
                            break;
                        }
                    }
                    g.setColor(fill);
                    g.fillRect(c * cs + 1, r * cs + 1, cs - 1, cs - 1);
                    g.setColor(GRID_LINE);
                    g.drawRect(c * cs, r * cs, cs, cs);
                }
            }
        }
    }

    public static void launch() {
        SwingUtilities.invokeLater(() -> {
            try {
                javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}
            new CrackerGui().setVisible(true);
        });
    }
}
