package com.example.biosmonitor.ui;

import com.example.biosmonitor.cctk.CctkSupport.SettingMeta;
import com.example.biosmonitor.cctk.CctkSupport.ValueRange;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.*;

/** Focused dialogs for CCTK values that need more than a standard choice list. */
final class SettingEditorDialogs {
    private static final List<String> DAYS = List.of("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun");

    private SettingEditorDialogs() {}

    static Optional<String> editAdvancedSchedule(Window owner, String current) {
        Dialog<String> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Advanced battery charge schedule");
        dialog.setHeaderText("Choose charge days and time windows");
        DialogPane pane = dialog.getDialogPane();
        pane.getButtonTypes().setAll(ButtonType.CANCEL, new ButtonType("Use schedule", ButtonBar.ButtonData.OK_DONE));

        ComboBox<String> mode = new ComboBox<>(FXCollections.observableArrayList("Disabled", "Enabled"));
        mode.setValue(current.toLowerCase(Locale.ROOT).startsWith("enabled") ? "Enabled" : "Disabled");
        List<String> times = new ArrayList<>();
        for (int hour = 0; hour < 24; hour++) for (int minute : List.of(0, 15, 30, 45))
            times.add(String.format(Locale.ROOT, "%02d:%02d", hour, minute));
        Map<String, String[]> existing = parseAdvancedSchedule(current);
        Map<String, CheckBox> days = new LinkedHashMap<>();
        Map<String, ComboBox<String>> beginnings = new LinkedHashMap<>();
        Map<String, ComboBox<String>> periods = new LinkedHashMap<>();
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);
        grid.addRow(0, new Label("Day"), new Label("Beginning of day"), new Label("Work period"));
        int index = 1;
        for (String day : DAYS) {
            CheckBox selected = new CheckBox(day);
            ComboBox<String> beginning = new ComboBox<>(FXCollections.observableArrayList(times));
            ComboBox<String> period = new ComboBox<>(FXCollections.observableArrayList(times));
            String[] pair = existing.get(day);
            selected.setSelected(pair != null);
            beginning.setValue(pair == null ? "00:00" : pair[0]);
            period.setValue(pair == null ? "00:00" : pair[1]);
            days.put(day, selected);
            beginnings.put(day, beginning);
            periods.put(day, period);
            grid.addRow(index++, selected, beginning, period);
        }
        Runnable updateEnabled = () -> {
            boolean enabled = "Enabled".equals(mode.getValue());
            for (String day : DAYS) {
                beginnings.get(day).setDisable(!enabled || !days.get(day).isSelected());
                periods.get(day).setDisable(!enabled || !days.get(day).isSelected());
                days.get(day).setDisable(!enabled);
            }
        };
        mode.valueProperty().addListener((obs, before, after) -> updateEnabled.run());
        days.values().forEach(day -> day.selectedProperty().addListener((obs, before, after) -> updateEnabled.run()));
        updateEnabled.run();

        VBox content = new VBox(12, new HBox(10, new Label("Mode"), mode), grid,
                new Label("Times use 24-hour format and 15-minute intervals, as required by CCTK."));
        content.setPadding(new Insets(8));
        pane.setContent(content);
        dialog.setResultConverter(button -> {
            if (button.getButtonData() != ButtonBar.ButtonData.OK_DONE) return null;
            if ("Disabled".equals(mode.getValue())) return "Disabled";
            List<String> entries = new ArrayList<>();
            for (String day : DAYS) if (days.get(day).isSelected())
                entries.add(day + "-" + beginnings.get(day).getValue() + "/" + periods.get(day).getValue());
            return entries.isEmpty() ? "Enabled" : "Enabled," + String.join(",", entries);
        });
        return dialog.showAndWait();
    }

    static Optional<String> editRawValue(Window owner, String setting, String current, SettingMeta meta) {
        Dialog<String> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Set " + setting);
        dialog.setHeaderText("Enter the value using the CCTK syntax below");
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL,
                new ButtonType("Use value", ButtonBar.ButtonData.OK_DONE));
        TextField value = new TextField(current);
        TextArea syntax = new TextArea(meta.help());
        syntax.setEditable(false);
        syntax.setWrapText(true);
        syntax.setPrefRowCount(8);
        VBox content = new VBox(10, new Label("Value"), value, new Label("CCTK help"), syntax);
        content.setPadding(new Insets(8));
        dialog.getDialogPane().setContent(content);
        dialog.setResultConverter(button -> button.getButtonData() == ButtonBar.ButtonData.OK_DONE
                ? value.getText().trim() : null);
        Optional<String> proposed = dialog.showAndWait();
        if (proposed.isEmpty()) return proposed;
        String text = proposed.get();
        if (text.contains("\n") || text.contains("\r")) {
            showError("Invalid value", "CCTK values must fit on one line.");
            return Optional.empty();
        }
        ValueRange range = meta.range();
        if (range != null) {
            try {
                int number = Integer.parseInt(text);
                if (number < range.minimum() || number > range.maximum()) {
                    showError("Invalid value", "Enter a value from " + range.minimum() + " to " + range.maximum() + ".");
                    return Optional.empty();
                }
            } catch (NumberFormatException ex) {
                showError("Invalid value", "Enter a whole number from " + range.minimum() + " to " + range.maximum() + ".");
                return Optional.empty();
            }
        }
        return proposed;
    }

    private static Map<String, String[]> parseAdvancedSchedule(String value) {
        Map<String, String[]> result = new HashMap<>();
        String[] parts = value.split(",");
        for (int i = 1; i < parts.length; i++) {
            String[] pair = parts[i].split("-", 2);
            if (pair.length != 2) continue;
            String[] times = pair[1].split("/", 2);
            if (times.length == 2) result.put(pair[0], times);
        }
        return result;
    }

    private static void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.show();
    }
}
