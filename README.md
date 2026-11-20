# Dell BIOS Monitor

A Java 17+ JavaFX desktop application for Dell Command | Configure (CCTK). It stores each successful BIOS export in SQLite, compares it with the previous scan, and lets you stage and apply supported BIOS setting changes in batches.

## Requirements

- Linux with a graphical desktop session
- Java 17+
- Maven 3.8+ (to build)
- Dell Command | Configure installed; default CCTK path: `/opt/dell/dcc/cctk`
- `sudo` access; the app requests the Linux account password in its own window before scanning

The application uses JavaFX and AtlantaFX. CCTK must be installed and functional on the Dell system; BIOS settings are device and firmware specific.

## Build

```bash
mvn clean package
```

This produces `target/dell-bios-monitor.jar`.

## Run

```bash
java -jar target/dell-bios-monitor.jar
```

The app prompts for the sudo password, validates it, then continues in the same window. CCTK commands run through `sudo`; the GUI stays under the logged-in account. The password is not saved. To perform one scan from a terminal without opening the GUI, use:

```bash
sudo java -jar target/dell-bios-monitor.jar --cli
```

Optional path overrides:

```bash
sudo java -Dcctk.path=/opt/dell/dcc/cctk \
  -Dbiosmonitor.dataDir=/var/lib/dell-bios-monitor \
  -jar target/dell-bios-monitor.jar --cli
```

## Dashboard behavior

- A branded dashboard header includes search, icon-led actions, and device details. The initial scan shows a splash-style progress card; later scans use the same focused progress display.
- Switch between light and dark themes from the header; the choice is saved locally. Apply completion and restart guidance appears in an in-app dialog.
- Star a setting to keep it in the persistent **Favorites** tab. The **Recent** tab highlights values that differ from the prior successful scan.
- Settings are grouped into tabs. CCTK help supplies the possible arguments; the `+` marker identifies values supported by the current machine. Other listed values remain visible and disabled.
- The dashboard includes only settings listed with a `--` option in CCTK's global help. Bare commands and their subsettings are omitted from the dashboard; successful scans still retain the complete CCTK export and history in SQLite.
- Stage one or more settings, inspect current and proposed values, then confirm **Apply all selected** once to submit the batch to CCTK.
- **Revert to previous scan** stages the prior scan’s values where CCTK reports those values as supported. Review and confirm that batch like any other apply.
- After an apply attempt, the app scans again so the database records the actual resulting state, including partial outcomes if CCTK reports an error.
- After a successful apply, the app displays whether CCTK reported a restart requirement. When CCTK provides no restart status, the app states that a restart may be needed; it never restarts automatically.
- Settings CCTK marks read-only and repeated settings that cannot be individually addressed through this interface remain read-only.

BIOS setup passwords are separate from the Linux sudo password. If BIOS password protection prevents CCTK from changing a setting, the app reports CCTK’s error and refreshes the scan.

## Source layout

- `src/main/java/com/example/biosmonitor/BiosMonitor.java` — application entry point, CCTK export lifecycle, SQLite history, and scan comparison
- `src/main/java/com/example/biosmonitor/cctk/CctkSupport.java` — CCTK process execution and help-output parsing
- `src/main/java/com/example/biosmonitor/ui/BiosMonitorApp.java` — JavaFX dashboard, scan flow, and batch apply workflow
- `src/main/java/com/example/biosmonitor/ui/SettingRow.java` — dashboard setting state and row model
- `src/main/java/com/example/biosmonitor/ui/SettingEditorDialogs.java` — editors for structured BIOS values

## Files created

- `~/.local/share/dell-bios-monitor/bios-history.sqlite` — scan history, settings, and detected changes
- `~/.local/share/dell-bios-monitor/current-bios.ini` — latest native CCTK export

The database contains `scans`, `bios_settings`, `bios_changes`, and `system_info` tables. Repeated settings are retained with occurrence numbers; empty values are retained; lines beginning with `;` are treated as comments.

The app uses `$XDG_DATA_HOME/dell-bios-monitor` when `XDG_DATA_HOME` is set. On first sign-in after upgrading from the old root-run version, it attempts to move existing data from `/var/lib/dell-bios-monitor` into the user data directory.

The first successful run creates a baseline. Later scans compare against the immediately previous successful scan and preserve full history. Failed or empty exports are not stored. Exports can contain service tags, UUIDs, and other system identifiers; keep the database private to your user account.

## Flatpak package

The Flatpak bundles the Java runtime and GUI, then invokes host `sudo` through Flatpak's host-spawn interface for CCTK. This requires the Flatpak host-spawn D-Bus permission. Dell Command | Configure must still be installed on the host at `/opt/dell/dcc/cctk`; the vendor utility and its privileged system integration are not bundled.

Install Flatpak and Flatpak Builder for your distribution, add Flathub if needed, then build a portable bundle:

```bash
flatpak remote-add --user --if-not-exists flathub https://flathub.org/repo/flathub.flatpakrepo
./packaging/flatpak/build-bundle.sh
flatpak install --user --bundle target/flatpak/dell-bios-monitor.flatpak
```

The manifest and build script are under `packaging/flatpak`. Bundle creation uses the current Flathub Freedesktop runtime and OpenJDK 25 extension. The resulting `.flatpak` file can be installed on Linux distributions that support Flatpak; the host CCTK requirement remains.
