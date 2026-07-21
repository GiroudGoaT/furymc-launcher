package com.mcfaction.launcher;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.WindowEvent;
import java.awt.BasicStroke;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.RescaleOp;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.List;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;

/**
 * Entry point + the whole UI. Deliberately a single class - this launcher does one thing (check for
 * updates, download if needed, ask for a pseudo, launch the game) and doesn't need the ceremony of
 * splitting a small Swing screen across many files.
 *
 * <p>
 * Two cards on a CardLayout: {@link #CARD_LOADING} (small window, shown first - runs the self-update
 * check, then the base/mod update-and-download) and {@link #CARD_MAIN} (the frame is resized up to
 * {@link #MAIN_SIZE} at this point - pseudo, Jouer, Param&egrave;tres). By the time the player can
 * click Jouer, the install is already up to date, so that button just launches the game directly.
 *
 * <p>
 * Undecorated (no native title bar - see {@link #applyRoundedShape()} and {@link #enableDragging}), so
 * it draws its own minimize/close buttons and rounded window shape.
 */
public class FuryMcLauncher extends JFrame {

    // Update this on every published release, alongside the corresponding version.json.
    private static final String MANIFEST_URL = "https://raw.githubusercontent.com/GiroudGoaT/furymc-launcher/main/version.json";

    // Bump this alongside the -PlauncherVersion passed to the packageExe Gradle task, and
    // version.json's launcherVersion/launcherJarUrl/launcherJarSha256, whenever the launcher's own code
    // changes (not game content - that's MANIFEST_URL's version/modUrl, unrelated to this). See
    // SelfUpdater: this is the only place that needs a manual "reinstall the .exe" step ever again.
    private static final String LAUNCHER_VERSION = "1.3.1";

    private static final Dimension LOADING_SIZE = new Dimension(420, 580);
    private static final Dimension MAIN_SIZE = new Dimension(1100, 620);
    private static final int CORNER_RADIUS = 22;

    // However fast the update check actually finishes, the loading screen stays up at least this
    // long - purely cosmetic (see Timer usage in startUpdateSequence).
    private static final int MIN_LOADING_DISPLAY_MS = 10_000;

    private static final String CARD_LOADING = "loading";
    private static final String CARD_MAIN = "main";

    private static final Color GOLD = new Color(0xF0, 0xC8, 0x78);
    private static final Color PANEL_DARK = new Color(0x28, 0x27, 0x2B, 245);

    private final LauncherConfig config = new LauncherConfig();
    private final UpdateManager updateManager = new UpdateManager();
    private final GameLauncher gameLauncher = new GameLauncher();

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cardHost = new JPanel(cardLayout);

    private JLabel loadingStatusLabel;
    private JButton retryButton;

    private JButton playButton;
    private JLabel mainStatusLabel;
    private JButton profileButton;
    private RootPanel content;

    public FuryMcLauncher() {
        super("FuryMc Launcher");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setResizable(false);
        setUndecorated(true);
        setIconImage(loadImage("/icon.png"));

        content = new RootPanel(loadImage("/loading-background.png"));
        content.setLayout(new BorderLayout());
        setContentPane(content);
        enableDragging(content);

        cardHost.setOpaque(false);
        cardHost.add(buildLoadingCard(), CARD_LOADING);
        cardHost.add(buildMainCard(), CARD_MAIN);
        content.add(cardHost, BorderLayout.CENTER);
        cardLayout.show(cardHost, CARD_LOADING);

        setSize(LOADING_SIZE);
        setLocationRelativeTo(null);
        applyRoundedShape();
    }

    /** No native title bar (see setUndecorated above) - just a minimize and a close glyph, flat and
     *  borderless so they blend into the background instead of reading as their own button. Only on the
     *  main card - the loading card shows none at all. */
    private JPanel buildWindowControls() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 4));
        panel.setOpaque(false);

        JButton minimizeButton = new WindowControlButton(false);
        minimizeButton.addActionListener(e -> setState(JFrame.ICONIFIED));

        JButton closeButton = new WindowControlButton(true);
        closeButton.addActionListener(e -> dispatchEvent(new WindowEvent(this, WindowEvent.WINDOW_CLOSING)));

        panel.add(minimizeButton);
        panel.add(closeButton);
        return panel;
    }

    /** Lets the player drag the window by any empty area of the background, since there's no title bar
     *  to drag by anymore. */
    private void enableDragging(JPanel dragHandle) {
        Point[] dragOrigin = new Point[1];
        dragHandle.addMouseListener(new MouseAdapter() {

            @Override
            public void mousePressed(MouseEvent e) {
                dragOrigin[0] = e.getPoint();
            }
        });
        dragHandle.addMouseMotionListener(new MouseMotionAdapter() {

            @Override
            public void mouseDragged(MouseEvent e) {
                Point location = getLocation();
                setLocation(location.x + e.getX() - dragOrigin[0].x, location.y + e.getY() - dragOrigin[0].y);
            }
        });
    }

    /** Clips the (undecorated) window to a rounded rectangle - must be reapplied after every setSize,
     *  since the shape is defined in the window's own coordinate space. */
    private void applyRoundedShape() {
        Shape shape = new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), CORNER_RADIUS, CORNER_RADIUS);
        setShape(shape);
    }

    private JPanel buildLoadingCard() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;

        JLabel titleLabel = new JLabel("FuryMc");
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 48));
        titleLabel.setForeground(GOLD);
        gbc.gridy = 0;
        gbc.insets = new Insets(0, 0, 20, 0);
        panel.add(titleLabel, gbc);

        loadingStatusLabel = new JLabel("Recherche de mise à jour...");
        loadingStatusLabel.setForeground(Color.WHITE);
        loadingStatusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        gbc.gridy = 1;
        gbc.insets = new Insets(0, 0, 14, 0);
        panel.add(loadingStatusLabel, gbc);

        retryButton = new StyledButton("Réessayer");
        retryButton.setVisible(false);
        retryButton.addActionListener(e -> startUpdateSequence());
        gbc.gridy = 2;
        panel.add(retryButton, gbc);

        return panel;
    }

    private JPanel buildMainCard() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);

        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.setOpaque(false);
        topPanel.add(buildWindowControls(), BorderLayout.NORTH);

        JPanel profileRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 20, 10));
        profileRow.setOpaque(false);
        profileButton = new ImageButton(loadImage("/button_profile.png"), 44, 44);
        profileButton.addActionListener(e -> showProfilePopup());
        profileRow.add(profileButton);
        topPanel.add(profileRow, BorderLayout.SOUTH);

        panel.add(topPanel, BorderLayout.NORTH);

        JPanel centerPanel = new JPanel(new GridBagLayout());
        centerPanel.setOpaque(false);

        GridBagConstraints logoGbc = new GridBagConstraints();
        logoGbc.gridx = 0;
        logoGbc.gridy = 0;
        logoGbc.insets = new Insets(0, 0, 16, 0);
        JLabel logoLabel = new JLabel(scaledIcon(loadImage("/logo.png"), 340));
        centerPanel.add(logoLabel, logoGbc);

        GridBagConstraints sloganGbc = new GridBagConstraints();
        sloganGbc.gridx = 0;
        sloganGbc.gridy = 1;
        JLabel sloganLabel = new JLabel("Si tu veux la paix, prépare la guerre");
        sloganLabel.setFont(new Font("Segoe UI", Font.ITALIC, 14));
        sloganLabel.setForeground(Color.WHITE);
        centerPanel.add(sloganLabel, sloganGbc);
        panel.add(centerPanel, BorderLayout.CENTER);

        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 20));
        bottomPanel.setOpaque(false);
        mainStatusLabel = new JLabel(" ");
        mainStatusLabel.setForeground(Color.WHITE);
        mainStatusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        playButton = new ImageButton(loadImage("/button_play.png"), 220, 66);
        playButton.addActionListener(e -> onPlay());
        JButton settingsButton = new ImageButton(loadImage("/button_settings.png"), 48, 48);
        settingsButton.addActionListener(e -> new SettingsDialog(this, config).setVisible(true));
        bottomPanel.add(mainStatusLabel);
        bottomPanel.add(playButton);
        bottomPanel.add(settingsButton);
        panel.add(bottomPanel, BorderLayout.SOUTH);

        return panel;
    }

    /** Anchored under the profile button - shows the saved pseudo + Se déconnecter if one is set,
     *  otherwise a field to pick one (first run, or right after déconnexion). */
    private void showProfilePopup() {
        JPopupMenu popup = new JPopupMenu();
        popup.setBorder(javax.swing.BorderFactory.createEmptyBorder());
        popup.setOpaque(false);

        RoundedPanel popupContent = new RoundedPanel();
        popupContent.setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.insets = new Insets(10, 18, 10, 18);

        String username = config.getUsername();
        if (username.isEmpty()) {
            JLabel prompt = new JLabel("Choisis ton pseudo");
            prompt.setForeground(Color.WHITE);
            prompt.setFont(new Font("Segoe UI", Font.BOLD, 13));
            gbc.gridy = 0;
            gbc.insets = new Insets(14, 18, 8, 18);
            popupContent.add(prompt, gbc);

            JTextField field = new JTextField(14);
            field.setFont(new Font("Segoe UI", Font.PLAIN, 13));
            gbc.gridy = 1;
            gbc.insets = new Insets(0, 18, 10, 18);
            popupContent.add(field, gbc);

            JButton confirm = new SolidButton("Valider", new Color(0x2F, 0x6F, 0xE0));
            confirm.addActionListener(e -> {
                String value = field.getText()
                    .trim();
                if (value.isEmpty() || value.length() > 16) {
                    mainStatusLabel.setText("Pseudo invalide (1-16 caractères)");
                    return;
                }
                config.setUsername(value);
                config.save();
                profileButton.repaint();
                popup.setVisible(false);
            });
            gbc.gridy = 2;
            gbc.insets = new Insets(0, 18, 14, 18);
            popupContent.add(confirm, gbc);
        } else {
            JLabel avatar = new AvatarLabel();
            gbc.gridy = 0;
            gbc.insets = new Insets(16, 18, 8, 18);
            popupContent.add(avatar, gbc);

            JLabel nameLabel = new JLabel(username);
            nameLabel.setForeground(Color.WHITE);
            nameLabel.setFont(new Font("Segoe UI", Font.BOLD, 14));
            gbc.gridy = 1;
            gbc.insets = new Insets(0, 18, 10, 18);
            popupContent.add(nameLabel, gbc);

            JButton logout = new JButton("Se déconnecter");
            logout.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            logout.setForeground(new Color(0xE0, 0x5A, 0x5A));
            logout.setContentAreaFilled(false);
            logout.setBorderPainted(false);
            logout.setFocusPainted(false);
            logout.addActionListener(e -> {
                config.setUsername("");
                config.save();
                profileButton.repaint();
                popup.setVisible(false);
                showProfilePopup();
            });
            gbc.gridy = 2;
            gbc.insets = new Insets(0, 18, 16, 18);
            popupContent.add(logout, gbc);
        }

        popup.add(popupContent);
        popup.pack();
        popup.show(profileButton, profileButton.getWidth() - popup.getPreferredSize().width, profileButton.getHeight() + 6);
    }

    private void onPlay() {
        String username = config.getUsername();
        if (username.isEmpty()) {
            mainStatusLabel.setText("Définis ton pseudo en haut à droite");
            showProfilePopup();
            return;
        }

        playButton.setEnabled(false);
        mainStatusLabel.setText("Lancement du jeu...");

        new SwingWorker<Void, Void>() {

            @Override
            protected Void doInBackground() {
                gameLauncher.launch(config.getInstallDir(), username, config.getOrCreateUuid(), config.getRamMb());
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    // The game runs as its own detached process (see GameLauncher#launch) - it doesn't
                    // need this window anymore, so close it instead of leaving it sitting behind the game.
                    dispose();
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    mainStatusLabel.setText("Erreur : " + cause.getMessage());
                    playButton.setEnabled(true);
                }
            }
        }.execute();
    }

    /** Runs once at startup: self-update check first (see SelfUpdater), then the base/mod
     *  update-and-download - the loading card stays up for both (at least MIN_LOADING_DISPLAY_MS), so
     *  by the time the player sees the main card and can click Jouer, everything is already current. */
    private void startUpdateSequence() {
        retryButton.setVisible(false);
        loadingStatusLabel.setText("Recherche de mise à jour...");
        long startedAt = System.currentTimeMillis();

        new SwingWorker<Boolean, String>() {

            @Override
            protected Boolean doInBackground() throws Exception {
                VersionManifest manifest = updateManager.fetchManifest(MANIFEST_URL);
                if (new SelfUpdater().checkAndApply(LAUNCHER_VERSION, manifest)) {
                    // A relaunch is already in flight via SelfUpdater's helper script.
                    return null;
                }

                Path installDir = config.getInstallDir();
                if (updateManager.needsBaseUpdate(installDir, manifest)) {
                    publish("Téléchargement des fichiers du jeu (première installation)...");
                    updateManager.downloadAndInstallBase(
                        installDir,
                        manifest,
                        (percent, status) -> publish(status));
                }
                if (updateManager.needsModUpdate(installDir, manifest)) {
                    updateManager.downloadAndInstallMod(
                        installDir,
                        manifest,
                        (percent, status) -> publish(status));
                }
                return true;
            }

            @Override
            protected void process(List<String> chunks) {
                loadingStatusLabel.setText(chunks.get(chunks.size() - 1));
            }

            @Override
            protected void done() {
                try {
                    Boolean result = get();
                    if (result == null) {
                        // Self-update relaunch in flight - just wait to be replaced, don't touch the UI.
                        return;
                    }
                    long remaining = MIN_LOADING_DISPLAY_MS - (System.currentTimeMillis() - startedAt);
                    Timer timer = new Timer((int) Math.max(0, remaining), e -> transitionToMain());
                    timer.setRepeats(false);
                    timer.start();
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    loadingStatusLabel.setText("Erreur : " + cause.getMessage());
                    retryButton.setVisible(true);
                }
            }
        }.execute();
    }

    private void transitionToMain() {
        cardLayout.show(cardHost, CARD_MAIN);
        content.setBackgroundImage(loadImage("/background.png"));
        setSize(MAIN_SIZE);
        setLocationRelativeTo(null);
        applyRoundedShape();
    }

    private static Image loadImage(String resourcePath) {
        try (InputStream in = FuryMcLauncher.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new LauncherException("Missing bundled resource: " + resourcePath);
            }
            return ImageIO.read(in);
        } catch (IOException e) {
            throw new LauncherException("Could not load image " + resourcePath, e);
        }
    }

    /** Scales an image down to the given display width, preserving aspect ratio, for use as a JLabel icon. */
    private static ImageIcon scaledIcon(Image source, int displayWidth) {
        int displayHeight = Math.round(displayWidth * (source.getHeight(null) / (float) source.getWidth(null)));
        return new ImageIcon(source.getScaledInstance(displayWidth, displayHeight, Image.SCALE_SMOOTH));
    }

    /** Paints the FuryMc background image scaled to fill the window, plus a gradient border tracing the
     *  rounded window shape (undecorated windows lose the OS drop shadow, this stands in for it). */
    private static class RootPanel extends JPanel {

        private Image background;

        RootPanel(Image background) {
            this.background = background;
        }

        void setBackgroundImage(Image background) {
            this.background = background;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            Shape clip = new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), CORNER_RADIUS, CORNER_RADIUS);
            g2.setClip(clip);
            g2.drawImage(background, 0, 0, getWidth(), getHeight(), null);
            g2.setClip(null);

            g2.setStroke(new java.awt.BasicStroke(3F));
            g2.setPaint(
                new java.awt.GradientPaint(0, 0, GOLD, getWidth(), getHeight(), new Color(0x5E, 0x32, 0x86)));
            g2.draw(
                new RoundRectangle2D.Double(1.5, 1.5, getWidth() - 3, getHeight() - 3, CORNER_RADIUS, CORNER_RADIUS));
            g2.dispose();
        }
    }

    /** Flat glyph, no button chrome at all (no fill, no border, no hover shape) so it reads as part of
     *  the background artwork rather than a UI control - only the glyph color shifts on hover. */
    private static class WindowControlButton extends JButton {

        private final boolean isClose;
        private boolean hovered;

        WindowControlButton(boolean isClose) {
            this.isClose = isClose;
            setFocusPainted(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setPreferredSize(new Dimension(32, 24));
            addMouseListener(new MouseAdapter() {

                @Override
                public void mouseEntered(MouseEvent e) {
                    hovered = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hovered = false;
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            g2.setColor(
                hovered ? (isClose ? new Color(0xE0, 0x6A, 0x6A) : Color.WHITE)
                    : new Color(255, 255, 255, 170));
            g2.setStroke(new java.awt.BasicStroke(1.4F));
            int w = getWidth();
            int h = getHeight();
            int pad = w / 3;
            if (isClose) {
                g2.drawLine(pad, h / 2 - w / 6, w - pad, h / 2 + w / 6);
                g2.drawLine(w - pad, h / 2 - w / 6, pad, h / 2 + w / 6);
            } else {
                g2.drawLine(pad, h / 2, w - pad, h / 2);
            }
            g2.dispose();
        }
    }

    /** Small pixelated placeholder avatar (no real skin-fetching yet - offline-mode UUIDs don't map to
     *  a real Mojang skin anyway). */
    private static class AvatarLabel extends JLabel {

        AvatarLabel() {
            setPreferredSize(new Dimension(48, 48));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setColor(new Color(0x8A, 0x6D, 0x5A));
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.setColor(new Color(0x5A, 0x42, 0x33));
            int eyeSize = getWidth() / 8;
            g2.fillRect(getWidth() / 3 - eyeSize / 2, getHeight() / 3, eyeSize, eyeSize);
            g2.fillRect(getWidth() * 2 / 3 - eyeSize / 2, getHeight() / 3, eyeSize, eyeSize);
            g2.dispose();
        }
    }

    /** Same purple/gold gradient look as the in-game Advanced Enchanting GUI button, so the launcher and
     *  the mod's own custom UI feel like one product. */
    private static class StyledButton extends JButton {

        private boolean hovered;

        StyledButton(String text) {
            super(text);
            setFont(new Font("Segoe UI", Font.BOLD, 18));
            setForeground(Color.WHITE);
            setFocusPainted(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setPreferredSize(new Dimension(200, 48));
            addMouseListener(new MouseAdapter() {

                @Override
                public void mouseEntered(MouseEvent e) {
                    hovered = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hovered = false;
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            Color top = hovered ? new Color(0x83, 0x47, 0xB5) : new Color(0x5E, 0x32, 0x86);
            Color bottom = hovered ? new Color(0x4E, 0x20, 0x78) : new Color(0x3B, 0x1A, 0x5C);
            Color border = hovered ? GOLD : new Color(0xB8, 0x88, 0xD8);

            g2.setPaint(new java.awt.GradientPaint(0, 0, top, 0, getHeight(), bottom));
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
            g2.setColor(border);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);

            g2.setFont(getFont());
            g2.setColor(getForeground());
            var metrics = g2.getFontMetrics();
            int textX = (getWidth() - metrics.stringWidth(getText())) / 2;
            int textY = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
            g2.drawString(getText(), textX, textY);
            g2.dispose();
        }
    }

    /** Small round dark button with a drawn gear icon (no font glyph dependency) that opens the RAM
     *  settings dialog. */
    /** Renders a custom-drawn PNG (supplied at 2x the display size for crispness) scaled down to fit,
     *  with a brightened variant swapped in on hover - used for Jouer/Paramètres. */
    private static class ImageButton extends JButton {

        private final Image normal;
        private final Image hovered;
        private boolean isHovered;

        ImageButton(Image sourceImage, int displayWidth, int displayHeight) {
            this(sourceImage, brighten((BufferedImage) sourceImage, 1.25F), displayWidth, displayHeight);
        }

        /** Use this overload when a dedicated hover-state artwork is supplied, instead of the
         *  auto-brightened fallback above. */
        ImageButton(Image sourceImage, Image hoverImage, int displayWidth, int displayHeight) {
            this.normal = sourceImage;
            this.hovered = hoverImage;
            setPreferredSize(new Dimension(displayWidth, displayHeight));
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            addMouseListener(new MouseAdapter() {

                @Override
                public void mouseEntered(MouseEvent e) {
                    isHovered = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    isHovered = false;
                    repaint();
                }
            });
        }

        private static BufferedImage brighten(BufferedImage source, float factor) {
            RescaleOp op = new RescaleOp(new float[] { factor, factor, factor, 1F }, new float[4], null);
            BufferedImage brightened = new BufferedImage(
                source.getWidth(),
                source.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
            op.filter(source, brightened);
            return brightened;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            int w = getWidth();
            int h = getHeight();
            if (isHovered) {
                // Brightness alone (or a swapped-in hover image alone) barely reads as "something
                // changed" - a small zoom on top makes the hover state unmistakable without being loud.
                int zoomW = Math.round(w * 1.06F);
                int zoomH = Math.round(h * 1.06F);
                g2.drawImage(hovered, (w - zoomW) / 2, (h - zoomH) / 2, zoomW, zoomH, null);
            } else {
                g2.drawImage(normal, 0, 0, w, h, null);
            }
            g2.dispose();
        }
    }

    /** RAM allocation dialog - a dark rounded panel over a dimmed backdrop, matching the in-game
     *  Advanced Enchanting/Crafting table GUIs' look. */
    private static class SettingsDialog extends JDialog {

        SettingsDialog(JFrame owner, LauncherConfig config) {
            super(owner, "Paramètres", true);
            setUndecorated(true);
            setBackground(new Color(0, 0, 0, 0));

            RoundedPanel content = new RoundedPanel();
            content.setLayout(new GridBagLayout());
            content.setPreferredSize(new Dimension(480, 320));
            setContentPane(content);

            GridBagConstraints gbc = new GridBagConstraints();
            gbc.gridx = 0;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.insets = new Insets(6, 32, 6, 32);

            JLabel titleLabel = new JLabel("PARAMÈTRE");
            titleLabel.setHorizontalAlignment(SwingConstants.CENTER);
            titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 24));
            titleLabel.setForeground(Color.WHITE);
            gbc.gridy = 0;
            gbc.insets = new Insets(30, 32, 14, 32);
            content.add(titleLabel, gbc);

            JLabel descLabel = new JLabel(
                "<html><div style='text-align:center;'>Envie d'allouer plus de mémoire à votre jeu ?<br>Il vous suffit d'utiliser la barre ci-dessous.</div></html>");
            descLabel.setHorizontalAlignment(SwingConstants.CENTER);
            descLabel.setFont(new Font("Segoe UI", Font.PLAIN, 13));
            descLabel.setForeground(new Color(0xB0, 0xB0, 0xB0));
            gbc.gridy = 1;
            gbc.insets = new Insets(0, 32, 26, 32);
            content.add(descLabel, gbc);

            JLabel ramLabel = new JLabel();
            ramLabel.setFont(new Font("Segoe UI", Font.BOLD, 14));
            ramLabel.setForeground(Color.WHITE);
            gbc.gridy = 2;
            gbc.insets = new Insets(0, 32, 8, 32);
            content.add(ramLabel, gbc);

            int initialRamMb = config.getRamMb();
            RamSlider ramSlider = new RamSlider(
                LauncherConfig.MIN_RAM_MB / 512,
                LauncherConfig.MAX_RAM_MB / 512,
                initialRamMb / 512);
            updateRamLabel(ramLabel, ramSlider.getValue() * 512);
            ramSlider.setOnChange(value -> updateRamLabel(ramLabel, value * 512));
            gbc.gridy = 3;
            gbc.insets = new Insets(0, 32, 30, 32);
            content.add(ramSlider, gbc);

            JPanel buttonRow = new JPanel(new java.awt.GridLayout(1, 2, 14, 0));
            buttonRow.setOpaque(false);
            JButton saveButton = new SolidButton("Sauvegarder", new Color(0x2F, 0x6F, 0xE0));
            saveButton.addActionListener(e -> {
                config.setRamMb(ramSlider.getValue() * 512);
                config.save();
                dispose();
            });
            JButton backButton = new SolidButton("Retour", new Color(0xD9, 0x4A, 0x4A));
            backButton.addActionListener(e -> dispose());
            buttonRow.add(saveButton);
            buttonRow.add(backButton);
            gbc.gridy = 4;
            gbc.insets = new Insets(0, 32, 30, 32);
            content.add(buttonRow, gbc);

            pack();
            setShape(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), CORNER_RADIUS, CORNER_RADIUS));
            setLocationRelativeTo(owner);
        }

        private static void updateRamLabel(JLabel label, int ramMb) {
            label.setText(String.format("RAM : %.1f Go", ramMb / 1024.0));
        }
    }

    /** Flat, custom-painted slider (orange fill, dark track, white thumb) - Swing's default JSlider
     *  brings the OS look and feel with it, which clashes badly with the rest of this UI. */
    private static class RamSlider extends JComponent {

        private static final Color TRACK = new Color(0x3A, 0x38, 0x40);
        private static final Color FILL = new Color(0xFF, 0x7A, 0x1F);
        private static final int THUMB_D = 20;
        private static final int TRACK_H = 6;

        private final int min;
        private final int max;
        private int value;
        private java.util.function.IntConsumer onChange;

        RamSlider(int min, int max, int initial) {
            this.min = min;
            this.max = max;
            this.value = initial;
            setPreferredSize(new Dimension(360, THUMB_D + 4));
            MouseAdapter drag = new MouseAdapter() {

                @Override
                public void mousePressed(MouseEvent e) {
                    setValueFromX(e.getX());
                }
            };
            addMouseListener(drag);
            addMouseMotionListener(new MouseMotionAdapter() {

                @Override
                public void mouseDragged(MouseEvent e) {
                    setValueFromX(e.getX());
                }
            });
        }

        void setOnChange(java.util.function.IntConsumer onChange) {
            this.onChange = onChange;
        }

        int getValue() {
            return value;
        }

        private void setValueFromX(int x) {
            int usable = Math.max(1, getWidth() - THUMB_D);
            double ratio = Math.max(0, Math.min(1, (x - THUMB_D / 2.0) / usable));
            value = min + (int) Math.round(ratio * (max - min));
            repaint();
            if (onChange != null) {
                onChange.accept(value);
            }
        }

        private int thumbX() {
            int usable = getWidth() - THUMB_D;
            double ratio = (value - min) / (double) (max - min);
            return (int) Math.round(ratio * usable) + THUMB_D / 2;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int trackY = getHeight() / 2 - TRACK_H / 2;
            int thumbX = thumbX();

            g2.setColor(TRACK);
            g2.fillRoundRect(THUMB_D / 2, trackY, getWidth() - THUMB_D, TRACK_H, TRACK_H, TRACK_H);

            g2.setColor(FILL);
            g2.fillRoundRect(THUMB_D / 2, trackY, Math.max(TRACK_H, thumbX - THUMB_D / 2), TRACK_H, TRACK_H, TRACK_H);

            g2.setColor(Color.WHITE);
            g2.fillOval(thumbX - THUMB_D / 2, getHeight() / 2 - THUMB_D / 2, THUMB_D, THUMB_D);
            g2.setColor(FILL);
            g2.setStroke(new java.awt.BasicStroke(2F));
            g2.drawOval(thumbX - THUMB_D / 2 + 1, getHeight() / 2 - THUMB_D / 2 + 1, THUMB_D - 2, THUMB_D - 2);
            g2.dispose();
        }
    }

    /** Dark rounded card with the same gold/purple gradient border as the main window - used as the
     *  Paramètres dialog's content pane. Must stay non-opaque: an opaque JPanel's own default paint
     *  fills its full rectangular bounds with the L&F's background color *after* paintComponent runs,
     *  which would paint right over the rounded corners with a plain square. */
    private static class RoundedPanel extends JPanel {

        RoundedPanel() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            g2.setColor(PANEL_DARK);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), CORNER_RADIUS, CORNER_RADIUS);

            g2.setStroke(new BasicStroke(3F));
            g2.setPaint(
                new java.awt.GradientPaint(0, 0, GOLD, getWidth(), getHeight(), new Color(0x5E, 0x32, 0x86)));
            g2.draw(
                new RoundRectangle2D.Double(1.5, 1.5, getWidth() - 3, getHeight() - 3, CORNER_RADIUS, CORNER_RADIUS));
            g2.dispose();
        }
    }

    private static class SolidButton extends JButton {

        private final Color base;

        SolidButton(String text, Color base) {
            super(text);
            this.base = base;
            setFont(new Font("Segoe UI", Font.BOLD, 14));
            setForeground(Color.WHITE);
            setFocusPainted(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setPreferredSize(new Dimension(160, 40));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(base);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
            g2.setFont(getFont());
            g2.setColor(getForeground());
            var metrics = g2.getFontMetrics();
            int textX = (getWidth() - metrics.stringWidth(getText())) / 2;
            int textY = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
            g2.drawString(getText(), textX, textY);
            g2.dispose();
        }
    }

    public static void main(String[] args) {
        // A jpackage app-image has no console attached when double-clicked, so an uncaught exception
        // here would otherwise vanish completely - the exact "nothing happens when I open it" symptom
        // this is meant to rule out. Anything that goes wrong from this point on, on any thread, gets a
        // visible dialog (JOptionPane, since it doesn't depend on our own icon/background resources
        // loading successfully) and a line in %APPDATA%/FuryMcLauncher/launcher-error.log.
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> reportFatalError(e));
        try {
            // The window must appear unconditionally and immediately - it must never be gated behind a
            // network call. startUpdateSequence runs off the EDT precisely so that a slow/hung manifest
            // fetch (bad network, DNS hiccup, GitHub blip) can never look like "the launcher does
            // nothing when I open it" - the loading card's status text/Réessayer button carries that
            // instead.
            SwingUtilities.invokeAndWait(() -> {
                FuryMcLauncher launcher = new FuryMcLauncher();
                launcher.setVisible(true);
                // Dev-only escape hatch for iterating on the main card's look without a working
                // network/being blocked by the update check: java -Dfurymc.skipUpdateCheck=true -jar ...
                if (Boolean.getBoolean("furymc.skipUpdateCheck")) {
                    launcher.transitionToMain();
                } else {
                    launcher.startUpdateSequence();
                }
            });
        } catch (Exception e) {
            reportFatalError(e);
        }
    }

    private static void reportFatalError(Throwable e) {
        StringWriter trace = new StringWriter();
        e.printStackTrace(new PrintWriter(trace));
        String message = LocalDateTime.now() + "\n" + trace;

        try {
            Path logFile = new LauncherConfig().getInstallDir()
                .resolveSibling("launcher-error.log");
            Files.createDirectories(logFile.getParent());
            Files.writeString(
                logFile,
                message + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        } catch (Exception logFailure) {
            // Best effort - still show the dialog below even if we can't write the log file.
        }

        JOptionPane.showMessageDialog(
            null,
            "FuryMc Launcher n'a pas pu démarrer :\n\n" + e + "\n\nDétails dans launcher-error.log (%APPDATA%\\FuryMcLauncher\\).",
            "Erreur FuryMc Launcher",
            JOptionPane.ERROR_MESSAGE);
    }
}
