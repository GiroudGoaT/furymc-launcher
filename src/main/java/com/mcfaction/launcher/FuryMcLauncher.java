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
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

/**
 * Entry point + the whole UI. Deliberately a single class - this launcher does one thing (check for
 * updates, download if needed, ask for a pseudo, launch the game) and doesn't need the ceremony of
 * splitting a small Swing screen across many files.
 */
public class FuryMcLauncher extends JFrame {

    // Update this on every published release, alongside the corresponding version.json.
    private static final String MANIFEST_URL = "https://raw.githubusercontent.com/GiroudGoaT/furymc-launcher/main/version.json";

    private static final int WINDOW_WIDTH = 960;
    private static final int WINDOW_HEIGHT = 540;

    private final LauncherConfig config = new LauncherConfig();
    private final UpdateManager updateManager = new UpdateManager();
    private final GameLauncher gameLauncher = new GameLauncher();

    private JTextField usernameField;
    private JButton playButton;
    private JLabel statusLabel;
    private JProgressBar progressBar;

    public FuryMcLauncher() {
        super("FuryMc Launcher");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setResizable(false);
        setIconImage(loadImage("/icon.png"));

        BackgroundPanel content = new BackgroundPanel(loadImage("/background.png"));
        content.setLayout(new GridBagLayout());
        setContentPane(content);

        content.add(buildOverlay(), new GridBagConstraints());

        setSize(WINDOW_WIDTH, WINDOW_HEIGHT);
        setLocationRelativeTo(null);
    }

    private JPanel buildOverlay() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 0, 6, 0);
        gbc.gridx = 0;

        usernameField = new JTextField(config.getUsername(), 16);
        usernameField.setHorizontalAlignment(JTextField.CENTER);
        usernameField.setFont(new Font("Segoe UI", Font.PLAIN, 16));
        usernameField.setMaximumSize(new Dimension(220, 32));
        usernameField.setToolTipText("Pseudo");
        gbc.gridy = 0;
        panel.add(usernameField, gbc);

        playButton = new StyledButton("Jouer");
        playButton.addActionListener(e -> onPlay());
        gbc.gridy = 1;
        panel.add(playButton, gbc);

        statusLabel = new JLabel(" ");
        statusLabel.setForeground(Color.WHITE);
        statusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        gbc.gridy = 2;
        panel.add(statusLabel, gbc);

        progressBar = new JProgressBar(0, 100);
        progressBar.setVisible(false);
        progressBar.setPreferredSize(new Dimension(260, 14));
        gbc.gridy = 3;
        panel.add(progressBar, gbc);

        return panel;
    }

    private void onPlay() {
        String username = usernameField.getText()
            .trim();
        if (username.isEmpty() || username.length() > 16) {
            statusLabel.setText("Pseudo invalide (1-16 caractères)");
            return;
        }
        config.setUsername(username);
        config.save();

        playButton.setEnabled(false);
        progressBar.setVisible(true);
        progressBar.setIndeterminate(true);

        new LaunchWorker(username).execute();
    }

    /** Runs the update-check/download/launch sequence off the UI thread, publishing progress back. */
    private class LaunchWorker extends SwingWorker<Void, ProgressUpdate> {

        private final String username;

        LaunchWorker(String username) {
            this.username = username;
        }

        @Override
        protected Void doInBackground() {
            Path installDir = config.getInstallDir();

            publish(new ProgressUpdate(-1, "Vérification des mises à jour..."));
            VersionManifest manifest = updateManager.fetchManifest(MANIFEST_URL);

            if (updateManager.needsBaseUpdate(installDir, manifest)) {
                publish(new ProgressUpdate(-1, "Téléchargement des fichiers du jeu (première installation)..."));
                updateManager.downloadAndInstallBase(
                    installDir,
                    manifest,
                    (percent, status) -> publish(new ProgressUpdate(percent, status)));
            }

            if (updateManager.needsModUpdate(installDir, manifest)) {
                updateManager.downloadAndInstallMod(
                    installDir,
                    manifest,
                    (percent, status) -> publish(new ProgressUpdate(percent, status)));
            }

            publish(new ProgressUpdate(-1, "Lancement du jeu..."));
            gameLauncher.launch(installDir, username, config.getOrCreateUuid());
            return null;
        }

        @Override
        protected void process(java.util.List<ProgressUpdate> chunks) {
            ProgressUpdate latest = chunks.get(chunks.size() - 1);
            statusLabel.setText(latest.status());
            if (latest.percent() < 0) {
                progressBar.setIndeterminate(true);
            } else {
                progressBar.setIndeterminate(false);
                progressBar.setValue(latest.percent());
            }
        }

        @Override
        protected void done() {
            try {
                get();
                // The game runs as its own detached process (see GameLauncher#launch) - it doesn't need
                // this window anymore, so close it instead of leaving it sitting behind the game.
                dispose();
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                statusLabel.setText("Erreur : " + cause.getMessage());
                progressBar.setVisible(false);
                playButton.setEnabled(true);
            }
        }
    }

    private record ProgressUpdate(int percent, String status) {
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
            Color border = hovered ? new Color(0xF0, 0xC8, 0x78) : new Color(0xB8, 0x88, 0xD8);

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

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new FuryMcLauncher().setVisible(true));
    }
}
