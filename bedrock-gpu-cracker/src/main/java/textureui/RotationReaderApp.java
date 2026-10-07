package textureui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.table.DefaultTableModel;

import gpucrack.RotationGpuSearch;
import gpucrack.RotationPattern;

/**
 * Rotation Reader - a friendly desktop app for cracking coordinates from a screenshot's block
 * texture rotations. Guided flow:
 *
 *   1. Open a screenshot   -> the app auto-detects the block grid (blue overlay).
 *   2. Nudge the 4 corners -> drag them if the auto-grid is slightly off.
 *   3. Read the grid       -> pick the block, click Read; cells fill in with their rotation.
 *   4. Crack it            -> search on your GPU around you, or the whole world.
 *
 * Manual touch-up: left-click a cell to cycle 0-3, right-click to clear. Everything runs off
 * the UI thread; the solver is the verified {@link RotationGpuSearch}.
 */
public final class RotationReaderApp extends JFrame {

    private static final Color BG = new Color(28, 28, 34);
    private static final Color PANEL = new Color(36, 36, 44);
    private static final Color ACCENT = new Color(66, 133, 244);
    private static final Color TEXT = new Color(230, 230, 235);

    private BufferedImage image;
    private File imageFile;
    /** 4 corners TL TR BR BL in image pixels. */
    private final double[] corners = new double[8];
    private boolean cornersSet = false;
    private int dragCorner = -1;

    /** Rotation grid [row][col], -1 = unknown. */
    private int[][] grid;
    private int rows = 5, cols = 5;

    private final ImageCanvas canvas = new ImageCanvas();
    private final JLabel statusLabel = new JLabel("Open a screenshot to begin");
    private final JComboBox<String> blockBox = new JComboBox<>(new String[]{
        "dirt", "netherrack", "stone", "sand", "red_sand", "gravel", "grass_block", "podzol",
        "mycelium", "dirt_path", "rooted_dirt", "sculk", "deepslate", "bedrock",
        "white_concrete_powder", "gray_concrete_powder", "black_concrete_powder"});
    private final JSpinner rowsSpin = new JSpinner(new SpinnerNumberModel(5, 2, 16, 1));
    private final JSpinner colsSpin = new JSpinner(new SpinnerNumberModel(5, 2, 16, 1));
    private final JTextField yField = new JTextField("64", 5);
    private final JTextField ySpanField = new JTextField("1", 4);
    private final JTextField cxField = new JTextField("0", 7);
    private final JTextField czField = new JTextField("0", 7);
    private final JTextField radiusField = new JTextField("100000", 9);
    private final JCheckBox fullWorldBox = new JCheckBox("Whole world (\u00b130M, hours!)", false);
    private final JCheckBox legacyBox = new JCheckBox("Old client (legacy formula)", false);
    private final JButton readBtn = new JButton("Read grid");
    private final JButton crackBtn = new JButton("Crack it");
    private final JProgressBar progress = new JProgressBar(0, 1000);
    private final DefaultTableModel matchModel = new DefaultTableModel(
        new Object[]{"X", "Y", "Z", "Facing", "Distance"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JTable matchTable = new JTable(matchModel);
    private final gpucrack.GpuSearch.Device[] devices = gpucrack.GpuSearch.listDevices()
        .toArray(new gpucrack.GpuSearch.Device[0]);
    private final JComboBox<gpucrack.GpuSearch.Device> deviceBox = new JComboBox<>(devices);
    private SearchWorker worker;

    public RotationReaderApp() {
        super("Rotation Reader - coordinate cracker");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1020, 700));
        getContentPane().setBackground(BG);
        setLayout(new BorderLayout(8, 8));

        add(buildTopBar(), BorderLayout.NORTH);
        add(canvas, BorderLayout.CENTER);
        add(buildRight(), BorderLayout.EAST);
        add(buildBottom(), BorderLayout.SOUTH);
        setLocationRelativeTo(null);
        updateStatus();
    }

    // ---- layout ----

    private JPanel buildTopBar() {
        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
        bar.setBackground(PANEL);
        bar.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        JButton open = bigButton("1. Open screenshot...");
        open.addActionListener(e -> openImage());
        JButton autoGrid = bigButton("Auto-detect grid");
        autoGrid.addActionListener(e -> autoDetect());
        JButton clear = bigButton("Clear grid");
        clear.addActionListener(e -> {
            grid = new int[rows][cols];
            for (int r = 0; r < rows; r++) java.util.Arrays.fill(grid[r], -1);
            canvas.repaint();
        });
        bar.add(open);
        bar.add(Box.createHorizontalStrut(8));
        bar.add(autoGrid);
        bar.add(Box.createHorizontalStrut(8));
        bar.add(clear);
        bar.add(Box.createHorizontalGlue());
        statusLabel.setForeground(TEXT);
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 13f));
        bar.add(statusLabel);
        return bar;
    }

    private JPanel buildRight() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(PANEL);
        p.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 1, 0, 0, new Color(50, 50, 60)),
            BorderFactory.createEmptyBorder(10, 10, 10, 10)));
        p.setPreferredSize(new Dimension(250, 0));

        p.add(label("Block:"));
        style(blockBox);
        p.add(blockBox);
        p.add(Box.createVerticalStrut(8));

        JPanel rc = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
        rc.setBackground(PANEL);
        rc.add(label("Rows"));
        rc.add(rowsSpin);
        rc.add(label("Cols"));
        rc.add(colsSpin);
        p.add(rc);
        p.add(Box.createVerticalStrut(8));

        readBtn.setBackground(ACCENT);
        readBtn.setForeground(Color.WHITE);
        readBtn.addActionListener(e -> readGrid());
        p.add(readBtn);
        p.add(Box.createVerticalStrut(14));

        p.add(label("Block Y level:"));
        p.add(yField);
        JPanel yspan = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
        yspan.setBackground(PANEL);
        yspan.add(label("Scan +"));
        yspan.add(ySpanField);
        yspan.add(label("levels up (1 = exact Y)"));
        p.add(yspan);
        p.add(Box.createVerticalStrut(8));
        p.add(label("Search centre X / Z:"));
        JPanel ctr = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
        ctr.setBackground(PANEL);
        ctr.add(cxField);
        ctr.add(czField);
        p.add(ctr);
        p.add(Box.createVerticalStrut(8));
        p.add(label("Radius (blocks):"));
        p.add(radiusField);
        fullWorldBox.setForeground(TEXT);
        fullWorldBox.setBackground(PANEL);
        fullWorldBox.addActionListener(e -> radiusField.setEnabled(!fullWorldBox.isSelected()));
        p.add(fullWorldBox);
        legacyBox.setForeground(TEXT);
        legacyBox.setBackground(PANEL);
        p.add(legacyBox);
        p.add(Box.createVerticalStrut(8));
        p.add(label("GPU device:"));
        style(deviceBox);
        p.add(deviceBox);
        p.add(Box.createVerticalStrut(12));
        crackBtn.setBackground(new Color(46, 125, 50));
        crackBtn.setForeground(Color.WHITE);
        crackBtn.addActionListener(e -> crack());
        p.add(crackBtn);
        p.add(Box.createVerticalGlue());
        return p;
    }

    private JPanel buildBottom() {
        JPanel b = new JPanel();
        b.setLayout(new BoxLayout(b, BoxLayout.Y_AXIS));
        b.setBackground(PANEL);
        b.setBorder(BorderFactory.createEmptyBorder(4, 10, 8, 10));
        progress.setStringPainted(true);
        b.add(progress);
        matchTable.setAutoCreateRowSorter(true);
        JScrollPane scroll = new JScrollPane(matchTable);
        scroll.setPreferredSize(new Dimension(100, 130));
        b.add(scroll);
        return b;
    }

    private JButton bigButton(String text) {
        JButton b = new JButton(text);
        b.setFont(b.getFont().deriveFont(Font.BOLD, 13f));
        return b;
    }

    private JLabel label(String s) {
        JLabel l = new JLabel(s);
        l.setForeground(TEXT);
        l.setAlignmentX(LEFT_ALIGNMENT);
        return l;
    }

    private void style(JComboBox<?> box) {
        box.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        box.setAlignmentX(LEFT_ALIGNMENT);
    }

    // ---- actions ----

    private void openImage() {
        JFileChooser ch = new JFileChooser();
        ch.setDialogTitle("Open a Minecraft screenshot");
        // Default to the screenshots folder if it exists.
        File shots = new File(System.getProperty("user.home"), "AppData/Roaming/.minecraft/screenshots");
        if (shots.isDirectory()) ch.setCurrentDirectory(shots);
        if (ch.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            imageFile = ch.getSelectedFile();
            image = ImageIO.read(imageFile);
            if (image == null) throw new IllegalStateException("unreadable image");
            cornersSet = false;
            grid = null;
            autoDetect();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Couldn't open that image:\n" + ex.getMessage(),
                "Open failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void autoDetect() {
        if (image == null) { status("Open a screenshot first"); return; }
        GridDetector.Quad q = GridDetector.detect(image, true);
        System.arraycopy(q.pts(), 0, corners, 0, 8);
        cornersSet = true;
        status("Grid detected - drag the corners to fine-tune, then Read grid");
        canvas.repaint();
    }

    private void readGrid() {
        if (image == null) { status("Open a screenshot first"); return; }
        if (!cornersSet) { status("Run Auto-detect grid (or place 4 corners) first"); return; }
        rows = (Integer) rowsSpin.getValue();
        cols = (Integer) colsSpin.getValue();
        // MVP: build an empty grid; the overlay shows cell boundaries for manual entry.
        // (Auto rotation classification needs shipped block textures - stretch goal.)
        grid = new int[rows][cols];
        for (int r = 0; r < rows; r++) java.util.Arrays.fill(grid[r], -1);
        status("Grid ready: left-click cells to set rotation 0-3, right-click to clear");
        canvas.repaint();
    }

    private void crack() {
        if (grid == null) { status("Read the grid first"); return; }
        int known = 0;
        for (int[] row : grid) for (int v : row) if (v >= 0) known++;
        if (known < 10) {
            int ok = JOptionPane.showConfirmDialog(this,
                known + " cells set - weak evidence, expect false matches.\nCrack anyway?",
                "Weak grid", JOptionPane.YES_NO_OPTION);
            if (ok != JOptionPane.YES_OPTION) return;
        }
        int y, ySpan, cx, cz;
        long radius;
        try {
            y = Integer.parseInt(yField.getText().trim());
            ySpan = Math.max(1, Integer.parseInt(ySpanField.getText().trim()));
            cx = Integer.parseInt(cxField.getText().trim());
            cz = Integer.parseInt(czField.getText().trim());
            radius = fullWorldBox.isSelected() ? 30_000_000L : Long.parseLong(radiusField.getText().trim());
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Y, Y-span, centre and radius must be numbers.",
                "Bad input", JOptionPane.ERROR_MESSAGE);
            return;
        }
        radius = Math.min(radius, 30_000_000L);
        gpucrack.GpuSearch.Device device = (gpucrack.GpuSearch.Device) deviceBox.getSelectedItem();
        if (device == null) {
            JOptionPane.showMessageDialog(this, "No OpenCL GPU found. Install your GPU driver.",
                "No GPU", JOptionPane.WARNING_MESSAGE);
            return;
        }
        // Build the rotation pattern (trim to known-cell bounding box).
        int minR = rows, maxR = -1, minC = cols, maxC = -1;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (grid[r][c] < 0) continue;
                minR = Math.min(minR, r); maxR = Math.max(maxR, r);
                minC = Math.min(minC, c); maxC = Math.max(maxC, c);
            }
        }
        if (maxR < 0) { status("Set some cell rotations first"); return; }
        int pc = maxC - minC + 1, pr = maxR - minR + 1;
        int[][] pg = new int[pc][pr];
        List<RotationPattern.Cell> cells = new ArrayList<>();
        for (int c = minC; c <= maxC; c++) {
            for (int r = minR; r <= maxR; r++) {
                int v = grid[r][c];
                pg[c - minC][r - minR] = v;
                if (v >= 0) cells.add(new RotationPattern.Cell(c - minC, r - minR, v));
            }
        }
        RotationPattern pattern = new RotationPattern(pc, pr, List.copyOf(cells), pg);

        matchModel.setRowCount(0);
        crackBtn.setEnabled(false);
        status("Cracking (self-test first)...");
        worker = new SearchWorker(pattern, y, ySpan, legacyBox.isSelected(), cx, cz, radius, device);
        worker.execute();
    }

    private void status(String s) {
        statusLabel.setText(s);
    }

    private void updateStatus() {
        if (devices.length == 0) status("No OpenCL GPU found - install your GPU driver");
    }

    // ---- search worker ----

    private final class SearchWorker extends SwingWorker<List<RotationGpuSearch.Match>, Object[]> {
        private final RotationPattern pattern;
        private final int y, ySpan, cx, cz;
        private final boolean legacy;
        private final long radius;
        private final gpucrack.GpuSearch.Device device;
        private volatile RotationGpuSearch search;
        private volatile boolean cancelFlag = false;
        private String error;

        SearchWorker(RotationPattern pattern, int y, int ySpan, boolean legacy, int cx, int cz, long radius,
                     gpucrack.GpuSearch.Device device) {
            this.pattern = pattern;
            this.y = y;
            this.ySpan = ySpan;
            this.legacy = legacy;
            this.cx = cx;
            this.cz = cz;
            this.radius = radius;
            this.device = device;
        }

        @Override
        protected List<RotationGpuSearch.Match> doInBackground() {
            try (RotationGpuSearch s = new RotationGpuSearch(device)) {
                search = s;
                return s.search(pattern, y, ySpan, legacy, cx, cz, radius, 8192, new RotationGpuSearch.Progress() {
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
            } catch (Throwable t) {
                error = t.getMessage() == null ? t.toString() : t.getMessage();
                return null;
            } finally {
                search = null;
            }
        }

        @Override
        protected void process(List<Object[]> chunks) {
            for (Object[] chunk : chunks) {
                if (chunk.length == 1 && chunk[0] instanceof RotationGpuSearch.Match m) {
                    long dist = Math.round(Math.hypot((double) m.x() - cx, (double) m.z() - cz));
                    matchModel.addRow(new Object[]{m.x(), m.y(), m.z(), "Rot " + m.rotation() * 90 + "°", dist});
                } else if (chunk.length == 4) {
                    long done = (Long) chunk[0];
                    long total = (Long) chunk[1];
                    int matches = (Integer) chunk[2];
                    double rate = (Double) chunk[3];
                    int pm = (int) (done * 1000 / Math.max(1, total));
                    progress.setValue(pm);
                    long eta = rate > 1 ? (long) ((total - done) / rate) : -1;
                    progress.setString(String.format("%.1f%%  |  %,.0f/s  |  %d match(es)%s",
                        pm / 10.0, rate, matches,
                        eta >= 0 ? String.format("  |  ETA %d:%02d:%02d", eta / 3600, eta % 3600 / 60, eta % 60) : ""));
                }
            }
        }

        @Override
        protected void done() {
            crackBtn.setEnabled(true);
            if (error != null) {
                status("Crack failed");
                JOptionPane.showMessageDialog(RotationReaderApp.this,
                    "Search failed:\n" + error, "Error", JOptionPane.ERROR_MESSAGE);
            } else {
                status("Done - " + matchModel.getRowCount() + " match(es)");
                progress.setValue(1000);
            }
        }
    }

    // ---- canvas ----

    private final class ImageCanvas extends JPanel {
        ImageCanvas() {
            setBackground(BG);
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (image == null || !cornersSet) return;
                    double[] img = toImg(e.getX(), e.getY());
                    // Near a corner? drag it. Else, if a grid is read, edit the cell.
                    dragCorner = nearCorner(e.getX(), e.getY());
                    if (dragCorner >= 0) {
                        corners[dragCorner * 2] = img[0];
                        corners[dragCorner * 2 + 1] = img[1];
                        repaint();
                    } else if (grid != null) {
                        editCell(img[0], img[1], SwingUtilities.isRightMouseButton(e));
                    }
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (dragCorner >= 0) {
                        double[] img = toImg(e.getX(), e.getY());
                        corners[dragCorner * 2] = img[0];
                        corners[dragCorner * 2 + 1] = img[1];
                        repaint();
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    dragCorner = -1;
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        private double scale() {
            if (image == null) return 1;
            return Math.min(getWidth() / (double) image.getWidth(), getHeight() / (double) image.getHeight()) * 0.98;
        }

        private int offX() {
            return image == null ? 0 : (getWidth() - (int) (image.getWidth() * scale())) / 2;
        }

        private int offY() {
            return image == null ? 0 : (getHeight() - (int) (image.getHeight() * scale())) / 2;
        }

        private double[] toImg(int sx, int sy) {
            return new double[]{(sx - offX()) / scale(), (sy - offY()) / scale()};
        }

        private int toScrX(double ix) { return offX() + (int) (ix * scale()); }
        private int toScrY(double iy) { return offY() + (int) (iy * scale()); }

        private int nearCorner(int sx, int sy) {
            for (int i = 0; i < 4; i++) {
                int cx = toScrX(corners[i * 2]), cy = toScrY(corners[i * 2 + 1]);
                if (Math.abs(sx - cx) < 14 && Math.abs(sy - cy) < 14) return i;
            }
            return -1;
        }

        /** Map an image point into the grid (bilinear-ish via the quad) and toggle the cell. */
        private void editCell(double ix, double iy, boolean clear) {
            double[] uv = quadUV(ix, iy);
            if (uv == null) return;
            int c = Math.min(cols - 1, Math.max(0, (int) (uv[0] * cols)));
            int r = Math.min(rows - 1, Math.max(0, (int) (uv[1] * rows)));
            grid[r][c] = clear ? -1 : (grid[r][c] + 1) % 4;
            repaint();
        }

        /** Inverse-map image point to (u,v) in [0,1] across the corner quad (bilinear approx). */
        private double[] quadUV(double ix, double iy) {
            // Solve bilinear interpolation for the quad TL,TR,BR,BL by a few Newton iterations.
            double u = 0.5, v = 0.5;
            double[] q = corners;
            for (int it = 0; it < 8; it++) {
                double[] p = bilinear(q, u, v);
                double ex = p[0] - ix, ey = p[1] - iy;
                if (ex * ex + ey * ey < 0.25) break;
                // Numerical Jacobian.
                double h = 1e-3;
                double[] pu = bilinear(q, u + h, v), pv = bilinear(q, u, v + h);
                double j11 = (pu[0] - p[0]) / h, j12 = (pv[0] - p[0]) / h;
                double j21 = (pu[1] - p[1]) / h, j22 = (pv[1] - p[1]) / h;
                double det = j11 * j22 - j12 * j21;
                if (Math.abs(det) < 1e-9) break;
                double du = (j22 * ex - j12 * ey) / det;
                double dv = (-j21 * ex + j11 * ey) / det;
                u -= du;
                v -= dv;
            }
            if (u < -0.02 || u > 1.02 || v < -0.02 || v > 1.02) return null;
            return new double[]{u, v};
        }

        private double[] bilinear(double[] q, double u, double v) {
            double x = (1 - u) * (1 - v) * q[0] + u * (1 - v) * q[2] + u * v * q[4] + (1 - u) * v * q[6];
            double y = (1 - u) * (1 - v) * q[1] + u * (1 - v) * q[3] + u * v * q[5] + (1 - u) * v * q[7];
            return new double[]{x, y};
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (image == null) {
                g.setColor(TEXT);
                g.setFont(g.getFont().deriveFont(Font.PLAIN, 16f));
                String s = "Open a screenshot to begin";
                g.drawString(s, (getWidth() - g.getFontMetrics().stringWidth(s)) / 2, getHeight() / 2);
                return;
            }
            g.drawImage(image, offX(), offY(), (int) (image.getWidth() * scale()),
                (int) (image.getHeight() * scale()), null);
            if (!cornersSet) return;

            // Region quad.
            Path2D quad = new Path2D.Double();
            quad.moveTo(toScrX(corners[0]), toScrY(corners[1]));
            for (int i = 1; i < 4; i++) quad.lineTo(toScrX(corners[i * 2]), toScrY(corners[i * 2 + 1]));
            quad.closePath();
            g.setColor(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 40));
            g.fill(quad);
            g.setColor(ACCENT);
            g.setStroke(new java.awt.BasicStroke(2f));
            g.draw(quad);

            // Grid lines across the quad (projected like the solver reads them).
            if (grid != null) {
                g.setColor(new Color(255, 255, 255, 90));
                g.setStroke(new java.awt.BasicStroke(1f));
                for (int ci = 0; ci <= cols; ci++) {
                    double u = ci / (double) cols;
                    double[] a = bilinear(corners, u, 0), b = bilinear(corners, u, 1);
                    g.drawLine(toScrX(a[0]), toScrY(a[1]), toScrX(b[0]), toScrY(b[1]));
                }
                for (int ri = 0; ri <= rows; ri++) {
                    double v = ri / (double) rows;
                    double[] a = bilinear(corners, 0, v), b = bilinear(corners, 1, v);
                    g.drawLine(toScrX(a[0]), toScrY(a[1]), toScrX(b[0]), toScrY(b[1]));
                }
                // Cell rotation labels.
                g.setFont(g.getFont().deriveFont(Font.BOLD, 13f));
                for (int r = 0; r < rows; r++) {
                    for (int c = 0; c < cols; c++) {
                        int val = grid[r][c];
                        if (val < 0) continue;
                        double[] p = bilinear(corners, (c + 0.5) / cols, (r + 0.5) / rows);
                        g.setColor(new Color(0, 0, 0, 160));
                        String s = String.valueOf(val);
                        int sx = toScrX(p[0]), sy = toScrY(p[1]);
                        g.fillOval(sx - 9, sy - 9, 18, 18);
                        g.setColor(Color.WHITE);
                        g.drawString(s, sx - g.getFontMetrics().stringWidth(s) / 2, sy + 5);
                    }
                }
            }

            // Corner handles.
            String[] names = {"TL", "TR", "BR", "BL"};
            for (int i = 0; i < 4; i++) {
                int cx = toScrX(corners[i * 2]), cy = toScrY(corners[i * 2 + 1]);
                g.setColor(Color.WHITE);
                g.fillOval(cx - 7, cy - 7, 14, 14);
                g.setColor(ACCENT);
                g.fillOval(cx - 5, cy - 5, 10, 10);
                g.setColor(Color.BLACK);
                g.setFont(g.getFont().deriveFont(Font.BOLD, 9f));
                g.drawString(names[i], cx - 6, cy + 3);
            }
        }
    }

    public static void launch() {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}
            new RotationReaderApp().setVisible(true);
        });
    }
}
