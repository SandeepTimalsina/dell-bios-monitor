package com.example.biosmonitor.ui;

import com.example.biosmonitor.cctk.CctkSupport.Choice;
import com.example.biosmonitor.cctk.CctkSupport.SettingMeta;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/** View model for one BIOS setting row. */
final class SettingRow {
    final String name;
    final int occurrence;
    final int identicalEntries;
    final String subOption;
    final String parentCommand;
    final StringProperty current;
    final StringProperty proposed;
    final BooleanProperty selected = new SimpleBooleanProperty(false);
    final BooleanProperty favorite = new SimpleBooleanProperty(false);
    final java.util.List<Choice> choices;
    final SettingMeta meta;
    final boolean editable;

    SettingRow(String name, int occurrence, String current, int identicalEntries,
               SettingMeta meta, boolean editable) {
        this(name, occurrence, current, identicalEntries, meta, editable, null, null);
    }

    SettingRow(String name, int occurrence, String current, int identicalEntries,
               SettingMeta meta, boolean editable, String subOption, String parentCommand) {
        this.name = name;
        this.occurrence = occurrence;
        this.current = new SimpleStringProperty(current);
        this.identicalEntries = identicalEntries;
        this.subOption = subOption;
        this.parentCommand = parentCommand;
        this.proposed = new SimpleStringProperty(current);
        this.meta = meta;
        this.choices = meta.choices();
        this.editable = editable;
    }

    String displayName() {
        if (subOption != null) return name + " · " + subOption;
        if (identicalEntries > 1) return name + " (" + identicalEntries + " identical entries)";
        return occurrence == 1 ? name : name + " [" + occurrence + "]";
    }
}
