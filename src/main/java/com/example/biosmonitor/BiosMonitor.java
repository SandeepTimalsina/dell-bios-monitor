package com.example.biosmonitor;

import com.example.biosmonitor.ui.BiosMonitorApp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

public final class BiosMonitor {
    public static final Path CCTK = Path.of(
            System.getProperty("cctk.path", "/opt/dell/dcc/cctk"));
    public static final Path DATA_DIR = Path.of(
            System.getProperty("biosmonitor.dataDir", defaultDataDirectory()));
    public static final Path EXPORT_FILE = DATA_DIR.resolve("current-bios.ini");
    public static final Path DB_FILE = DATA_DIR.resolve("bios-history.sqlite");
    private static final int PROCESS_TIMEOUT_SECONDS = 90;

    public record Setting(String name, int occurrence, String value) {}
    public record SystemInfo(String model, String systemId, String biosVersion,
                              String cctkVersion) {}

    public static void main(String[] args) {
        if (Arrays.asList(args).contains("--cli")) {
            runCliScan();
        } else {
            BiosMonitorApp.launchGui(args);
        }
    }

    private static void runCliScan() {
        try {
            scanNow();
        } catch (Exception e) {
            System.err.println("BIOS monitor failed: " + e.getMessage());
            System.exit(1);
        }
    }

    public static long scanNow() throws Exception {
        return scanNow(message -> { });
    }

    public static long scanNow(Consumer<String> progress) throws Exception {
        Files.createDirectories(DATA_DIR);
        progress.accept("CCTK is exporting current BIOS settings…");
        exportWithCctk();
        List<Setting> settings = parseExport(EXPORT_FILE);
        if (settings.isEmpty()) throw new IllegalStateException("No BIOS settings found in " + EXPORT_FILE
                + "; refusing to save an empty snapshot.");
        SystemInfo info = parseSystemInfo(EXPORT_FILE);
        progress.accept("Saving scan history to SQLite…");
        try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE)) {
            configureDatabase(db);
            createSchema(db);
            long scanId = saveSnapshot(db, settings, info);
            printSummary(db, scanId, settings.size());
            return scanId;
        }
    }

    public static List<String> cctkCommand(Path executable, List<String> arguments) {
        List<String> command = privilegedCommand(executable.toString(), arguments);
        return command;
    }

    public static List<String> privilegedCommand(String executable, List<String> arguments) {
        List<String> command = new ArrayList<>();
        if (isRunningAsRoot()) {
            command.add(executable);
        } else if (System.getenv("FLATPAK_ID") != null) {
            command.addAll(List.of("flatpak-spawn", "--host", "sudo", "-n", "--", executable));
        } else {
            command.addAll(List.of("sudo", "-n", "--", executable));
        }
        command.addAll(arguments);
        return command;
    }

    public static boolean isRunningAsRoot() {
        try {
            Process p = new ProcessBuilder("id", "-u").start();
            String uid;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    p.getInputStream(), StandardCharsets.UTF_8))) {
                uid = r.readLine();
            }
            return p.waitFor() == 0 && "0".equals(uid);
        } catch (IOException | InterruptedException ex) { return false; }
    }

    private static String defaultDataDirectory() {
        String xdg = System.getenv("XDG_DATA_HOME");
        String home = System.getProperty("user.home");
        String sudoUser = System.getenv("SUDO_USER");
        if (isRunningAsRoot() && sudoUser != null && !sudoUser.isBlank()) {
            try {
                Process p = new ProcessBuilder("getent", "passwd", sudoUser).start();
                String entry = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                if (p.waitFor() == 0) {
                    String[] fields = entry.split(":");
                    if (fields.length > 5) home = fields[5];
                }
            } catch (IOException | InterruptedException ignored) { }
        }
        Path base = xdg == null || xdg.isBlank()
                ? Path.of(home, ".local", "share") : Path.of(xdg);
        return base.resolve("dell-bios-monitor").toString();
    }

    private static void exportWithCctk() throws IOException, InterruptedException {
        // Always use a new path. Some CCTK builds append to an existing outfile.
        Path freshExport = Files.createTempFile(DATA_DIR, "cctk-export-", ".ini");
        Files.deleteIfExists(freshExport);
        try {
            ProcessBuilder pb = new ProcessBuilder(cctkCommand(
                    CCTK, List.of("--outfile=" + freshExport.toAbsolutePath())));
            pb.redirectErrorStream(true);
            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append(System.lineSeparator());
                    System.out.println("[CCTK] " + line);
                }
            }
            boolean finished = process.waitFor(PROCESS_TIMEOUT_SECONDS,
                    java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("CCTK export timed out.");
            }
            if (process.exitValue() != 0) {
                throw new IOException("CCTK export returned exit code " + process.exitValue()
                        + ". Output: " + output);
            }
            if (!Files.isRegularFile(freshExport) || Files.size(freshExport) == 0) {
                throw new IOException("CCTK did not create a non-empty export file: " + freshExport);
            }
            try { Files.move(freshExport, EXPORT_FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(freshExport, EXPORT_FILE, StandardCopyOption.REPLACE_EXISTING); }
        } finally {
            Files.deleteIfExists(freshExport);
        }
    }

    public static List<Setting> parseExport(Path file) throws IOException {
        List<Setting> result = new ArrayList<>();
        Map<String, Integer> occurrences = new HashMap<>();
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith(";") || line.startsWith("[")
                    || !line.contains("=")) continue;
            int equals = line.indexOf('=');
            String name = line.substring(0, equals).trim();
            String value = line.substring(equals + 1).trim();
            if (name.isEmpty()) continue;
            int occurrence = occurrences.merge(name, 1, Integer::sum);
            result.add(new Setting(name, occurrence, value));
        }
        return result;
    }

    public static SystemInfo parseSystemInfo(Path file) throws IOException {
        Map<String, String> values = new HashMap<>();
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.startsWith(";") || !line.contains("=")) continue;
            int i = line.indexOf('=');
            values.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
        }
        return new SystemInfo(values.getOrDefault("SysName", ""),
                values.getOrDefault("SysId", ""),
                values.getOrDefault("BiosVer", ""),
                values.getOrDefault("CCTKGenEngVer", ""));
    }

    private static void configureDatabase(Connection db) throws SQLException {
        try (Statement s = db.createStatement()) {
            s.execute("PRAGMA foreign_keys = ON");
            s.execute("PRAGMA journal_mode = WAL");
            s.execute("PRAGMA busy_timeout = 5000");
        }
    }

    private static void createSchema(Connection db) throws SQLException {
        String[] ddl = {
            """
            CREATE TABLE IF NOT EXISTS scans (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                scan_time TEXT NOT NULL,
                scan_type TEXT NOT NULL CHECK(scan_type IN ('BASELINE','NORMAL')),
                system_model TEXT,
                system_id TEXT,
                bios_version TEXT,
                cctk_version TEXT,
                setting_count INTEGER NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS bios_settings (
                scan_id INTEGER NOT NULL REFERENCES scans(id) ON DELETE CASCADE,
                setting_name TEXT NOT NULL,
                occurrence INTEGER NOT NULL,
                setting_value TEXT NOT NULL,
                PRIMARY KEY (scan_id, setting_name, occurrence)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS bios_changes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                scan_id INTEGER NOT NULL REFERENCES scans(id) ON DELETE CASCADE,
                setting_name TEXT NOT NULL,
                occurrence INTEGER NOT NULL,
                old_value TEXT,
                new_value TEXT,
                change_type TEXT NOT NULL CHECK(change_type IN ('ADDED','MODIFIED','REMOVED'))
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS system_info (
                scan_id INTEGER PRIMARY KEY REFERENCES scans(id) ON DELETE CASCADE,
                system_model TEXT,
                system_id TEXT,
                bios_version TEXT,
                cctk_version TEXT
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_scans_time ON scans(scan_time)",
            "CREATE INDEX IF NOT EXISTS idx_changes_scan ON bios_changes(scan_id)"
        };
        try (Statement s = db.createStatement()) {
            for (String sql : ddl) s.execute(sql);
        }
    }

    private static long saveSnapshot(Connection db, List<Setting> settings,
                                     SystemInfo info) throws SQLException {
        db.setAutoCommit(false);
        try {
            Long previousScanId = getLatestScanId(db);
            Map<String, String> previous = previousScanId == null
                    ? Map.of() : loadSnapshot(db, previousScanId);

            String scanType = previousScanId == null ? "BASELINE" : "NORMAL";
            long scanId;
            try (PreparedStatement ps = db.prepareStatement("""
                    INSERT INTO scans(scan_time, scan_type, system_model, system_id,
                                      bios_version, cctk_version, setting_count)
                    VALUES(?,?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, Instant.now().toString());
                ps.setString(2, scanType);
                ps.setString(3, info.model());
                ps.setString(4, info.systemId());
                ps.setString(5, info.biosVersion());
                ps.setString(6, info.cctkVersion());
                ps.setInt(7, settings.size());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) throw new SQLException("Could not get scan ID.");
                    scanId = keys.getLong(1);
                }
            }

            try (PreparedStatement ps = db.prepareStatement("""
                    INSERT INTO bios_settings(scan_id, setting_name, occurrence, setting_value)
                    VALUES(?,?,?,?)
                    """)) {
                for (Setting setting : settings) {
                    ps.setLong(1, scanId);
                    ps.setString(2, setting.name());
                    ps.setInt(3, setting.occurrence());
                    ps.setString(4, setting.value());
                    ps.addBatch();
                }
                ps.executeBatch();
            }

            try (PreparedStatement ps = db.prepareStatement("""
                    INSERT INTO system_info(scan_id, system_model, system_id, bios_version, cctk_version)
                    VALUES(?,?,?,?,?)
                    """)) {
                ps.setLong(1, scanId);
                ps.setString(2, info.model());
                ps.setString(3, info.systemId());
                ps.setString(4, info.biosVersion());
                ps.setString(5, info.cctkVersion());
                ps.executeUpdate();
            }

            int changeCount = 0;
            if (previousScanId != null) {
                Map<String, String> current = toMap(settings);
                Set<String> allKeys = new TreeSet<>();
                allKeys.addAll(previous.keySet());
                allKeys.addAll(current.keySet());

                try (PreparedStatement ps = db.prepareStatement("""
                        INSERT INTO bios_changes(scan_id, setting_name, occurrence,
                                                  old_value, new_value, change_type)
                        VALUES(?,?,?,?,?,?)
                        """)) {
                    for (String key : allKeys) {
                        String oldValue = previous.get(key);
                        String newValue = current.get(key);
                        String type = null;
                        if (oldValue == null && newValue != null) type = "ADDED";
                        else if (oldValue != null && newValue == null) type = "REMOVED";
                        else if (!Objects.equals(oldValue, newValue)) type = "MODIFIED";

                        if (type != null) {
                            int split = key.lastIndexOf('#');
                            ps.setLong(1, scanId);
                            ps.setString(2, key.substring(0, split));
                            ps.setInt(3, Integer.parseInt(key.substring(split + 1)));
                            ps.setString(4, oldValue);
                            ps.setString(5, newValue);
                            ps.setString(6, type);
                            ps.addBatch();
                            changeCount++;
                        }
                    }
                    ps.executeBatch();
                }
            }

            db.commit();
            System.out.println("Scan ID: " + scanId + " (" + scanType + ")");
            System.out.println("Settings stored: " + settings.size());
            if (previousScanId == null) {
                System.out.println("Baseline created. Future runs will compare against this scan.");
            } else {
                System.out.println("Changes detected: " + changeCount);
                printChanges(db, scanId);
            }
            return scanId;
        } catch (SQLException | RuntimeException e) {
            db.rollback();
            throw e;
        } finally {
            db.setAutoCommit(true);
        }
    }

    private static Long getLatestScanId(Connection db) throws SQLException {
        try (Statement s = db.createStatement();
             ResultSet rs = s.executeQuery("SELECT id FROM scans ORDER BY id DESC LIMIT 1")) {
            return rs.next() ? rs.getLong(1) : null;
        }
    }

    private static Map<String, String> loadSnapshot(Connection db, long scanId)
            throws SQLException {
        Map<String, String> values = new HashMap<>();
        try (PreparedStatement ps = db.prepareStatement("""
                SELECT setting_name, occurrence, setting_value
                FROM bios_settings WHERE scan_id=?
                """)) {
            ps.setLong(1, scanId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    values.put(key(rs.getString(1), rs.getInt(2)), rs.getString(3));
                }
            }
        }
        return values;
    }

    private static Map<String, String> toMap(List<Setting> settings) {
        Map<String, String> map = new HashMap<>();
        for (Setting s : settings) map.put(key(s.name(), s.occurrence()), s.value());
        return map;
    }

    private static String key(String name, int occurrence) {
        return name + "#" + occurrence;
    }

    private static void printChanges(Connection db, long scanId) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("""
                SELECT setting_name, occurrence, old_value, new_value, change_type
                FROM bios_changes WHERE scan_id=? ORDER BY setting_name, occurrence
                """)) {
            ps.setLong(1, scanId);
            try (ResultSet rs = ps.executeQuery()) {
                boolean any = false;
                while (rs.next()) {
                    any = true;
                    System.out.printf("  %s %s[%d]: %s -> %s%n",
                            rs.getString("change_type"),
                            rs.getString("setting_name"),
                            rs.getInt("occurrence"),
                            printable(rs.getString("old_value")),
                            printable(rs.getString("new_value")));
                }
                if (!any) System.out.println("No BIOS setting changes detected.");
            }
        }
    }

    private static String printable(String value) {
        return value == null ? "<absent>" : value;
    }

    private static void printSummary(Connection db, long scanId, int count)
            throws SQLException {
        System.out.println("Database: " + DB_FILE);
        System.out.println("Export:   " + EXPORT_FILE);
    }
}
