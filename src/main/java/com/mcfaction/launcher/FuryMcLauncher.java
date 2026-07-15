package com.mcfaction.launcher;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;

import javax.imageio.ImageIO;
import javax.swing.JOptionPane;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

/**
 * Entry point + the whole UI. Deliberately a single class - this launcher does one thing (check for
 * updates, download if needed, ask for a pseudo, launch the game) and doesn't need the ceremony of
 * splitting a small Swing screen across many files.
 *
 * <p>
 * Two cards on a CardLayout: {@link #CARD_LOADING} (shown first - runs the self-update check, then the
 * base/mod update-and-download) and {@link #CARD_MAIN} (shown once that's done - username, Jouer,
 * Param&egrave;tres). By the time the player can click Jouer, the install is already up to date, so
 * that button just launches the game directly.
 */
public class FuryMcLauncher extends JFrame {

    // Update this on every published release, alongside the corresponding version.json.
    private static final String MANIFEST_URL = "https://raw.githubusercontent.com/GiroudGoaT/furymc-launcher/main/version.json";

    // Bump this alongside the -PlauncherVersion passed to the packageExe Gradle task, and
    // version.json's launcherVersion/launcherJarUrl/launcherJarSha256, whenever the launcher's own code
    // changes (not game content - that's MANIFEST_URL's version/modUrl, unrelated to this). See
    // SelfUpdater: this is the only place that needs a manual "reinstall the .exe" step ever again.
    private static final String LAUNCHER_VERSION = "1.2.0";

    private static final int WINDOW_WIDTH = 1100;
    private static final int WINDOW_HEIGHT = 620;

    private static final String CARD_LOADING = "loading";
    private static final String CARD_MAIN = "main";

    private static final Color GOLD = new Color(0xF0, 0xC8, 0x78);

    private final LauncherConfig config = new LauncherConfig();
    private final UpdateManager updateManager = new UpdateManager();
    private final GameLauncher gameLauncher = new GameLauncher();

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cardHost = new JPanel(cardLayout);

    private JLabel loadingStatusLabel;
    private JButton retryButton;

    private JTextField usernameField;
    private JButton playButton;
    private JLabel mainStatusLabel;

    public FuryMcLauncher() {
        super("FuryMc Launcher");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setResizable(false);
        setIconImage(loadImage("/icon.png"));

        BackgroundPanel content = new BackgroundPanel(loadImage("/background.png"));
        content.setLayout(new BorderLayout());
        setContentPane(content);

        cardHost.setOpaque(false);
        cardHost.add(buildLoadingCard(), CARD_LOADING);
        cardHost.add(buildMainCard(), CARD_MAIN);
        content.add(cardHost, BorderLayout.CENTER);
        cardLayout.show(cardHost, CARD_LOADING);

        setSize(WINDOW_WIDTH, WINDOW_HEIGHT);
        setLocationRelativeTo(null);
    }

    private JPanel buildLoadingCard() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;

        JLabel titleLabel = new JLabel("FuryMc");
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 72));
        titleLabel.setForeground(GOLD);
        gbc.gridy = 0;
        gbc.insets = new Insets(0, 0, 24, 0);
        panel.add(titleLabel, gbc);

        loadingStatusLabel = new JLabel("Recherche de mise à jour...");
        loadingStatusLabel.setForeground(Color.WHITE);
        loadingStatusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 15));
        gbc.gridy = 1;
        gbc.insets = new Insets(0, 0, 16, 0);
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

        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 20, 20));
        topPanel.setOpaque(false);
        usernameField = new JTextField(config.getUsername(), 12);
        usernameField.setHorizontalAlignment(JTextField.CENTER);
        usernameField.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        usernameField.setToolTipText("Pseudo");
        topPanel.add(usernameField);
        panel.add(topPanel, BorderLayout.NORTH);

        JPanel centerPanel = new JPanel(new GridBagLayout());
        centerPanel.setOpaque(false);
        JLabel sloganLabel = new JLabel("Si tu veux la paix, prépare la guerre");
        sloganLabel.setFont(new Font("Segoe UI", Font.BOLD, 26));
        sloganLabel.setForeground(Color.WHITE);
        centerPanel.add(sloganLabel, new GridBagConstraints());
        panel.add(centerPanel, BorderLayout.CENTER);

        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 20));
        bottomPanel.setOpaque(false);
        mainStatusLabel = new JLabel(" ");
        mainStatusLabel.setForeground(Color.WHITE);
        mainStatusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        playButton = new StyledButton("Jouer");
        playButton.addActionListener(e -> onPlay());
        JButton settingsButton = new GearButton();
        settingsButton.addActionListener(e -> new SettingsDialog(this, config).setVisible(true));
        bottomPanel.add(mainStatusLabel);
        bottomPanel.add(playButton);
        bottomPanel.add(settingsButton);
        panel.add(bottomPanel, BorderLayout.SOUTH);

        return panel;
    }

    private void onPlay() {
        String username = usernameField.getText()
            .trim();
        if (username.isEmpty() || username.length() > 16) {
            mainStatusLabel.setText("Pseudo invalide (1-16 caractères)");
            return;
        }
        config.setUsername(username);
        config.save();

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
     *  update-and-download - the loading card stays up for both, so by the time the player sees the
     *  main card and can click Jouer, everything is already current. */
    private void startUpdateSequence() {
        retryButton.setVisible(false);
        loadingStatusLabel.setText("Recherche de mise à jour...");

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
            protected void process(java.util.List<String> chunks) {
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
                    cardLayout.show(cardHost, CARD_MAIN);
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    loadingStatusLabel.setText("Erreur : " + cause.getMessage());
                    retryButton.setVisible(true);
                }
            }
        }.execute();
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

    /** Paints the FuryMc background image scaled to fill the window. */
    private static class BackgroundPanel extends JPanel {

        private final Image background;

        BackgroundPanel(Image background) {
            this.background = background;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(background, 0, 0, getWidth(), getHeight(), null);
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
    private static class GearButton extends JButton {

        private boolean hovered;

        GearButton() {
            setFocusPainted(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setPreferredSize(new Dimension(48, 48));
            setToolTipText("Paramètres");
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

            int size = Math.min(getWidth(), getHeight());
            g2.setColor(hovered ? new Color(0x3B, 0x1A, 0x5C) : new Color(0x20, 0x14, 0x2C, 210));
            g2.fillOval(0, 0, size - 1, size - 1);

            int cx = size / 2;
            int cy = size / 2;
            int teeth = 8;
            int outerR = size / 2 - 6;
            int innerR = outerR - 5;
            g2.setColor(hovered ? GOLD : Color.WHITE);
            for (int i = 0; i < teeth; i++) {
                double angle = i * (2 * Math.PI / teeth);
                int x1 = cx + (int) (Math.cos(angle) * outerR);
                int y1 = cy + (int) (Math.sin(angle) * outerR);
                g2.fillOval(x1 - 2, y1 - 2, 4, 4);
            }
            g2.drawOval(cx - innerR, cy - innerR, innerR * 2, innerR * 2);
            g2.fillOval(cx - 4, cy - 4, 8, 8);
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
            content.setPreferredSize(new Dimension(440, 300));
            setContentPane(content);

            GridBagConstraints gbc = new GridBagConstraints();
            gbc.gridx = 0;
            gbc.insets = new Insets(6, 20, 6, 20);

            JLabel titleLabel = new JLabel("PARAMÈTRE");
            titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 22));
            titleLabel.setForeground(Color.WHITE);
            gbc.gridy = 0;
            gbc.insets = new Insets(24, 20, 12, 20);
            content.add(titleLabel, gbc);

            JLabel descLabel = new JLabel(
                "<html><div style='text-align:center;width:340px;'>Envie d'allouer plus de mémoire à votre jeu ?<br>Il vous suffit d'utiliser la barre ci-dessous.</div></html>");
            descLabel.setFont(new Font("Segoe UI", Font.PLAIN, 13));
            descLabel.setForeground(new Color(0xCC, 0xCC, 0xCC));
            gbc.gridy = 1;
            gbc.insets = new Insets(0, 20, 20, 20);
            content.add(descLabel, gbc);

            JLabel ramLabel = new JLabel();
            ramLabel.setFont(new Font("Segoe UI", Font.BOLD, 14));
            ramLabel.setForeground(Color.WHITE);
            gbc.gridy = 2;
            gbc.insets = new Insets(0, 20, 6, 20);
            content.add(ramLabel, gbc);

            int initialRamMb = config.getRamMb();
            JSlider ramSlider = new JSlider(
                LauncherConfig.MIN_RAM_MB / 512,
                LauncherConfig.MAX_RAM_MB / 512,
                initialRamMb / 512);
            ramSlider.setOpaque(false);
            updateRamLabel(ramLabel, ramSlider.getValue() * 512);
            ramSlider.addChangeListener(e -> updateRamLabel(ramLabel, ramSlider.getValue() * 512));
            gbc.gridy = 3;
            gbc.insets = new Insets(0, 20, 24, 20);
            content.add(ramSlider, gbc);

            JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 0));
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
            gbc.insets = new Insets(0, 20, 24, 20);
            content.add(buttonRow, gbc);

            pack();
            setLocationRelativeTo(owner);
        }

        private static void updateRamLabel(JLabel label, int ramMb) {
            label.setText(String.format("RAM : %.1f Go", ramMb / 1024.0));
        }
    }

    private static class RoundedPanel extends JPanel {

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(0x18, 0x12, 0x1E, 235));
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 22, 22);
            g2.dispose();
            super.paintComponent(g);
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
                launcher.startUpdateSequence();
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
