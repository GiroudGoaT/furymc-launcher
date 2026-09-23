package com.mcfaction.launcher;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.LinearGradientPaint;
import java.awt.Point;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.WindowEvent;
import java.awt.BasicStroke;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
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
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextArea;
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
 * Two cards on a CardLayout: {@link #CARD_LOADING} (small window, shown first - only checks whether the
 * launcher app itself is outdated, see {@link #startUpdateSequence()}) and {@link #CARD_MAIN} (the frame
 * is resized up to {@link #MAIN_SIZE} at this point - pseudo, Jouer, Param&egrave;tres). The game's own
 * files (base install + mod updates) are deliberately NOT checked here - that happens on demand when the
 * player clicks Jouer (see {@link #onPlay()}), same as most other server launchers: open fast, only pay
 * the download cost when actually starting a game.
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
    //
    // 1.4.0: GameLauncher now launches the standalone MCP client (net.minecraft.client.main.Main)
    // instead of the old Forge/LaunchWrapper+FMLTweaker path - see GameLauncher.java's class comment.
    //
    // This was left stuck at "1.4.0" for a long time while version.json's launcherVersion moved on
    // (1.4.4) - since SelfUpdater compares the two unconditionally on every startup, that mismatch made
    // it attempt the self-update jar-swap-and-relaunch dance on literally every single launch, not just
    // once after an actual update. Bump this alongside launcherVersion in version.json from now on.
    private static final String LAUNCHER_VERSION = "1.4.15";

    private static final Dimension LOADING_SIZE = new Dimension(420, 580);
    private static final Dimension MAIN_SIZE = new Dimension(1100, 620);
    private static final int CORNER_RADIUS = 22;

    // However fast the (now much lighter - just a manifest fetch, no game-file download) launcher
    // self-update check finishes, the loading screen stays up at least this long - purely cosmetic (the
    // player asked for a deliberate splash pause here instead of an instant flash between window sizes,
    // see Timer usage in startUpdateSequence).
    private static final int MIN_LOADING_DISPLAY_MS = 5_000;

    private static final String CARD_LOADING = "loading";
    private static final String CARD_MAIN = "main";

    private static final Color GOLD = new Color(0xF0, 0xC8, 0x78);
    private static final Color GOLD_DIM = new Color(0xC9, 0xA1, 0x5F);
    private static final Color PURPLE_DEEP = new Color(0x5E, 0x32, 0x86);
    private static final Color PURPLE_DARK = new Color(0x3B, 0x1A, 0x5C);
    private static final Color PANEL_DARK = new Color(0x28, 0x27, 0x2B, 245);
    private static final Color SIDEBAR_TOP = new Color(0x1C, 0x1B, 0x21);
    private static final Color SIDEBAR_BOTTOM = new Color(0x16, 0x15, 0x1A);
    private static final Color INK_DIM = new Color(0xA9, 0xA3, 0xB8);

    // Sidebar column of the main card - fixed pixel widths since the window itself is non-resizable
    // (see MAIN_SIZE), so there's no need for percentage-based/stretchy sizing here.
    private static final int SIDEBAR_WIDTH = 400;
    private static final int SIDEBAR_PAD_H = 28;
    private static final int SIDEBAR_CONTENT_WIDTH = SIDEBAR_WIDTH - 2 * SIDEBAR_PAD_H;

    // Same reasoning for the art panel on the right (see buildArtPanel's EmptyBorder(22, 30, 26, 30)) -
    // used to give TipCard's description JTextArea a known, correct wrap width up front instead of
    // guessing a fixed HTML <div> width that didn't match the real available space (see TipCard).
    private static final int ART_CONTENT_WIDTH = MAIN_SIZE.width - SIDEBAR_WIDTH - 60;
    private static final int TIP_ICON_WIDTH = 46;
    private static final int TIP_ICON_GAP = 14;
    private static final int TIP_CARD_PAD_H = 32;
    private static final int TIP_TEXT_WIDTH = ART_CONTENT_WIDTH - TIP_CARD_PAD_H - TIP_ICON_WIDTH - TIP_ICON_GAP;

    private final LauncherConfig config = new LauncherConfig();
    private final UpdateManager updateManager = new UpdateManager();
    private final GameLauncher gameLauncher = new GameLauncher();

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cardHost = new JPanel(cardLayout);

    private JLabel loadingStatusLabel;
    private JButton retryButton;

    private JButton playButton;
    private JTextField pseudoField;
    private JLabel ramValueLabel;
    private JLabel mainStatusLabel;
    private GameProgressBar progressBar;
    private RootPanel content;

    private MusicPlayer musicPlayer;
    private SoundButton soundButton;
    private VolumeBar volumeBar;
    private float lastNonZeroVolume = 0.7F;

    public FuryMcLauncher() {
        super("FuryMc");
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

        // Painted on the glass pane (renders above every other component, including the sidebar's own
        // opaque background) rather than as part of RootPanel's paintComponent - the border used to be
        // drawn there, UNDER the sidebar, which is itself opaque and fully covers that edge of the
        // window, hiding the border along the whole left side (see player feedback screenshot).
        BorderOverlay borderOverlay = new BorderOverlay();
        setGlassPane(borderOverlay);
        borderOverlay.setVisible(true);

        setSize(LOADING_SIZE);
        setLocationRelativeTo(null);
        applyRoundedShape();
    }

    /** No native title bar (see setUndecorated above) - just a minimize and a close glyph, flat and
     *  borderless so they blend into the background instead of reading as their own button. Only on the
     *  main card - the loading card shows none at all. */
    private JPanel buildWindowControls() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 1));
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

    /** Two-panel layout (compact sidebar / editorial art panel) matching the reference DA approved by
     *  the player - see the mockup artifact this was built from. Replaces the previous single centered
     *  panel with logo+slogan overlaying the whole background and controls floating over it. */
    private JPanel buildMainCard() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        panel.add(buildSidebar(), BorderLayout.WEST);
        panel.add(buildArtPanel(), BorderLayout.CENTER);
        return panel;
    }

    /** GridBagLayout instead of BoxLayout - every row gets gridx=0/weightx=1/fill=HORIZONTAL, which
     *  guarantees each one is stretched to the EXACT same column width (the sidebar's real content
     *  width) regardless of that row's own natural preferred size. BoxLayout's per-child
     *  alignmentX/maximumSize combination used here previously left just enough ambiguity for the field
     *  box, Jouer button and icon row to end up narrower than - and not sharing a center with - the
     *  logo above them (see the player's screenshot feedback); GridBagLayout's column model doesn't
     *  leave room for that kind of drift. */
    private JPanel buildSidebar() {
        SidebarPanel sidebar = new SidebarPanel();
        sidebar.setPreferredSize(new Dimension(SIDEBAR_WIDTH, 10));
        sidebar.setLayout(new GridBagLayout());
        sidebar.setBorder(BorderFactory.createEmptyBorder(26, SIDEBAR_PAD_H, 22, SIDEBAR_PAD_H));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;

        JLabel buildTag = new JLabel("FuryMc · 1.7.10", SwingConstants.CENTER);
        buildTag.setFont(new Font("Segoe UI", Font.BOLD, 10));
        buildTag.setForeground(INK_DIM);
        gbc.gridy = row++;
        gbc.insets = new Insets(0, 0, 28, 0);
        sidebar.add(buildTag, gbc);

        HoverLogo logoLabel = new HoverLogo(loadImage("/logo.png"), (int) (SIDEBAR_CONTENT_WIDTH * 0.8));
        gbc.gridy = row++;
        gbc.insets = new Insets(0, 0, 2, 0);
        sidebar.add(logoLabel, gbc);

        JLabel sloganLabel = new JLabel("Si tu veux la paix, prépare la guerre", SwingConstants.CENTER);
        sloganLabel.setFont(new Font("Segoe UI", Font.ITALIC, 12));
        sloganLabel.setForeground(INK_DIM);
        gbc.gridy = row++;
        gbc.insets = new Insets(0, 0, 30, 0);
        sidebar.add(sloganLabel, gbc);

        JLabel fieldLabel = new JLabel("PSEUDONYME");
        fieldLabel.setFont(new Font("Segoe UI", Font.BOLD, 10));
        fieldLabel.setForeground(GOLD_DIM);
        gbc.gridy = row++;
        gbc.insets = new Insets(0, 0, 8, 0);
        sidebar.add(fieldLabel, gbc);

        gbc.gridy = row++;
        gbc.insets = new Insets(0, 0, 16, 0);
        sidebar.add(buildPseudoField(), gbc);

        playButton = new PlayButton("JOUER");
        playButton.addActionListener(e -> onPlay());
        gbc.gridy = row++;
        gbc.insets = new Insets(0, 0, 14, 0);
        sidebar.add(playButton, gbc);

        gbc.gridy = row++;
        gbc.insets = new Insets(0, 0, 0, 0);
        sidebar.add(buildIconRow(), gbc);

        // Eats all remaining vertical space, pushing the status block below it down to the bottom of
        // the sidebar - the one row that needs to actually grow, so weighty is set only here.
        gbc.gridy = row++;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        sidebar.add(Box.createGlue(), gbc);
        gbc.weighty = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridy = row++;
        gbc.insets = new Insets(0, 0, 0, 0);
        sidebar.add(buildStatusBlock(), gbc);

        return sidebar;
    }

    private JPanel buildPseudoField() {
        PseudoFieldPanel field = new PseudoFieldPanel();
        field.setLayout(new BoxLayout(field, BoxLayout.X_AXIS));
        field.setPreferredSize(new Dimension(SIDEBAR_CONTENT_WIDTH, 44));
        field.setMaximumSize(field.getPreferredSize());
        field.setBorder(BorderFactory.createEmptyBorder(0, 14, 0, 14));

        AvatarIcon avatar = new AvatarIcon();
        field.add(avatar);
        field.add(Box.createHorizontalStrut(10));

        pseudoField = new JTextField(config.getUsername());
        pseudoField.setOpaque(false);
        pseudoField.setBorder(null);
        pseudoField.setForeground(Color.WHITE);
        pseudoField.setCaretColor(GOLD);
        pseudoField.setFont(new Font("Segoe UI", Font.BOLD, 14));
        pseudoField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                savePseudoField();
            }
        });
        pseudoField.addActionListener(e -> {
            savePseudoField();
            playButton.requestFocusInWindow();
        });
        field.add(pseudoField);

        return field;
    }

    /** Validates and persists whatever's currently typed in the pseudo field - called both when focus
     *  leaves the field and when Entrée is pressed. Silently ignores an invalid value rather than
     *  reverting it, since the player is very likely mid-edit (e.g. just cleared the field to retype) -
     *  onPlay() is the real gatekeeper that refuses to launch with an invalid name. */
    private void savePseudoField() {
        String value = pseudoField.getText()
            .trim();
        if (value.isEmpty() || value.length() > 16) {
            return;
        }
        config.setUsername(value);
        config.save();
    }

    private JPanel buildIconRow() {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setOpaque(false);
        row.setPreferredSize(new Dimension(SIDEBAR_CONTENT_WIDTH, 52));
        row.setMaximumSize(row.getPreferredSize());

        SidebarIconButton folderButton = new SidebarIconButton(SidebarIconButton.Glyph.FOLDER);
        folderButton.setToolTipText("Ouvrir le dossier d'installation");
        folderButton.addActionListener(e -> openInstallFolder());
        row.add(folderButton);

        row.add(Box.createHorizontalStrut(8));
        row.add(buildSoundControls());

        row.add(Box.createHorizontalGlue());
        row.add(buildRamReadout());

        return row;
    }

    /** Mute toggle + volume slider - previously lived in their own bottom-left strip under the old
     *  single-panel layout; folded into the icon row here since the new sidebar has no separate bottom
     *  bar of its own. */
    private JPanel buildSoundControls() {
        JPanel holder = new JPanel();
        holder.setLayout(new BoxLayout(holder, BoxLayout.X_AXIS));
        holder.setOpaque(false);

        float initialVolume = config.getMusicVolume();
        if (initialVolume > 0F) {
            lastNonZeroVolume = initialVolume;
        }

        soundButton = new SoundButton();
        soundButton.setMuted(initialVolume <= 0.0001F);
        soundButton.addActionListener(e -> toggleMusicMuted());

        volumeBar = new VolumeBar(initialVolume);
        volumeBar.onDrag = v -> applyMusicVolume(v, false);
        volumeBar.onCommit = v -> applyMusicVolume(v, true);

        holder.add(soundButton);
        holder.add(volumeBar);
        return holder;
    }

    private JPanel buildRamReadout() {
        JPanel readout = new JPanel();
        readout.setLayout(new BoxLayout(readout, BoxLayout.Y_AXIS));
        readout.setOpaque(false);
        readout.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));

        JLabel caption = new JLabel("RAM");
        caption.setFont(new Font("Segoe UI", Font.BOLD, 9));
        caption.setForeground(INK_DIM);
        caption.setAlignmentX(Component.RIGHT_ALIGNMENT);

        ramValueLabel = new JLabel();
        ramValueLabel.setFont(new Font("Segoe UI", Font.BOLD, 14));
        ramValueLabel.setForeground(GOLD);
        ramValueLabel.setAlignmentX(Component.RIGHT_ALIGNMENT);
        refreshRamReadout();

        readout.add(caption);
        readout.add(ramValueLabel);

        readout.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                new SettingsDialog(FuryMcLauncher.this, config).setVisible(true);
                refreshRamReadout();
            }
        });

        return readout;
    }

    private void refreshRamReadout() {
        ramValueLabel.setText(String.format("%.1f GB", config.getRamMb() / 1024.0));
    }

    /** Opens the install directory in the OS file browser - creates it first if this is a brand new
     *  install that hasn't downloaded anything yet, so the folder actually exists to open. */
    private void openInstallFolder() {
        try {
            Path installDir = config.getInstallDir();
            Files.createDirectories(installDir);
            Desktop.getDesktop()
                .open(installDir.toFile());
        } catch (Exception e) {
            mainStatusLabel.setText("Impossible d'ouvrir le dossier : " + e.getMessage());
        }
    }

    /** GridBagLayout here too (see buildSidebar's javadoc for why) - the previous BoxLayout version only
     *  got mainStatusLabel's width/alignment fixed last round, not progressBar's, since progressBar is
     *  invisible by default (see GameProgressBar) and so its misalignment wasn't visible until the
     *  player actually clicked Jouer. Both rows are gridx=0/weightx=1/fill=HORIZONTAL now, so both are
     *  guaranteed to span the exact same width as everything else in the sidebar. */
    private JPanel buildStatusBlock() {
        JPanel block = new JPanel(new GridBagLayout());
        block.setOpaque(false);
        block.setPreferredSize(new Dimension(SIDEBAR_CONTENT_WIDTH, 30));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        mainStatusLabel = new JLabel("Prêt à jouer", SwingConstants.CENTER);
        mainStatusLabel.setForeground(INK_DIM);
        mainStatusLabel.setFont(new Font("Segoe UI", Font.BOLD, 10));
        gbc.gridy = 0;
        gbc.insets = new Insets(0, 0, 8, 0);
        block.add(mainStatusLabel, gbc);

        progressBar = new GameProgressBar();
        progressBar.setPreferredSize(new Dimension(SIDEBAR_CONTENT_WIDTH, 5));
        gbc.gridy = 1;
        gbc.insets = new Insets(0, 0, 0, 0);
        block.add(progressBar, gbc);

        return block;
    }

    private JPanel buildArtPanel() {
        ArtPanel art = new ArtPanel();
        art.setLayout(new BorderLayout());
        art.setBorder(BorderFactory.createEmptyBorder(22, 30, 26, 30));

        art.add(buildArtTopBar(), BorderLayout.NORTH);
        art.add(buildTipsSection(), BorderLayout.CENTER);
        return art;
    }

    private JPanel buildArtTopBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setOpaque(false);
        bar.add(new ServerPill("FURYMC"), BorderLayout.WEST);
        bar.add(buildWindowControls(), BorderLayout.EAST);
        return bar;
    }

    private JPanel buildTipsSection() {
        JPanel section = new JPanel();
        section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
        section.setOpaque(false);
        section.setBorder(BorderFactory.createEmptyBorder(30, 0, 0, 0));

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));

        JLabel title = new JLabel("ASTUCES");
        title.setFont(new Font("Segoe UI", Font.BOLD, 12));
        title.setForeground(INK_DIM);
        header.add(title, BorderLayout.WEST);

        // TODO: wire this to the real Discord invite once we have one - deliberately left without an
        // action listener rather than guessing/hardcoding a placeholder URL that could be wrong.
        header.add(new DiscordPill(), BorderLayout.EAST);

        section.add(header);
        section.add(Box.createVerticalStrut(16));

        section.add(new TipCard(
            TipCard.Glyph.BOX,
            "Les caisses infernales",
            "Récupère des clés sur les mobs et ouvre les caisses réparties sur la carte "
                + "- chaque variante a ses propres récompenses, des plus communes aux plus rares."));
        section.add(Box.createVerticalStrut(14));
        section.add(new TipCard(
            TipCard.Glyph.STAR,
            "Gestion de faction",
            "/f perms te permet de définir précisément qui peut construire, casser ou "
                + "interagir dans le territoire de ta faction, rang par rang."));
        section.add(Box.createVerticalStrut(14));
        section.add(new TipCard(
            TipCard.Glyph.CLOCK,
            "Spawners améliorables",
            "Chaque spawner peut être amélioré en jeu pour augmenter sa vitesse et la "
                + "qualité de ses drops - ouvre /spawners pour voir la progression."));

        return section;
    }

    /** Checks for game-file updates (base install + mod), downloads whatever's missing/outdated, then
     *  launches - all triggered by the Jouer click itself rather than eagerly at startup (see class
     *  javadoc). The status label + progress bar under the button double as the display for both the
     *  update check and any download, then finally the actual game launch. When the install is already
     *  current there's no real download to show progress for, so a brief simulated check runs instead
     *  (see {@link #simulateUpToDateCheck}) - otherwise the button would jump straight to "Lancement du
     *  jeu..." with no visible feedback at all, which reads as broken rather than fast. */
    private void onPlay() {
        String username = pseudoField.getText()
            .trim();
        if (username.isEmpty() || username.length() > 16) {
            mainStatusLabel.setText("Pseudo invalide (1-16 caractères)");
            pseudoField.requestFocusInWindow();
            return;
        }
        config.setUsername(username);
        config.save();

        playButton.setEnabled(false);
        progressBar.setProgress(0);
        progressBar.setVisible(true);
        mainStatusLabel.setText("Vérification des mises à jour...");

        new SwingWorker<Void, ProgressUpdate>() {

            @Override
            protected Void doInBackground() throws Exception {
                Path installDir = config.getInstallDir();
                VersionManifest manifest = updateManager.fetchManifest(MANIFEST_URL);
                boolean updated = false;

                if (updateManager.needsBaseUpdate(installDir, manifest)) {
                    updated = true;
                    publish(new ProgressUpdate(0, "Téléchargement des fichiers du jeu (première installation)..."));
                    updateManager.downloadAndInstallBase(
                        installDir,
                        manifest,
                        (percent, status) -> publish(new ProgressUpdate(percent, status)));
                }
                if (updateManager.needsModUpdate(installDir, manifest)) {
                    updated = true;
                    publish(new ProgressUpdate(0, "Téléchargement de la mise à jour..."));
                    updateManager.downloadAndInstallMod(
                        installDir,
                        manifest,
                        (percent, status) -> publish(new ProgressUpdate(percent, status)));
                }
                if (!updated) {
                    simulateUpToDateCheck(this::publish);
                }

                publish(new ProgressUpdate(100, "Lancement du jeu..."));
                // GameLauncher#launch calls cleanStaleLayout itself right before building the process -
                // no need to also call it here.
                gameLauncher.launch(installDir, username, config.getOrCreateUuid(), config.getRamMb());
                return null;
            }

            @Override
            protected void process(List<ProgressUpdate> chunks) {
                ProgressUpdate last = chunks.get(chunks.size() - 1);
                mainStatusLabel.setText(last.status);
                if (last.percent >= 0) {
                    progressBar.setProgress(last.percent);
                }
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
                    progressBar.setVisible(false);
                    playButton.setEnabled(true);
                }
            }
        }.execute();
    }

    /** Nothing to actually download when the install is already current - this fakes a short, visibly
     *  staged check (rather than jumping straight from "Vérification..." to "Lancement...") so Jouer
     *  never looks like it did nothing. Runs on the SwingWorker's background thread (see its only caller),
     *  so the Thread.sleep here doesn't block the EDT. */
    private static void simulateUpToDateCheck(java.util.function.Consumer<ProgressUpdate> publish)
        throws InterruptedException {
        String[] steps = {"Vérification des fichiers du jeu...", "Contrôle de l'intégrité...", "Jeu à jour !"};
        int[] percents = {35, 75, 100};
        for (int i = 0; i < steps.length; i++) {
            publish.accept(new ProgressUpdate(percents[i], steps[i]));
            Thread.sleep(600);
        }
    }

    /** One (percent, status) tick published from onPlay's SwingWorker - percent is -1 for steps whose
     *  size isn't known ahead of time (see {@link ProgressListener}), in which case the bar just holds
     *  its last value while the status text still updates. */
    private static final class ProgressUpdate {

        final int percent;
        final String status;

        ProgressUpdate(int percent, String status) {
            this.percent = percent;
            this.status = status;
        }
    }

    /** Runs once at startup: only checks whether the launcher app itself is outdated and, if so,
     *  downloads + applies the update and relaunches (see SelfUpdater) - it deliberately does NOT touch
     *  the game's own files here any more, see class javadoc. The loading card stays up for at least
     *  MIN_LOADING_DISPLAY_MS purely so the transition to the main card doesn't flash instantly. */
    private void startUpdateSequence() {
        retryButton.setVisible(false);
        loadingStatusLabel.setText("Vérification du launcher...");
        long startedAt = System.currentTimeMillis();

        new SwingWorker<Boolean, Void>() {

            @Override
            protected Boolean doInBackground() throws Exception {
                VersionManifest manifest = updateManager.fetchManifest(MANIFEST_URL);
                if (new SelfUpdater().checkAndApply(LAUNCHER_VERSION, manifest)) {
                    // A relaunch is already in flight via SelfUpdater's helper script.
                    return null;
                }
                return true;
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
                    Timer timer = new Timer((int) Math.max(0, remaining), e -> beginTransitionToMain());
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
        startBackgroundMusic();
    }

    // How much getOpacity() moves per fade tick - 10 ticks at FADE_TICK_MS each to cross the full
    // 0..1 range, in either direction.
    private static final float FADE_STEP = 0.1F;
    private static final int FADE_TICK_MS = 15;

    /** Softens the loading→main card switch instead of an instant resize+content swap, which reads as an
     *  abrupt flash: shows one last status line, holds it just long enough to read, then fades the whole
     *  window out, swaps to the main card while invisible, and fades back in. */
    private void beginTransitionToMain() {
        loadingStatusLabel.setText("Lancement du launcher...");
        Timer holdTimer = new Timer(500, e -> fadeOutThenSwitch());
        holdTimer.setRepeats(false);
        holdTimer.start();
    }

    private void fadeOutThenSwitch() {
        if (!isWindowTranslucencySupported()) {
            // Some platform/driver combos don't support window translucency at all - fall back to the
            // old instant switch rather than risk an UnsupportedOperationException from setOpacity.
            transitionToMain();
            return;
        }
        Timer fadeOutTimer = new Timer(FADE_TICK_MS, null);
        fadeOutTimer.addActionListener(e -> {
            float opacity = getOpacity() - FADE_STEP;
            if (opacity <= 0F) {
                ((Timer) e.getSource()).stop();
                setOpacity(0F);
                transitionToMain();
                fadeIn();
            } else {
                setOpacity(opacity);
            }
        });
        fadeOutTimer.start();
    }

    private void fadeIn() {
        Timer fadeInTimer = new Timer(FADE_TICK_MS, null);
        fadeInTimer.addActionListener(e -> {
            float opacity = getOpacity() + FADE_STEP;
            if (opacity >= 1F) {
                ((Timer) e.getSource()).stop();
                setOpacity(1F);
            } else {
                setOpacity(opacity);
            }
        });
        fadeInTimer.start();
    }

    private boolean isWindowTranslucencySupported() {
        java.awt.GraphicsConfiguration gc = getGraphicsConfiguration();
        return gc != null && gc.getDevice()
            .isWindowTranslucencySupported(java.awt.GraphicsDevice.WindowTranslucency.TRANSLUCENT);
    }

    /** Loads the whole track into memory once and streams it in small chunks for the lifetime of the
     *  process (see {@link MusicPlayer}) - a javax.sound.sampled.Clip's MASTER_GAIN control only affects
     *  audio not yet handed off to the OS, and with an entire 2-minute track queued at once that made
     *  volume changes audibly lag behind the slider. Streaming in small chunks and applying gain in
     *  software per-chunk keeps that lag down to one chunk's worth of audio. */
    private void startBackgroundMusic() {
        try (AudioInputStream audioIn = AudioSystem.getAudioInputStream(getClass().getResource("/music.wav"))) {
            byte[] pcm = audioIn.readAllBytes();
            musicPlayer = new MusicPlayer(pcm, audioIn.getFormat(), config.getMusicVolume());
            musicPlayer.start();
        } catch (Exception e) {
            // No music is a cosmetic loss, not worth failing the launcher over.
        }
    }

    private void applyMusicVolume(float volume, boolean persist) {
        volume = Math.max(0F, Math.min(1F, volume));
        if (volume > 0F) {
            lastNonZeroVolume = volume;
        }
        if (musicPlayer != null) {
            musicPlayer.setVolume(volume);
        }
        if (soundButton != null) {
            soundButton.setMuted(volume <= 0.0001F);
        }
        if (volumeBar != null) {
            volumeBar.setValue(volume);
        }
        if (persist) {
            config.setMusicVolume(volume);
            config.save();
        }
    }

    private void toggleMusicMuted() {
        float current = config.getMusicVolume();
        float next = current > 0.0001F ? 0F : (lastNonZeroVolume > 0F ? lastNonZeroVolume : 0.7F);
        applyMusicVolume(next, true);
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
            g2.dispose();
        }
    }

    /** Draws the same gradient window-edge stroke RootPanel used to draw itself - moved onto the glass
     *  pane (see constructor) so it paints last, above every other component including opaque ones like
     *  the sidebar, instead of being drawn first and then covered up. contains() always returns false so
     *  this never intercepts a mouse event meant for whatever's underneath - it's paint-only. */
    private static class BorderOverlay extends JComponent {

        BorderOverlay() {
            setOpaque(false);
        }

        @Override
        public boolean contains(int x, int y) {
            return false;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            g2.setStroke(new BasicStroke(3F));
            g2.setPaint(
                new java.awt.GradientPaint(0, 0, GOLD, getWidth(), getHeight(), PURPLE_DEEP));
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

    /** Same flat-glyph treatment as {@link WindowControlButton}, but the glyph itself is Windows' own
     *  volume icon - Segoe Fluent Icons/Segoe MDL2 Assets (both ship with Windows 10/11) map the mute and
     *  full-volume speaker glyphs to U+E74F and U+E995, so no custom-drawn shape or image asset is
     *  needed to match the OS's own icon. */
    private static class SoundButton extends JButton {

        private static final String GLYPH_MUTED = "";
        private static final String GLYPH_ON = "";

        private boolean muted;
        private boolean hovered;

        SoundButton() {
            setFocusPainted(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setPreferredSize(new Dimension(32, 32));
            setMaximumSize(getPreferredSize());
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

        void setMuted(boolean muted) {
            this.muted = muted;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(hovered ? Color.WHITE : new Color(255, 255, 255, 170));

            Font iconFont = new Font("Segoe Fluent Icons", Font.PLAIN, 18);
            if (!iconFont.canDisplay('')) {
                iconFont = new Font("Segoe MDL2 Assets", Font.PLAIN, 18);
            }
            g2.setFont(iconFont);

            String glyph = muted ? GLYPH_MUTED : GLYPH_ON;
            var metrics = g2.getFontMetrics();
            int x = (getWidth() - metrics.stringWidth(glyph)) / 2;
            int y = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
            g2.drawString(glyph, x, y);
            g2.dispose();
        }
    }

    /** Compact draggable level bar next to the sound glyph - a plain mute toggle can only go all the way
     *  on or off, so this adds an actual 0-100% level the player can drag to. onDrag fires continuously
     *  while dragging (live audio feedback, not persisted); onCommit fires once on mouse release (what
     *  actually gets saved), so dragging doesn't hammer the properties file with a write per pixel. */
    private static class VolumeBar extends JComponent {

        private float value;
        java.util.function.Consumer<Float> onDrag;
        java.util.function.Consumer<Float> onCommit;

        VolumeBar(float initialValue) {
            this.value = initialValue;
            setPreferredSize(new Dimension(90, 32));
            setMaximumSize(getPreferredSize());
            setOpaque(false);

            MouseAdapter handler = new MouseAdapter() {

                @Override
                public void mousePressed(MouseEvent e) {
                    updateFromMouse(e.getX(), false);
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    updateFromMouse(e.getX(), false);
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    updateFromMouse(e.getX(), true);
                }
            };
            addMouseListener(handler);
            addMouseMotionListener(handler);
        }

        private void updateFromMouse(int x, boolean commit) {
            value = Math.max(0F, Math.min(1F, x / (float) getWidth()));
            repaint();
            if (commit) {
                if (onCommit != null) {
                    onCommit.accept(value);
                }
            } else if (onDrag != null) {
                onDrag.accept(value);
            }
        }

        void setValue(float value) {
            this.value = Math.max(0F, Math.min(1F, value));
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int width = getWidth();
            int trackHeight = 4;
            int y = getHeight() / 2 - trackHeight / 2;

            g2.setColor(new Color(255, 255, 255, 60));
            g2.fillRoundRect(0, y, width, trackHeight, trackHeight, trackHeight);

            int filledWidth = Math.round(width * value);
            g2.setColor(GOLD);
            g2.fillRoundRect(0, y, Math.max(trackHeight, filledWidth), trackHeight, trackHeight, trackHeight);

            int handleD = 12;
            int handleX = Math.max(0, Math.min(width - handleD, filledWidth - handleD / 2));
            g2.setColor(GOLD);
            g2.fillOval(handleX, getHeight() / 2 - handleD / 2, handleD, handleD);

            g2.dispose();
        }
    }

    /** Thin gold-on-dark progress bar shown under the status label during onPlay's update check/download -
     *  hidden until then (see buildMainCard), so it doesn't clutter the main card the rest of the time. */
    private static class GameProgressBar extends JComponent {

        private int percent;

        GameProgressBar() {
            setPreferredSize(new Dimension(200, 6));
            setOpaque(false);
            setVisible(false);
        }

        void setProgress(int percent) {
            this.percent = Math.max(0, Math.min(100, percent));
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int h = getHeight();
            g2.setColor(new Color(255, 255, 255, 40));
            g2.fillRoundRect(0, 0, getWidth(), h, h, h);

            int filledWidth = Math.max(h, Math.round(getWidth() * (percent / 100F)));
            g2.setColor(GOLD);
            g2.fillRoundRect(0, 0, filledWidth, h, h, h);

            g2.dispose();
        }
    }

    /** Dark panel behind the whole sidebar column - a top-to-bottom gradient plus a faint purple glow
     *  near the top-left (matching the reference DA), and a 1px gold hairline on the right edge to
     *  separate it from the art panel. Opaque so it fully covers whatever RootPanel's shared background
     *  image would otherwise show through here (see class javadoc on why there's a single shared image
     *  for the whole window rather than per-panel art). */
    private static class SidebarPanel extends JPanel {

        SidebarPanel() {
            setOpaque(true);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();
            g2.setPaint(new LinearGradientPaint(0, 0, 0, h, new float[] {0F, 1F}, new Color[] {SIDEBAR_TOP, SIDEBAR_BOTTOM}));
            g2.fillRect(0, 0, w, h);

            g2.setPaint(
                new RadialGradientPaint(
                    w * 0.2F,
                    0F,
                    w * 0.6F,
                    new float[] {0F, 1F},
                    new Color[] {new Color(PURPLE_DEEP.getRed(), PURPLE_DEEP.getGreen(), PURPLE_DEEP.getBlue(), 70), new Color(0, 0, 0, 0)}));
            g2.fillRect(0, 0, w, h);

            g2.setColor(new Color(GOLD.getRed(), GOLD.getGreen(), GOLD.getBlue(), 46));
            g2.drawLine(w - 1, 0, w - 1, h);

            g2.dispose();
        }
    }

    /** The sidebar logo - zooms in slightly while the mouse is over it, same "draw the image slightly
     *  larger than the component's own bounds on hover" technique used elsewhere in this file (see
     *  ImageButton's hover zoom in an earlier revision). Drawn at a fixed base size so the surrounding
     *  GridBagLayout row never reflows on hover; only the painted image grows. */
    private static class HoverLogo extends JComponent {

        private static final float MAX_SCALE = 1.2F;
        private static final float SCALE_STEP = 0.025F;
        private static final int TICK_MS = 15;

        private final Image source;
        private final int baseWidth;
        private final int baseHeight;
        private float scale = 1F;
        private float targetScale = 1F;
        private Timer animTimer;

        HoverLogo(Image source, int baseWidth) {
            this.source = source;
            this.baseWidth = baseWidth;
            this.baseHeight = Math.round(baseWidth * (source.getHeight(null) / (float) source.getWidth(null)));

            // The component's own size reserves room for the FULL zoomed footprint (not just the resting
            // size) - Swing clips each child's painting to its own bounds, so without this margin the
            // zoomed-in image would get cropped right at the component's edge instead of growing freely
            // (see player feedback). The resting-state image is simply centered within that padded box.
            setPreferredSize(new Dimension(Math.round(this.baseWidth * MAX_SCALE), Math.round(this.baseHeight * MAX_SCALE)));
            setOpaque(false);
            setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {

                @Override
                public void mouseEntered(MouseEvent e) {
                    animateTo(MAX_SCALE);
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    animateTo(1F);
                }
            });
        }

        /** Eases scale towards the target a little each tick instead of jumping straight there - a
         *  smooth grow/shrink ("fondu") rather than an instant, clipped-looking snap. */
        private void animateTo(float target) {
            targetScale = target;
            if (animTimer != null && animTimer.isRunning()) {
                return;
            }
            animTimer = new Timer(TICK_MS, null);
            animTimer.addActionListener(e -> {
                if (Math.abs(scale - targetScale) <= SCALE_STEP) {
                    scale = targetScale;
                    ((Timer) e.getSource()).stop();
                } else {
                    scale += scale < targetScale ? SCALE_STEP : -SCALE_STEP;
                }
                repaint();
            });
            animTimer.start();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            // Drawn relative to the component's ACTUAL current bounds (not the base size) and always
            // re-centered - GridBagLayout stretches this row horizontally like every other sidebar row
            // (see buildSidebar), so assuming the component stayed at its base width previously left the
            // logo drawn flush-left instead of centered (see player feedback screenshot).
            int drawW = Math.round(baseWidth * scale);
            int drawH = Math.round(baseHeight * scale);
            int x = (getWidth() - drawW) / 2;
            int y = (getHeight() - drawH) / 2;
            g2.drawImage(source, x, y, drawW, drawH, null);

            g2.dispose();
        }
    }

    /** Rounded translucent box behind the avatar icon + pseudo text field. */
    private static class PseudoFieldPanel extends JPanel {

        PseudoFieldPanel() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(255, 255, 255, 13));
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
            g2.setColor(new Color(255, 255, 255, 20));
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Small circular person-silhouette glyph next to the pseudo field - stands in for a real skin-based
     *  avatar (offline-mode UUIDs don't map to a real Mojang skin, same reasoning as the old AvatarLabel
     *  it replaces). */
    private static class AvatarIcon extends JComponent {

        AvatarIcon() {
            setPreferredSize(new Dimension(27, 27));
            setMaximumSize(getPreferredSize());
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int d = Math.min(getWidth(), getHeight());
            g2.setPaint(new LinearGradientPaint(0, 0, d * 0.7F, d * 0.7F, new float[] {0F, 1F}, new Color[] {PURPLE_DEEP, PURPLE_DARK}));
            g2.fillOval(0, 0, d - 1, d - 1);
            g2.setColor(new Color(GOLD.getRed(), GOLD.getGreen(), GOLD.getBlue(), 130));
            g2.drawOval(0, 0, d - 1, d - 1);

            g2.setColor(GOLD);
            g2.setStroke(new BasicStroke(1.6F));
            float headD = d * 0.32F;
            g2.draw(new Ellipse2D.Float(d / 2F - headD / 2F, d * 0.24F, headD, headD));
            java.awt.geom.Arc2D.Float body =
                new java.awt.geom.Arc2D.Float(d * 0.18F, d * 0.52F, d * 0.64F, d * 0.64F, 20, 140, java.awt.geom.Arc2D.OPEN);
            g2.draw(body);

            g2.dispose();
        }
    }

    /** The Jouer button, redesigned to match the reference DA instead of the old flat button_play.png
     *  artwork - a dark violet gradient fill with a gold border/glow, since a light gold surface (closer
     *  to the reference's own button) reads poorly with white text and would need per-pixel text-shadow
     *  tricks to stay legible; this keeps the same gold+violet vocabulary while staying simple to paint. */
    private static class PlayButton extends JButton {

        private boolean hovered;

        PlayButton(String text) {
            super(text);
            setFont(new Font("Segoe UI", Font.BOLD, 15));
            setForeground(Color.WHITE);
            setFocusPainted(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setPreferredSize(new Dimension(SIDEBAR_CONTENT_WIDTH, 52));
            setMaximumSize(getPreferredSize());
            setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
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

            int w = getWidth();
            int h = getHeight();
            g2.setPaint(new LinearGradientPaint(0, 0, w * 0.7F, h, new float[] {0F, 1F}, new Color[] {PURPLE_DEEP, PURPLE_DARK}));
            g2.fillRoundRect(0, 0, w - 1, h - 1, 12, 12);
            g2.setColor(hovered ? GOLD : new Color(GOLD.getRed(), GOLD.getGreen(), GOLD.getBlue(), 140));
            g2.setStroke(new BasicStroke(1.3F));
            g2.drawRoundRect(0, 0, w - 1, h - 1, 12, 12);

            java.awt.geom.Path2D.Float triangle = new java.awt.geom.Path2D.Float();
            float tx = w / 2F - 42F;
            float ty = h / 2F;
            triangle.moveTo(tx, ty - 7);
            triangle.lineTo(tx, ty + 7);
            triangle.lineTo(tx + 11, ty);
            triangle.closePath();
            g2.setColor(GOLD);
            g2.fill(triangle);

            g2.setFont(getFont());
            g2.setColor(getForeground());
            var metrics = g2.getFontMetrics();
            int textX = w / 2 - metrics.stringWidth(getText()) / 2 + 10;
            int textY = (h - metrics.getHeight()) / 2 + metrics.getAscent();
            g2.drawString(getText(), textX, textY);

            g2.dispose();
        }
    }

    /** Flat icon button used in the sidebar's icon row (currently just the "open install folder"
     *  shortcut) - same visual language as {@link WindowControlButton}/{@link SoundButton} (a hand-drawn
     *  glyph on a subtle rounded tile) rather than another bundled image asset. */
    private static class SidebarIconButton extends JButton {

        enum Glyph { FOLDER }

        private final Glyph glyph;
        private boolean hovered;

        SidebarIconButton(Glyph glyph) {
            this.glyph = glyph;
            setPreferredSize(new Dimension(52, 52));
            // JButton's default maximumSize is effectively unbounded, so without capping it explicitly
            // BoxLayout.X_AXIS (in buildIconRow) was splitting the row's leftover width between this
            // button AND the trailing glue instead of giving it all to the glue - stretching the button
            // (and its drawn folder shape) noticeably wider than tall (see player feedback: "étiré").
            setMaximumSize(getPreferredSize());
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
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

            int w = getWidth();
            int h = getHeight();
            g2.setColor(new Color(255, 255, 255, hovered ? 20 : 13));
            g2.fillRoundRect(0, 0, w - 1, h - 1, 10, 10);
            g2.setColor(new Color(255, 255, 255, 20));
            g2.drawRoundRect(0, 0, w - 1, h - 1, 10, 10);

            if (glyph == Glyph.FOLDER) {
                // Same visual language as PlayButton - a solid violet/gold shape rather than a thin
                // outline glyph, so this reads as belonging to the same DA (see player feedback asking
                // for "a real folder icon, same colours as the Jouer button").
                float left = w * 0.08F;
                float right = w * 0.92F;
                float bodyTop = h * 0.3F;
                float bottom = h * 0.84F;
                float tabTop = h * 0.16F;

                java.awt.geom.Path2D.Float folder = new java.awt.geom.Path2D.Float();
                folder.moveTo(left, tabTop);
                folder.lineTo(left + (right - left) * 0.28F, tabTop);
                folder.lineTo(left + (right - left) * 0.42F, bodyTop);
                folder.lineTo(right, bodyTop);
                folder.lineTo(right, bottom);
                folder.lineTo(left, bottom);
                folder.closePath();

                g2.setPaint(
                    new LinearGradientPaint(
                        0,
                        bodyTop,
                        0,
                        bottom,
                        new float[] {0F, 1F},
                        new Color[] {hovered ? GOLD_DIM : PURPLE_DEEP, PURPLE_DARK}));
                g2.fill(folder);
                g2.setColor(hovered ? GOLD : new Color(GOLD.getRed(), GOLD.getGreen(), GOLD.getBlue(), 150));
                g2.setStroke(new BasicStroke(1.4F));
                g2.draw(folder);
            }

            g2.dispose();
        }
    }

    /** Small rounded pill in the art panel's top bar - a live-status dot plus the server name, standing
     *  in for a server switcher since FuryMc only has the one server. */
    private static class ServerPill extends JComponent {

        private static final Color DOT_COLOR = new Color(0x6F, 0xE0, 0x8A);

        private final String text;
        private float dotAlpha = 1F;
        private boolean fadingOut = true;

        ServerPill(String text) {
            this.text = text;
            setFont(new Font("Segoe UI", Font.BOLD, 12));
            setOpaque(false);

            // Gentle breathing pulse rather than a hard on/off blink - a live-status indicator, not an
            // alert, so it shouldn't read as urgent.
            Timer pulse = new Timer(40, e -> {
                dotAlpha += fadingOut ? -0.03F : 0.03F;
                if (dotAlpha <= 0.35F) {
                    dotAlpha = 0.35F;
                    fadingOut = false;
                } else if (dotAlpha >= 1F) {
                    dotAlpha = 1F;
                    fadingOut = true;
                }
                repaint();
            });
            pulse.start();
        }

        @Override
        public Dimension getPreferredSize() {
            Graphics2D g2 = (Graphics2D) getGraphics();
            int textWidth = g2 != null ? g2.getFontMetrics(getFont()).stringWidth(text)
                : text.length() * 8;
            return new Dimension(textWidth + 44, 30);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();
            g2.setColor(new Color(10, 9, 13, 165));
            g2.fillRoundRect(0, 0, w - 1, h - 1, h, h);
            g2.setColor(new Color(GOLD.getRed(), GOLD.getGreen(), GOLD.getBlue(), 46));
            g2.drawRoundRect(0, 0, w - 1, h - 1, h, h);

            g2.setColor(
                new Color(DOT_COLOR.getRed(), DOT_COLOR.getGreen(), DOT_COLOR.getBlue(), Math.round(255 * dotAlpha)));
            g2.fillOval(14, h / 2 - 3, 7, 7);

            g2.setFont(getFont());
            g2.setColor(Color.WHITE);
            var metrics = g2.getFontMetrics();
            int textY = (h - metrics.getHeight()) / 2 + metrics.getAscent();
            g2.drawString(text, 28, textY);

            g2.dispose();
        }
    }

    /** Companion pill to {@link ServerPill}, gold-on-violet - reserved for the community Discord link
     *  once one exists (see the TODO where this is instantiated). */
    private static class DiscordPill extends JComponent {

        private boolean hovered;

        DiscordPill() {
            setFont(new Font("Segoe UI", Font.BOLD, 11));
            setOpaque(false);
            setPreferredSize(new Dimension(96, 28));
            setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
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

            int w = getWidth();
            int h = getHeight();
            Color top = hovered ? GOLD_DIM : PURPLE_DEEP;
            g2.setPaint(new LinearGradientPaint(0, 0, w, h, new float[] {0F, 1F}, new Color[] {top, PURPLE_DARK}));
            g2.fillRoundRect(0, 0, w - 1, h - 1, h, h);
            g2.setColor(hovered ? GOLD : new Color(GOLD.getRed(), GOLD.getGreen(), GOLD.getBlue(), 110));
            g2.drawRoundRect(0, 0, w - 1, h - 1, h, h);

            g2.setFont(getFont());
            g2.setColor(Color.WHITE);
            var metrics = g2.getFontMetrics();
            String text = "DISCORD";
            int textX = (w - metrics.stringWidth(text)) / 2;
            int textY = (h - metrics.getHeight()) / 2 + metrics.getAscent();
            g2.drawString(text, textX, textY);

            g2.dispose();
        }
    }

    /** Right-hand panel of the main card - non-opaque so RootPanel's shared background image shows
     *  through (see class javadoc), with its own dark gradient overlay drawn on top for text contrast,
     *  matching the reference DA's dimmed art treatment. */
    private static class ArtPanel extends JPanel {

        ArtPanel() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            int h = getHeight();
            g2.setPaint(
                new LinearGradientPaint(
                    0,
                    0,
                    0,
                    h,
                    new float[] {0F, 0.3F, 1F},
                    new Color[] {new Color(8, 6, 10, 140), new Color(8, 6, 10, 90), new Color(8, 6, 10, 185)}));
            g2.fillRect(0, 0, getWidth(), h);
            g2.dispose();
        }
    }

    /** One "Astuces" card in the art panel - icon tile, title, description, with a border/background
     *  hover state (see the reference DA request for this specifically). */
    private static class TipCard extends JPanel {

        enum Glyph { BOX, STAR, CLOCK }

        private final Glyph glyph;
        private boolean hovered;

        TipCard(Glyph glyph, String title, String description) {
            this.glyph = glyph;
            setOpaque(false);
            setLayout(new BorderLayout(TIP_ICON_GAP, 0));
            setBorder(BorderFactory.createEmptyBorder(14, TIP_CARD_PAD_H / 2, 14, TIP_CARD_PAD_H / 2));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 96));
            setAlignmentX(Component.LEFT_ALIGNMENT);

            IconTile tile = new IconTile();
            add(tile, BorderLayout.WEST);

            JPanel text = new JPanel();
            text.setOpaque(false);
            text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));

            JLabel titleLabel = new JLabel(title);
            titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 14));
            titleLabel.setForeground(Color.WHITE);
            titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            text.add(titleLabel);
            text.add(Box.createVerticalStrut(4));

            // A JTextArea (not a JLabel with a fixed-pixel-width HTML <div>) reflows to whatever width
            // it's given - the HTML approach previously here hardcoded a width guess that didn't match
            // the real available space and clipped the last few words. setSize() up front (rather than
            // leaving it to normal layout) is the standard trick to make a wrap-enabled JTextArea report
            // a correctly wrapped preferred height inside a BoxLayout - safe here since the window (and
            // therefore this exact width) never changes at runtime.
            JTextArea descLabel = new JTextArea(description);
            descLabel.setLineWrap(true);
            descLabel.setWrapStyleWord(true);
            descLabel.setEditable(false);
            descLabel.setFocusable(false);
            descLabel.setOpaque(false);
            descLabel.setBorder(null);
            descLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            descLabel.setForeground(INK_DIM);
            descLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            descLabel.setSize(new Dimension(TIP_TEXT_WIDTH, Short.MAX_VALUE));
            text.add(descLabel);

            add(text, BorderLayout.CENTER);

            // Swing dispatches a mouse event to the single deepest component under the cursor, not to
            // every ancestor - a listener on the card alone only fires while the mouse is over its own
            // uncovered padding, going dead the moment it crosses onto the icon tile or the text (which
            // together cover most of the card's area, see player feedback: "hover doesn't work over
            // certain zones"). Attaching the same listener to every child closes those dead zones.
            MouseAdapter hoverListener = new MouseAdapter() {

                @Override
                public void mouseEntered(MouseEvent e) {
                    hovered = true;
                    tile.setHovered(true);
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hovered = false;
                    tile.setHovered(false);
                    repaint();
                }
            };
            addMouseListener(hoverListener);
            tile.addMouseListener(hoverListener);
            text.addMouseListener(hoverListener);
            titleLabel.addMouseListener(hoverListener);
            descLabel.addMouseListener(hoverListener);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            g2.setColor(new Color(12, 10, 16, hovered ? 216 : 173));
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
            g2.setColor(hovered ? new Color(GOLD.getRed(), GOLD.getGreen(), GOLD.getBlue(), 140)
                : new Color(GOLD.getRed(), GOLD.getGreen(), GOLD.getBlue(), 46));
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);

            g2.dispose();
        }

        /** Small gradient tile with a simple hand-drawn glyph, on the left of each tip card. */
        private class IconTile extends JComponent {

            private boolean hovered;

            IconTile() {
                setPreferredSize(new Dimension(TIP_ICON_WIDTH, TIP_ICON_WIDTH));
                setOpaque(false);
            }

            void setHovered(boolean hovered) {
                this.hovered = hovered;
                repaint();
            }

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                int w = getWidth();
                int h = getHeight();
                Color from = hovered ? GOLD_DIM : PURPLE_DEEP;
                g2.setPaint(new LinearGradientPaint(0, 0, w, h, new float[] {0F, 1F}, new Color[] {from, PURPLE_DARK}));
                g2.fillRoundRect(0, 0, w - 1, h - 1, 10, 10);

                g2.setColor(GOLD);
                g2.setStroke(new BasicStroke(1.7F));
                int cx = w / 2;
                int cy = h / 2;
                switch (glyph) {
                    case BOX:
                        g2.drawRect(cx - 9, cy - 8, 18, 16);
                        g2.drawLine(cx - 9, cy, cx + 9, cy);
                        break;
                    case STAR:
                        java.awt.geom.Path2D.Float star = new java.awt.geom.Path2D.Float();
                        for (int i = 0; i < 8; i++) {
                            double angle = Math.PI / 4 * i - Math.PI / 2;
                            double r = i % 2 == 0 ? 10 : 4.5;
                            float px = (float) (cx + r * Math.cos(angle));
                            float py = (float) (cy + r * Math.sin(angle));
                            if (i == 0) {
                                star.moveTo(px, py);
                            } else {
                                star.lineTo(px, py);
                            }
                        }
                        star.closePath();
                        g2.draw(star);
                        break;
                    case CLOCK:
                        g2.drawOval(cx - 10, cy - 10, 20, 20);
                        g2.drawLine(cx, cy, cx, cy - 6);
                        g2.drawLine(cx, cy, cx + 5, cy + 2);
                        break;
                    default:
                        break;
                }

                g2.dispose();
            }
        }
    }

    /** Streams PCM in small chunks on a dedicated thread instead of handing the whole track to a
     *  javax.sound.sampled.Clip at once - see the note on {@link #startBackgroundMusic()} for why. Gain
     *  is applied here in software (each signed 16-bit sample scaled by the current volume) rather than
     *  through a mixer control, since {@code volume} is read fresh for every chunk. */
    private static class MusicPlayer {

        private static final int CHUNK_FRAMES = 2048;

        private final byte[] pcm;
        private final AudioFormat format;
        private volatile float volume;
        private volatile boolean running;

        MusicPlayer(byte[] pcm, AudioFormat format, float initialVolume) {
            this.pcm = pcm;
            this.format = format;
            this.volume = initialVolume;
        }

        void setVolume(float volume) {
            this.volume = volume;
        }

        void start() {
            running = true;
            Thread thread = new Thread(this::run, "music-player");
            thread.setDaemon(true);
            thread.start();
        }

        private void run() {
            try {
                SourceDataLine line = AudioSystem.getSourceDataLine(format);
                line.open(format);
                line.start();

                int chunkBytes = CHUNK_FRAMES * format.getFrameSize();
                byte[] chunk = new byte[chunkBytes];
                int position = 0;

                while (running) {
                    int toCopy = Math.min(chunkBytes, pcm.length - position);
                    System.arraycopy(pcm, position, chunk, 0, toCopy);
                    applyGain(chunk, toCopy, volume);
                    line.write(chunk, 0, toCopy);
                    position += toCopy;
                    if (position >= pcm.length) {
                        position = 0;
                    }
                }
                line.drain();
                line.close();
            } catch (LineUnavailableException e) {
                // No music is a cosmetic loss, not worth failing the launcher over.
            } catch (Throwable t) {
                // Same reasoning as above, just widened to Throwable: a GraalVM native-image build
                // throws a plain java.lang.Error ("Can't find java.home") from deep inside
                // AudioSystem's service-provider lookup (com.sun.media.sound.JSSecurityManager
                // assumes a real JDK install with a resolvable java.home, which a native image
                // doesn't have) instead of the checked LineUnavailableException above - on a plain
                // `java -jar` launch this path is never hit, but this thread's own uncaught
                // exceptions still shouldn't ever surface as a fatal launcher error either way.
            }
        }

        /** In-place scale of each signed 16-bit little-endian sample - matches the PCM_SIGNED stereo
         *  16-bit format the track is authored in (see the format assertion in startBackgroundMusic). */
        private static void applyGain(byte[] chunk, int length, float volume) {
            for (int i = 0; i + 1 < length; i += 2) {
                short sample = (short) ((chunk[i] & 0xFF) | (chunk[i + 1] << 8));
                short scaled = (short) Math.round(sample * volume);
                chunk[i] = (byte) (scaled & 0xFF);
                chunk[i + 1] = (byte) (scaled >> 8);
            }
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

    /** RAM allocation dialog - a dark rounded panel over a dimmed backdrop, matching the in-game
     *  Advanced Enchanting/Crafting table GUIs' look. */
    private static class SettingsDialog extends JDialog {

        SettingsDialog(JFrame owner, LauncherConfig config) {
            super(owner, "Paramètres", true);
            setUndecorated(true);
            // Deliberately no setBackground(alpha=0) here (unlike the alpha-less setShape-only pattern
            // used by the main window at line ~186): a fully transparent background forces AWT into
            // Windows' PERPIXEL_TRANSLUCENT path, which routes through sun.awt.windows.TranslucentWindowPainter -
            // a class that throws NoSuchMethodError in this GraalVM native-image build (missing native
            // JNI method binding), freezing the AWT event thread entirely. setShape() alone (below) still
            // gives the rounded-corner clip via the simpler PERPIXEL_TRANSPARENT path, which works fine.

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
        // Must be the very first thing that runs, before any AWT/Swing class is touched: a
        // GraalVM native-image build of this class defaults java.awt.headless to true (unlike a
        // plain `java -jar` launch, which correctly auto-detects the real desktop session) -
        // GraphicsEnvironment caches the headless flag in a static initializer on first use, so
        // setting this after any AWT class has loaded would be too late.
        System.setProperty("java.awt.headless", "false");

        // Same native-image-only gap: a native image has no real JDK install, so java.home comes
        // back null. javax.sound.sampled's service-provider lookup (com.sun.media.sound.
        // JSSecurityManager) throws a plain Error ("Can't find java.home ??") the instant it's
        // null, before it even gets to gracefully handling a missing <java.home>/conf/sound.
        // properties file - any non-null path satisfies that check (the sound.properties read
        // itself already tolerates not finding the file). MusicPlayer.run()'s own Throwable catch
        // is the real safety net if this property trick ever stops being enough on some future
        // GraalVM version - see its comment.
        if (System.getProperty("java.home") == null) {
            System.setProperty("java.home", System.getProperty("user.dir"));
        }

        // A jpackage app-image has no console attached when double-clicked, so an uncaught exception
        // here would otherwise vanish completely - the exact "nothing happens when I open it" symptom
        // this is meant to rule out. Anything that goes wrong from this point on, on any thread, gets a
        // visible dialog (JOptionPane, since it doesn't depend on our own icon/background resources
        // loading successfully) and a line in %APPDATA%/.furymc/launcher-error.log.
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
            "FuryMc Launcher n'a pas pu démarrer :\n\n" + e + "\n\nDétails dans launcher-error.log (%APPDATA%\\.furymc\\).",
            "Erreur FuryMc Launcher",
            JOptionPane.ERROR_MESSAGE);
    }
}
