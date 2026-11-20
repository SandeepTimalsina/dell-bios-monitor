package com.example.biosmonitor.ui;

import atlantafx.base.theme.PrimerLight;
import atlantafx.base.theme.PrimerDark;
import atlantafx.base.controls.ToggleSwitch;
import com.example.biosmonitor.BiosMonitor;
import com.example.biosmonitor.cctk.CctkSupport;
import com.example.biosmonitor.cctk.CctkSupport.Choice;
import com.example.biosmonitor.cctk.CctkSupport.SettingMeta;
import com.example.biosmonitor.cctk.CctkSupport.SubOptionMeta;
import com.example.biosmonitor.cctk.CctkSupport.ValueRange;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;

import java.io.*;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import static com.example.biosmonitor.cctk.CctkSupport.*;

/** JavaFX front end for Dell Command | Configure. */
public final class BiosMonitorApp extends Application {
    private static final Map<String, List<Choice>> SESSION_CHOICES = new ConcurrentHashMap<>();
    private static final Map<String, String> SESSION_HELP = new ConcurrentHashMap<>();
    private final ObservableList<SettingRow> rows = FXCollections.observableArrayList();
    private final Map<String, String> previousValues = new HashMap<>();
    private Label status = new Label("Starting…");
    private Label machine = new Label();
    private Button applyButton;
    private Button revertButton;
    private Button scanButton;
    private TextField searchField;
    private TabPane tabs;
    private Stage stage;
    private StackPane appRoot;
    private VBox scanOverlay;
    private VBox messageOverlay;
    private Label scanMessage;
    private Label scanDetail;
    private final Set<String> favoriteIds = new HashSet<>();
    private static final String FAVORITES_FILE = "favorites.properties";
    private boolean darkMode;
    private Button themeButton;

    public static void launchGui(String[] args) {
        Application.launch(BiosMonitorApp.class, args);
    }

    @Override public void start(Stage stage) {
        this.stage = stage;
        darkMode = loadDarkMode();
        applyTheme();
        stage.setTitle("Dell BIOS Monitor");
        stage.setMinWidth(940);
        stage.setMinHeight(640);
        if (!isRoot()) {
            showPrivilegePrompt();
            return;
        }
        loadFavorites();
        showDashboard();
        scanAndReload(true);
    }

    private void showPrivilegePrompt() {
        Label title = new Label("Administrator access required");
        title.getStyleClass().add("h2");
        Label explanation = new Label("Dell Command | Configure needs root privileges to read and apply BIOS settings.");
        explanation.setWrapText(true);
        PasswordField password = new PasswordField();
        password.setPromptText("Linux sudo password");
        Label message = new Label();
        Button continueButton = new Button("Continue");
        continueButton.getStyleClass().add("accent");
        continueButton.setDefaultButton(true);
        continueButton.setOnAction(event -> {
            continueButton.setDisable(true);
            message.setText("Checking credentials…");
            char[] secret = password.getText().toCharArray();
            password.clear();
            Task<Void> auth = new Task<>() {
                @Override protected Void call() throws Exception {
                    authenticateSudo(secret);
                    migrateLegacyData();
                    return null;
                }
            };
            auth.setOnSucceeded(e -> {
                loadFavorites();
                showDashboard();
                scanAndReload(true);
            });
            auth.setOnFailed(e -> {
                message.setText(auth.getException().getMessage());
                continueButton.setDisable(false);
            });
            Thread thread = new Thread(auth, "sudo-authentication");
            thread.setDaemon(true);
            thread.start();
        });
        VBox content = new VBox(16, title, explanation, password, continueButton, message);
        content.setPadding(new Insets(34));
        content.setMaxWidth(480);
        Scene scene = new Scene(new StackPane(content), 560, 360);
        scene.getStylesheets().add(Objects.requireNonNull(
                getClass().getResource("/com/example/biosmonitor/ui/dashboard.css")).toExternalForm());
        stage.setScene(scene);
        stage.show();
        password.requestFocus();
    }

    private void authenticateSudo(char[] secret) throws Exception {
        List<String> authCommand = System.getenv("FLATPAK_ID") != null
                ? List.of("flatpak-spawn", "--host", "sudo", "-S", "-p", "", "-v")
                : List.of("sudo", "-S", "-p", "", "-v");
        Process auth = new ProcessBuilder(authCommand).redirectErrorStream(true).start();
        ByteArrayOutputStream authOutput = new ByteArrayOutputStream();
        Thread outputReader = new Thread(() -> {
            try { auth.getInputStream().transferTo(authOutput); } catch (IOException ignored) { }
        }, "sudo-auth-output");
        outputReader.setDaemon(true);
        outputReader.start();
        try (OutputStream out = auth.getOutputStream()) {
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(CharBuffer.wrap(secret));
            byte[] passwordBytes = new byte[bytes.remaining()];
            bytes.get(passwordBytes);
            out.write(passwordBytes);
            out.write('\n');
            out.flush();
            Arrays.fill(passwordBytes, (byte) 0);
            if (bytes.hasArray()) Arrays.fill(bytes.array(), (byte) 0);
        } finally {
            Arrays.fill(secret, '\0');
        }
        if (!auth.waitFor(30, TimeUnit.SECONDS)) {
            auth.destroyForcibly();
            throw new IOException("sudo authentication timed out.");
        }
        if (auth.exitValue() != 0) throw new IOException("Authentication failed. Check the password and try again.");
    }

    private void migrateLegacyData() {
        Path legacy = Path.of("/var/lib/dell-bios-monitor");
        if (BiosMonitor.DATA_DIR.equals(legacy) || Files.exists(BiosMonitor.DB_FILE)
                || !Files.isRegularFile(legacy.resolve("bios-history.sqlite"))) return;
        try {
            Files.createDirectories(BiosMonitor.DATA_DIR);
            runHostCommand(BiosMonitor.privilegedCommand("cp", List.of("-a",
                    legacy.toString() + "/.", BiosMonitor.DATA_DIR.toString() + "/")));
            String uid = commandOutput(List.of("id", "-u")).trim();
            String gid = commandOutput(List.of("id", "-g")).trim();
            runHostCommand(BiosMonitor.privilegedCommand("chown", List.of("-R", uid + ":" + gid,
                    BiosMonitor.DATA_DIR.toString())));
        } catch (Exception ex) {
            throw new IllegalStateException("Signed in, but could not move the existing BIOS history into "
                    + BiosMonitor.DATA_DIR + ": " + ex.getMessage(), ex);
        }
    }

    private static void runHostCommand(List<String> command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Command timed out: " + String.join(" ", command));
        }
        if (process.exitValue() != 0) throw new IOException(output.isBlank()
                ? "Command failed: " + String.join(" ", command) : output.strip());
    }

    private static String commandOutput(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IOException(output.strip());
        return output;
    }

    private void showDashboard() {
        Label heading = new Label("Dell BIOS Monitor");
        heading.getStyleClass().add("h1");
        Label logo = new Label("D");
        logo.getStyleClass().add("brand-mark");
        machine.getStyleClass().add("text-muted");
        VBox titleBlock = new VBox(4, heading, machine);
        themeButton = new Button();
        themeButton.getStyleClass().add("action-button");
        updateThemeButton();
        themeButton.setOnAction(event -> {
            darkMode = !darkMode;
            applyTheme();
            updateThemeButton();
            saveDarkMode();
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox brand = new HBox(14, logo, titleBlock, spacer, themeButton);
        brand.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        scanButton = actionButton("↻", "Refresh scan");
        revertButton = actionButton("↶", "Revert to previous scan");
        applyButton = actionButton("✓", "Apply all selected");
        applyButton.getStyleClass().add("accent");
        Button selectAll = actionButton("☑", "Select all");
        Button clear = actionButton("□", "Clear selection");
        scanButton.setOnAction(e -> scanAndReload(false));
        revertButton.setOnAction(e -> stageRevert());
        applyButton.setOnAction(e -> reviewAndApply());
        selectAll.setOnAction(e -> { rows.forEach(row -> { if (row.editable) row.selected.set(true); }); updateActions(); });
        clear.setOnAction(e -> { rows.forEach(row -> row.selected.set(false)); updateActions(); });
        searchField = new TextField();
        searchField.setPromptText("Search BIOS settings…");
        searchField.getStyleClass().add("search-field");
        searchField.textProperty().addListener((obs, old, value) -> rebuildTabs());
        Label searchGlyph = new Label("⌕");
        searchGlyph.getStyleClass().add("search-glyph");
        StackPane search = new StackPane(searchField, searchGlyph);
        StackPane.setAlignment(searchGlyph, javafx.geometry.Pos.CENTER_LEFT);
        StackPane.setMargin(searchGlyph, new Insets(0, 0, 0, 12));
        searchField.setPadding(new Insets(10, 12, 10, 38));
        search.setMaxWidth(380);
        HBox actions = new HBox(9, search, selectAll, clear, revertButton, scanButton, applyButton);
        actions.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        actions.setPadding(new Insets(20, 0, 12, 0));
        tabs = new TabPane();
        status.getStyleClass().add("status-line");
        VBox root = new VBox(8, brand, actions, tabs, status);
        root.setPadding(new Insets(24));
        VBox.setVgrow(tabs, Priority.ALWAYS);
        appRoot = new StackPane(root);
        scanOverlay = createScanOverlay();
        scanOverlay.setVisible(false);
        scanOverlay.setManaged(false);
        appRoot.getChildren().add(scanOverlay);
        messageOverlay = new VBox();
        messageOverlay.getStyleClass().add("dialog-overlay");
        messageOverlay.setAlignment(javafx.geometry.Pos.CENTER);
        messageOverlay.setVisible(false);
        messageOverlay.setManaged(false);
        appRoot.getChildren().add(messageOverlay);
        Scene scene = new Scene(appRoot, 1280, 820);
        scene.getStylesheets().add(Objects.requireNonNull(
                getClass().getResource("/com/example/biosmonitor/ui/dashboard.css")).toExternalForm());
        stage.setScene(scene);
        stage.show();
        rebuildTabs();
    }

    private VBox createScanOverlay() {
        Label artwork = new Label("D");
        artwork.getStyleClass().add("splash-mark");
        Label title = new Label("Preparing your BIOS dashboard");
        title.getStyleClass().add("h2");
        scanMessage = new Label("Connecting to Dell Command | Configure…");
        scanMessage.getStyleClass().add("scan-message");
        scanDetail = new Label("Reading settings and checking device support");
        scanDetail.getStyleClass().add("text-muted");
        ProgressIndicator progress = new ProgressIndicator();
        progress.setPrefSize(38, 38);
        VBox card = new VBox(16, artwork, title, scanMessage, progress, scanDetail);
        card.setAlignment(javafx.geometry.Pos.CENTER);
        card.setMaxWidth(470);
        card.getStyleClass().add("scan-card");
        VBox overlay = new VBox(card);
        overlay.setAlignment(javafx.geometry.Pos.CENTER);
        overlay.getStyleClass().add("scan-overlay");
        return overlay;
    }

    private static Button actionButton(String icon, String text) {
        Label glyph = new Label(icon);
        glyph.getStyleClass().add("action-icon");
        Button button = new Button(text, glyph);
        button.getStyleClass().add("action-button");
        return button;
    }

    private void applyTheme() {
        Application.setUserAgentStylesheet(darkMode ? new PrimerDark().getUserAgentStylesheet()
                : new PrimerLight().getUserAgentStylesheet());
        if (appRoot != null) {
            appRoot.getStyleClass().removeAll("dark-mode", "light-mode");
            appRoot.getStyleClass().add(darkMode ? "dark-mode" : "light-mode");
        }
    }

    private void updateThemeButton() {
        if (themeButton != null) themeButton.setText(darkMode ? "☀  Light mode" : "☾  Dark mode");
    }

    private boolean loadDarkMode() {
        Properties preferences = new Properties();
        Path file = BiosMonitor.DATA_DIR.resolve("appearance.properties");
        if (!Files.isRegularFile(file)) return true;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            preferences.load(reader);
            return Boolean.parseBoolean(preferences.getProperty("darkMode", "false"));
        } catch (IOException ignored) { return false; }
    }

    private void saveDarkMode() {
        Properties preferences = new Properties();
        preferences.setProperty("darkMode", Boolean.toString(darkMode));
        try {
            Files.createDirectories(BiosMonitor.DATA_DIR);
            try (Writer writer = Files.newBufferedWriter(BiosMonitor.DATA_DIR.resolve("appearance.properties"), StandardCharsets.UTF_8)) {
                preferences.store(writer, "Dashboard appearance");
            }
        } catch (IOException ex) { status.setText("Could not save appearance preference: " + ex.getMessage()); }
    }

    private void scanAndReload(boolean initial) {
        setBusy(true, initial ? "Running the initial CCTK scan…" : "Scanning BIOS settings…");
        runTask("scan", () -> {
            BiosMonitor.scanNow(message -> Platform.runLater(() -> updateProgressMessage(message)));
            return loadDashboardData();
        }, result -> {
            updateDashboard(result);
            status.setText("Scan complete · " + rows.size() + " settings · " + BiosMonitor.DB_FILE);
            setBusy(false, null);
        });
    }

    private ScanResult loadDashboardData() throws Exception {
        List<BiosMonitor.Setting> settings = BiosMonitor.parseExport(BiosMonitor.EXPORT_FILE);
        Map<String, String> old = loadPreviousSnapshot();
        List<SettingRow> loaded = new ArrayList<>();
        Map<String, List<BiosMonitor.Setting>> byName = new LinkedHashMap<>();
        for (BiosMonitor.Setting setting : settings) byName.computeIfAbsent(setting.name(), k -> new ArrayList<>()).add(setting);
        String cacheVersion = cacheVersion();
        Properties cache = readChoiceCache(cacheVersion);
        Set<String> readOnly = readCachedReadOnly(cache);
        Set<String> longOptions = readCachedLongOptions(cache);
        if (readOnly == null || longOptions == null) {
            String commandHelp = CctkSupport.run(BiosMonitor.CCTK, List.of("--help"));
            if (readOnly == null) readOnly = parseReadOnly(commandHelp);
            if (longOptions == null) longOptions = parseLongOptions(commandHelp);
            writeCachedReadOnly(cache, readOnly);
            writeCachedLongOptions(cache, longOptions);
            saveChoiceCache(cache);
        }
        Set<String> supportedOptions = longOptions;
        byName.keySet().removeIf(name -> !supportedOptions.contains(name));
        int total = byName.size();
        int completed = 0;
        for (var group : byName.entrySet()) {
            String name = group.getKey();
            int progress = ++completed;
            Platform.runLater(() -> updateProgressMessage("Checking settings · " + progress + "/" + total + " · " + name));
            SettingMeta meta = loadSettingMeta(name, cache);
            boolean repeatedSameValue = group.getValue().size() > 1
                    && group.getValue().stream().map(BiosMonitor.Setting::value).distinct().count() == 1;
            boolean canEdit = !readOnly.contains(name) && meta.editable();
            if (!meta.subOptions().isEmpty()) {
                Map<String, SubOptionMeta> subOptions = new HashMap<>();
                meta.subOptions().forEach(option -> subOptions.put(option.name().toLowerCase(Locale.ROOT), option));
                for (BiosMonitor.Setting setting : group.getValue()) {
                    String[] keyValue = setting.value().split(":", 2);
                    if (keyValue.length != 2) {
                    SettingRow row = new SettingRow(name, setting.occurrence(), setting.value(), 1, meta, false, null,
                            "--" + name);
                    row.favorite.set(favoriteIds.contains(id(row.name, row.occurrence)));
                    loaded.add(row);
                        continue;
                    }
                    SubOptionMeta sub = subOptions.get(keyValue[0].toLowerCase(Locale.ROOT));
                    if (sub == null) {
                        SettingRow row = new SettingRow(name, setting.occurrence(), keyValue[1], 1, meta, false, keyValue[0], null);
                        row.favorite.set(favoriteIds.contains(id(row.name, row.occurrence)));
                        loaded.add(row);
                        continue;
                    }
                    SettingMeta subMeta = new SettingMeta(sub.choices(), sub.help(), false,
                            sub.range() != null, sub.range(), List.of(),
                            sub.range() != null || sub.choices().stream().anyMatch(Choice::supported));
                    SettingRow row = new SettingRow(name, setting.occurrence(), keyValue[1], 1, subMeta,
                            canEdit && subMeta.editable(), keyValue[0], "--" + name);
                    row.favorite.set(favoriteIds.contains(id(row.name, row.occurrence)));
                    loaded.add(row);
                }
            } else if (group.getValue().size() == 1 || repeatedSameValue) {
                BiosMonitor.Setting first = group.getValue().get(0);
                SettingRow row = new SettingRow(name, 1, first.value(), group.getValue().size(), meta, canEdit, null, null);
                row.favorite.set(favoriteIds.contains(id(row.name, row.occurrence)));
                loaded.add(row);
            } else {
                for (BiosMonitor.Setting setting : group.getValue()) {
                    SettingRow row = new SettingRow(name, setting.occurrence(), setting.value(), 1, meta, false, null, null);
                    row.favorite.set(favoriteIds.contains(id(row.name, row.occurrence)));
                    loaded.add(row);
                }
            }
        }
        return new ScanResult(loaded, old, systemLabel());
    }

    private SettingMeta loadSettingMeta(String name, Properties cache) throws Exception {
        List<Choice> choices = SESSION_CHOICES.get(name);
        if (choices == null) choices = readCachedChoices(cache, name);
        String help = SESSION_HELP.get(name);
        boolean specialSchedule = name.equalsIgnoreCase("AdvBatteryChargeCfg");
        if (help == null && (choices == null || choices.isEmpty() || specialSchedule)) {
            try { help = CctkSupport.run(BiosMonitor.CCTK, List.of("-H", "--" + name)); }
            catch (IOException ex) { help = ""; }
            SESSION_HELP.put(name, help);
        }
        if (choices == null) {
            choices = parseChoices(help == null ? "" : help);
            writeCachedChoices(cache, name, choices);
            saveChoiceCache(cache);
        }
        if (specialSchedule) choices = List.of(new Choice("Disabled", true), new Choice("Enabled", true));
        SESSION_CHOICES.put(name, choices);
        List<SubOptionMeta> subOptions = parseSubOptions(help);
        ValueRange range = parseValueRange(help);
        boolean rawValue = hasSyntaxValue(help, name) && !specialSchedule;
        boolean editable = choices.stream().anyMatch(Choice::supported) || specialSchedule || rawValue
                || range != null || !subOptions.isEmpty();
        return new SettingMeta(choices, help == null ? "" : help, specialSchedule, rawValue,
                range, subOptions, editable);
    }

    private String cacheVersion() {
        try {
            BiosMonitor.SystemInfo info = BiosMonitor.parseSystemInfo(BiosMonitor.EXPORT_FILE);
            return String.join("|", info.model(), info.systemId(), info.biosVersion(), info.cctkVersion());
        } catch (IOException ex) { return "unknown"; }
    }

    private Properties readChoiceCache(String version) throws IOException {
        Properties properties = new Properties();
        Path file = BiosMonitor.DATA_DIR.resolve("cctk-options.properties");
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { properties.load(reader); }
        }
        if (!version.equals(properties.getProperty("system.version"))) {
            properties.clear();
            properties.setProperty("system.version", version);
        }
        return properties;
    }

    private static List<Choice> readCachedChoices(Properties cache, String setting) {
        String prefix = cachePrefix(setting);
        String countText = cache.getProperty(prefix + ".count");
        if (countText == null) return null;
        try {
            int count = Integer.parseInt(countText);
            List<Choice> choices = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                String value = cache.getProperty(prefix + "." + i + ".value");
                boolean supported = Boolean.parseBoolean(cache.getProperty(prefix + "." + i + ".supported", "false"));
                if (value == null) return null;
                choices.add(new Choice(value, supported));
            }
            return choices;
        } catch (NumberFormatException ex) { return null; }
    }

    private static Set<String> readCachedReadOnly(Properties cache) {
        String countText = cache.getProperty("readOnly.count");
        if (countText == null) return null;
        try {
            int count = Integer.parseInt(countText);
            Set<String> values = new HashSet<>();
            for (int i = 0; i < count; i++) {
                String value = cache.getProperty("readOnly." + i);
                if (value == null) return null;
                values.add(value);
            }
            return values;
        } catch (NumberFormatException ex) { return null; }
    }

    private static Set<String> readCachedLongOptions(Properties cache) {
        String countText = cache.getProperty("longOptions.count");
        if (countText == null) return null;
        try {
            int count = Integer.parseInt(countText);
            Set<String> values = new HashSet<>();
            for (int i = 0; i < count; i++) {
                String value = cache.getProperty("longOptions." + i);
                if (value == null) return null;
                values.add(value);
            }
            return values;
        } catch (NumberFormatException ex) { return null; }
    }

    private static void writeCachedReadOnly(Properties cache, Set<String> values) {
        cache.setProperty("readOnly.count", Integer.toString(values.size()));
        int i = 0;
        for (String value : values) cache.setProperty("readOnly." + i++, value);
    }

    private static void writeCachedLongOptions(Properties cache, Set<String> values) {
        cache.setProperty("longOptions.count", Integer.toString(values.size()));
        int i = 0;
        for (String value : values) cache.setProperty("longOptions." + i++, value);
    }

    private static void writeCachedChoices(Properties cache, String setting, List<Choice> choices) {
        String prefix = cachePrefix(setting);
        cache.setProperty(prefix + ".count", Integer.toString(choices.size()));
        for (int i = 0; i < choices.size(); i++) {
            Choice choice = choices.get(i);
            cache.setProperty(prefix + "." + i + ".value", choice.value());
            cache.setProperty(prefix + "." + i + ".supported", Boolean.toString(choice.supported()));
        }
    }

    private static String cachePrefix(String setting) {
        return "option." + Base64.getUrlEncoder().withoutPadding().encodeToString(setting.getBytes(StandardCharsets.UTF_8));
    }

    private void saveChoiceCache(Properties cache) throws IOException {
        Files.createDirectories(BiosMonitor.DATA_DIR);
        Path file = BiosMonitor.DATA_DIR.resolve("cctk-options.properties");
        Path temp = Files.createTempFile(BiosMonitor.DATA_DIR, "cctk-options-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                cache.store(writer, "CCTK setting choices");
            }
            try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }

    private void updateDashboard(ScanResult result) {
        rows.setAll(result.rows());
        previousValues.clear();
        previousValues.putAll(result.previous());
        machine.setText(result.machine());
        rebuildTabs();
        updateActions();
    }

    private String systemLabel() {
        try {
            List<BiosMonitor.Setting> settings = BiosMonitor.parseExport(BiosMonitor.EXPORT_FILE);
            Map<String, String> values = new HashMap<>();
            for (BiosMonitor.Setting setting : settings) values.put(setting.name(), setting.value());
            return String.join(" · ", Arrays.asList(values.getOrDefault("SysName", "Dell system"),
                    values.getOrDefault("BiosVer", ""), values.getOrDefault("SysId", "")));
        } catch (IOException ex) { return "System information unavailable"; }
    }

    private void stageRevert() {
        int count = 0;
        for (SettingRow row : rows) {
            String old = previousValues.get(id(row.name, row.occurrence));
            if (row.subOption != null && old != null) {
                String[] pair = old.split(":", 2);
                if (pair.length != 2 || !pair[0].equalsIgnoreCase(row.subOption)) continue;
                old = pair[1];
            }
            if (old == null || Objects.equals(old, row.current.get())) continue;
            String previousValue = old;
            Choice match = row.choices.stream().filter(c -> c.supported() && c.value().equalsIgnoreCase(previousValue))
                    .findFirst().orElse(null);
            if (match == null && !row.meta.advancedSchedule() && !row.meta.rawValue() && row.meta.range() == null) continue;
            row.proposed.set(old);
            row.selected.set(true);
            count++;
        }
        status.setText(count == 0 ? "No reversible supported changes found in the previous scan."
                : count + " previous value(s) staged. Review the selections, then apply.");
        updateActions();
    }

    private void reviewAndApply() {
        List<SettingRow> selected = rows.stream().filter(r -> r.selected.get() && r.editable).toList();
        if (selected.isEmpty()) { status.setText("Select at least one supported setting to apply."); return; }
        List<SettingRow> changed = selected.stream().filter(r -> !Objects.equals(r.current.get(), r.proposed.get())).toList();
        if (changed.isEmpty()) { status.setText("The selected values match the current BIOS values; nothing needs to be applied."); return; }
        List<SettingRow> selectedRows = changed;
        TableView<SettingRow> review = new TableView<>(FXCollections.observableArrayList(selectedRows));
        review.setPrefHeight(Math.min(420, 90 + selectedRows.size() * 34));
        review.getColumns().setAll(textColumn("Setting", SettingRow::displayName, 220),
                textColumn("Current", r -> r.current.get(), 220),
                textColumn("New value", r -> r.proposed.get(), 240));
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.setTitle("Confirm BIOS changes");
        confirmation.setHeaderText("Apply " + selectedRows.size() + " BIOS setting(s)?");
        confirmation.setContentText("Review the current and new values below. CCTK will apply this batch, then the monitor will scan again. Some BIOS changes may require a system restart; the app will not restart automatically.");
        confirmation.getDialogPane().setExpandableContent(review);
        confirmation.getDialogPane().setExpanded(true);
        confirmation.getButtonTypes().setAll(ButtonType.CANCEL, new ButtonType("Apply all", ButtonBar.ButtonData.OK_DONE));
        Optional<ButtonType> answer = confirmation.showAndWait();
        if (answer.isEmpty() || answer.get().getButtonData() != ButtonBar.ButtonData.OK_DONE) return;

        setBusy(true, "Applying " + selectedRows.size() + " BIOS setting(s)…");
        runTask("apply", () -> {
            StringBuilder output = new StringBuilder();
            List<String> errors = new ArrayList<>();
            for (List<String> command : buildApplyCommands(selectedRows)) {
                try { output.append(CctkSupport.run(BiosMonitor.CCTK, command)).append('\n'); }
                catch (Exception ex) { errors.add(String.join(" ", command) + ": " + ex.getMessage()); }
            }
            BiosMonitor.scanNow(message -> Platform.runLater(() -> updateProgressMessage(message)));
            ScanResult refreshed = loadDashboardData();
            return new ApplyResult(output.toString(), errors.isEmpty() ? null : String.join("\n", errors), refreshed);
        }, result -> {
            updateDashboard(result.scan());
            boolean reboot = cctkReportsReboot(result.output());
            status.setText(result.error() == null ? (reboot ? "CCTK reported a reboot may be required. A follow-up scan was saved. "
                    : "Batch submitted and a follow-up scan was saved. CCTK did not report reboot status; restart may be needed for BIOS changes to take effect. ") + compact(result.output())
                    : "CCTK reported an apply error; a follow-up scan recorded the actual state. " + result.error());
            setBusy(false, null);
            if (result.error() == null) showInfo("Apply complete", reboot
                    ? "CCTK indicates a restart may be required. The monitor saved a follow-up scan. Restart the computer when convenient."
                    : "CCTK accepted the selected values and the monitor scanned again, but CCTK did not report reboot status. A restart may be needed before some BIOS changes take effect. The app will not restart automatically.");
        });
    }

    private static List<List<String>> buildApplyCommands(List<SettingRow> selected) {
        List<List<String>> commands = new ArrayList<>();
        List<String> flaggedOptions = new ArrayList<>();
        Map<String, List<String>> subOptions = new LinkedHashMap<>();
        for (SettingRow row : selected) {
            if (row.subOption != null) {
                subOptions.computeIfAbsent(row.parentCommand, ignored -> new ArrayList<>())
                        .add("--" + row.subOption + "=" + row.proposed.get());
            } else if (row.parentCommand != null) {
                commands.add(List.of(row.parentCommand + "=" + row.proposed.get()));
            } else {
                flaggedOptions.add("--" + row.name + "=" + row.proposed.get());
            }
        }
        if (!flaggedOptions.isEmpty()) commands.add(0, flaggedOptions);
        for (var group : subOptions.entrySet()) {
            List<String> command = new ArrayList<>();
            command.add(group.getKey());
            command.addAll(group.getValue());
            commands.add(command);
        }
        return commands;
    }

    private static boolean cctkReportsReboot(String output) {
        if (output == null || output.isBlank()) return false;
        String lower = output.toLowerCase(Locale.ROOT);
        if (lower.contains("no reboot required") || lower.contains("reboot not required")
                || lower.contains("no restart required") || lower.contains("restart not required")) return false;
        return Pattern.compile("(?:reboot|restart).{0,50}(?:required|pending)|(?:required|pending).{0,50}(?:reboot|restart)")
                .matcher(lower).find();
    }

    private void rebuildTabs() {
        if (tabs == null) return;
        String query = searchField == null ? "" : searchField.getText().trim().toLowerCase(Locale.ROOT);
        List<SettingRow> visibleRows = rows.stream().filter(row -> matchesSearch(row, query)).toList();
        Map<String, List<SettingRow>> groups = new LinkedHashMap<>();
        List<SettingRow> favorites = visibleRows.stream().filter(row -> row.favorite.get()).toList();
        List<SettingRow> recent = visibleRows.stream().filter(this::isRecent).toList();
        tabs.getTabs().clear();
        if (!favorites.isEmpty()) addTab("Favorites", "★", favorites);
        if (!recent.isEmpty()) addTab("Recent", "◷", recent);
        for (SettingRow row : visibleRows) groups.computeIfAbsent(category(row.name), ignored -> new ArrayList<>()).add(row);
        for (var group : groups.entrySet()) {
            String glyph = switch (group.getKey()) {
                case "Boot" -> "⇧";
                case "Security" -> "◈";
                case "Power" -> "ϟ";
                case "Processor" -> "▦";
                case "Devices" -> "⌘";
                default -> "▤";
            };
            addTab(group.getKey(), glyph, group.getValue());
        }
        if (tabs.getTabs().isEmpty()) {
            Label empty = new Label(query.isEmpty() ? "No settings are available for this system." : "No settings match your search.");
            empty.getStyleClass().add("empty-state");
            tabs.getTabs().add(new Tab("Settings", empty));
        }
    }

    private void addTab(String title, String glyph, List<SettingRow> source) {
        Label icon = new Label(glyph);
        icon.getStyleClass().add("tab-icon");
        Tab tab = new Tab(title + "  " + source.size(), createTable(source));
        tab.setGraphic(icon);
        tab.setClosable(false);
        tabs.getTabs().add(tab);
    }

    private static boolean matchesSearch(SettingRow row, String query) {
        return query.isEmpty() || row.displayName().toLowerCase(Locale.ROOT).contains(query)
                || row.current.get().toLowerCase(Locale.ROOT).contains(query)
                || category(row.name).toLowerCase(Locale.ROOT).contains(query);
    }

    private boolean isRecent(SettingRow row) {
        String before = previousValues.get(id(row.name, row.occurrence));
        if (before == null) return false;
        if (row.subOption != null) {
            String[] pair = before.split(":", 2);
            if (pair.length != 2 || !pair[0].equalsIgnoreCase(row.subOption)) return false;
            before = pair[1];
        }
        return !Objects.equals(before, row.current.get());
    }

    private TableView<SettingRow> createTable(List<SettingRow> source) {
        TableView<SettingRow> table = new TableView<>(FXCollections.observableArrayList(source));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<SettingRow, Boolean> select = new TableColumn<>("Stage");
        select.setMaxWidth(72);
        select.setCellValueFactory(c -> c.getValue().selected);
        select.setCellFactory(c -> new TableCell<>() {
            private final CheckBox box = new CheckBox();
            { box.setOnAction(e -> { SettingRow r = getTableView().getItems().get(getIndex()); r.selected.set(box.isSelected()); updateActions(); }); }
            @Override protected void updateItem(Boolean value, boolean empty) {
                super.updateItem(value, empty);
                if (empty) setGraphic(null);
                else { SettingRow row = getTableView().getItems().get(getIndex()); box.setSelected(row.selected.get()); box.setDisable(!row.editable); setGraphic(box); }
            }
        });
        TableColumn<SettingRow, String> setting = textColumn("Setting", SettingRow::displayName, 210);
        TableColumn<SettingRow, Boolean> favorite = new TableColumn<>("★");
        favorite.setMaxWidth(56);
        favorite.setCellValueFactory(c -> c.getValue().favorite);
        favorite.setCellFactory(c -> new TableCell<>() {
            private final Button star = new Button();
            {
                star.getStyleClass().add("favorite-button");
                star.setOnAction(event -> {
                    SettingRow row = getTableView().getItems().get(getIndex());
                    row.favorite.set(!row.favorite.get());
                    String rowId = id(row.name, row.occurrence);
                    if (row.favorite.get()) favoriteIds.add(rowId); else favoriteIds.remove(rowId);
                    saveFavorites();
                    rebuildTabs();
                });
            }
            @Override protected void updateItem(Boolean value, boolean empty) {
                super.updateItem(value, empty);
                if (empty) setGraphic(null);
                else { star.setText(Boolean.TRUE.equals(value) ? "★" : "☆"); star.getStyleClass().removeAll("favorite-active", "favorite-inactive"); star.getStyleClass().add(Boolean.TRUE.equals(value) ? "favorite-active" : "favorite-inactive"); setGraphic(star); }
            }
        });
        TableColumn<SettingRow, String> current = textColumn("Current value", r -> r.current.get(), 170);
        TableColumn<SettingRow, String> editor = new TableColumn<>("New value");
        editor.setCellValueFactory(c -> c.getValue().proposed);
        editor.setCellFactory(c -> new TableCell<>() {
            private final ComboBox<Choice> combo = new ComboBox<>();
            private final ToggleSwitch toggle = new ToggleSwitch();
            private final Button scheduleEditor = new Button("Configure schedule…");
            private final Button rawEditor = new Button("Edit value…");
            private boolean updating;
            { combo.setMaxWidth(Double.MAX_VALUE); combo.setCellFactory(list -> new ChoiceCell()); combo.setButtonCell(new ChoiceCell());
              combo.setOnAction(e -> { if (getIndex() >= 0 && getIndex() < getTableView().getItems().size()) {
                  Choice choice = combo.getValue(); if (choice != null) getTableView().getItems().get(getIndex()).proposed.set(choice.value()); updateActions();
              }});
              toggle.selectedProperty().addListener((obs, wasSelected, selected) -> {
                  if (updating || getIndex() < 0 || getIndex() >= getTableView().getItems().size()) return;
                  SettingRow row = getTableView().getItems().get(getIndex());
                  String target = booleanValue(row.choices, selected);
                  if (target != null) { row.proposed.set(target); toggle.setText(target); updateActions(); }
              });
              scheduleEditor.setOnAction(e -> {
                  if (getIndex() < 0 || getIndex() >= getTableView().getItems().size()) return;
                  SettingRow row = getTableView().getItems().get(getIndex());
                  editAdvancedSchedule(row);
              });
              rawEditor.setOnAction(e -> {
                  if (getIndex() < 0 || getIndex() >= getTableView().getItems().size()) return;
                  SettingRow row = getTableView().getItems().get(getIndex());
                  editRawValue(row);
              }); }
            @Override protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                if (empty) { setGraphic(null); return; }
                SettingRow row = getTableView().getItems().get(getIndex());
                if (row.meta.advancedSchedule()) {
                    scheduleEditor.setDisable(!row.editable);
                    scheduleEditor.setText(row.proposed.get().toLowerCase(Locale.ROOT).startsWith("enabled")
                            ? "Configure schedule…" : "Set charge days…");
                    setGraphic(scheduleEditor);
                    return;
                }
                if (row.meta.rawValue() || row.meta.range() != null) {
                    rawEditor.setDisable(!row.editable);
                    rawEditor.setText(row.proposed.get().isEmpty() ? "Set value…" : "Edit value…");
                    setGraphic(rawEditor);
                    return;
                }
                if (row.choices.isEmpty()) { setGraphic(new Label("No CCTK choice list")); return; }
                combo.setDisable(!row.editable);
                combo.setItems(FXCollections.observableArrayList(row.choices));
                Choice selected = row.choices.stream().filter(ch -> ch.value().equals(row.proposed.get())).findFirst().orElse(null);
                combo.setValue(selected);
                if (!row.editable) { setGraphic(combo); return; }
                if (isBooleanSwitch(row.choices)) {
                    updating = true;
                    String target = row.proposed.get();
                    boolean on = isOnValue(row.choices, target);
                    toggle.setSelected(on);
                    toggle.setText(target);
                    updating = false;
                    setGraphic(toggle);
                    return;
                }
                setGraphic(combo);
            }
        });
        TableColumn<SettingRow, String> availability = textColumn("Device support", r -> r.editable ? "Configurable" : "Read only / unavailable", 150);
        table.getColumns().setAll(select, favorite, setting, current, editor, availability);
        return table;
    }

    private static TableColumn<SettingRow, String> textColumn(String title,
            java.util.function.Function<SettingRow, String> value, double width) {
        TableColumn<SettingRow, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(c -> new SimpleStringProperty(value.apply(c.getValue())));
        return column;
    }

    private void editAdvancedSchedule(SettingRow row) {
        SettingEditorDialogs.editAdvancedSchedule(stage, row.proposed.get()).ifPresent(value -> {
            row.proposed.set(value); row.selected.set(true); updateActions();
            rebuildTabs();
        });
    }

    private void editRawValue(SettingRow row) {
        SettingEditorDialogs.editRawValue(stage, row.name, row.proposed.get(), row.meta).ifPresent(proposed -> {
            row.proposed.set(proposed); row.selected.set(true); updateActions(); rebuildTabs();
        });
    }

    private <T> void runTask(String name, TaskAction<T> action, java.util.function.Consumer<T> success) {
        Task<T> task = new Task<>() { @Override protected T call() throws Exception { return action.run(); } };
        task.setOnSucceeded(e -> success.accept(task.getValue()));
        task.setOnFailed(e -> { setBusy(false, null); status.setText(name + " failed: " + task.getException().getMessage()); showError(name + " failed", task.getException()); });
        Thread thread = new Thread(task, "bios-monitor-" + name);
        thread.setDaemon(true);
        thread.start();
    }

    private void setBusy(boolean busy, String message) {
        if (message != null) status.setText(message);
        if (scanButton != null) scanButton.setDisable(busy);
        if (tabs != null) tabs.setDisable(busy);
        if (applyButton != null) applyButton.setDisable(busy || rows.stream().noneMatch(r -> r.selected.get() && r.editable));
        if (revertButton != null) revertButton.setDisable(busy || previousValues.isEmpty());
        if (scanOverlay != null) {
            scanOverlay.setVisible(busy);
            scanOverlay.setManaged(busy);
            if (busy && scanMessage != null) {
                scanMessage.setText(message == null ? "Working…" : message);
                scanDetail.setText(rows.isEmpty() ? "First scan · discovering BIOS settings and supported options"
                        : "Reading settings, checking device support, and updating scan history");
            }
        }
    }

    private void updateProgressMessage(String message) {
        status.setText(message);
        if (scanMessage != null) scanMessage.setText(message);
    }

    private void updateActions() {
        if (applyButton != null) applyButton.setDisable(rows.stream().noneMatch(r -> r.selected.get() && r.editable));
        if (revertButton != null) revertButton.setDisable(previousValues.isEmpty());
    }

    private Map<String, String> loadPreviousSnapshot() throws SQLException {
        Map<String, String> values = new HashMap<>();
        try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + BiosMonitor.DB_FILE);
             Statement stmt = db.createStatement();
             ResultSet scans = stmt.executeQuery("SELECT id FROM scans ORDER BY id DESC LIMIT 2")) {
            if (!scans.next() || !scans.next()) return values;
            long previousId = scans.getLong(1);
            try (PreparedStatement ps = db.prepareStatement("SELECT setting_name,occurrence,setting_value FROM bios_settings WHERE scan_id=?")) {
                ps.setLong(1, previousId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) values.put(id(rs.getString(1), rs.getInt(2)), rs.getString(3));
                }
            }
        }
        return values;
    }

    private void loadFavorites() {
        Path file = BiosMonitor.DATA_DIR.resolve(FAVORITES_FILE);
        if (!Files.isRegularFile(file)) return;
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
            for (String value : properties.stringPropertyNames()) {
                if (Boolean.parseBoolean(properties.getProperty(value))) favoriteIds.add(value);
            }
        } catch (IOException ignored) { }
    }

    private void saveFavorites() {
        Properties properties = new Properties();
        favoriteIds.forEach(id -> properties.setProperty(id, "true"));
        Path file = BiosMonitor.DATA_DIR.resolve(FAVORITES_FILE);
        try {
            Files.createDirectories(BiosMonitor.DATA_DIR);
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                properties.store(writer, "Favorite BIOS settings");
            }
        } catch (IOException ex) {
            status.setText("Could not save favorites: " + ex.getMessage());
        }
    }

    private static String category(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("boot") || n.contains("uefi") || n.contains("fastboot")) return "Boot";
        if (n.contains("pwd") || n.contains("password") || n.contains("secure") || n.contains("tpm") || n.contains("admin")) return "Security";
        if (n.contains("battery") || n.contains("power") || n.contains("sleep") || n.contains("wake") || n.contains("autoOn".toLowerCase(Locale.ROOT))) return "Power";
        if (n.contains("cpu") || n.contains("core") || n.contains("speed") || n.contains("virtual")) return "Processor";
        if (n.contains("wireless") || n.contains("bluetooth") || n.contains("usb") || n.contains("sata") || n.contains("camera") || n.contains("audio") || n.contains("nic")) return "Devices";
        return "Other";
    }

    private static String id(String name, int occurrence) { return name + "#" + occurrence; }
    private static String compact(String s) { return s == null ? "" : s.strip().replaceAll("\\s+", " "); }

    private static boolean isRoot() {
        return BiosMonitor.isRunningAsRoot();
    }

    private void showError(String title, Throwable ex) {
        Alert alert = new Alert(Alert.AlertType.ERROR, ex.getMessage(), ButtonType.OK);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.show();
    }

    private void showInfo(String title, String message) {
        if (messageOverlay == null) return;
        Label mark = new Label(title.toLowerCase(Locale.ROOT).contains("restart")
                || message.toLowerCase(Locale.ROOT).contains("restart")
                || message.toLowerCase(Locale.ROOT).contains("reboot") ? "↻" : "✓");
        mark.getStyleClass().add("dialog-mark");
        Label heading = new Label(title);
        heading.getStyleClass().add("h2");
        Label body = new Label(message);
        body.setWrapText(true);
        body.getStyleClass().add("dialog-copy");
        Button close = new Button("Got it");
        close.getStyleClass().add("accent");
        close.setOnAction(event -> {
            messageOverlay.setVisible(false);
            messageOverlay.setManaged(false);
        });
        VBox card = new VBox(18, mark, heading, body, close);
        card.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        card.setMaxWidth(510);
        card.getStyleClass().add("dialog-card");
        messageOverlay.getChildren().setAll(card);
        messageOverlay.setVisible(true);
        messageOverlay.setManaged(true);
    }

    private record ScanResult(List<SettingRow> rows, Map<String, String> previous, String machine) {}
    @FunctionalInterface private interface TaskAction<T> { T run() throws Exception; }
    private record ApplyResult(String output, String error, ScanResult scan) {}

    private static final class ChoiceCell extends ListCell<Choice> {
        @Override protected void updateItem(Choice choice, boolean empty) {
            super.updateItem(choice, empty);
            setText(empty || choice == null ? null : choice.toString());
            setDisable(!empty && choice != null && !choice.supported());
            setStyle(!empty && choice != null && !choice.supported() ? "-fx-opacity: 0.45;" : "");
        }
    }

}
