package com.example.biosmonitor.cctk;

import com.example.biosmonitor.BiosMonitor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** CCTK process execution and parsing of its help output. */
public final class CctkSupport {
    private static final Pattern OPTION = Pattern.compile("--([A-Za-z0-9]+)(\\*)?");

    private CctkSupport() {}

    public static String run(Path executable, List<String> args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.addAll(BiosMonitor.cctkCommand(executable, args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try { process.getInputStream().transferTo(outputBytes); } catch (IOException ignored) { }
        }, "cctk-output-reader");
        reader.setDaemon(true);
        reader.start();
        if (!process.waitFor(90, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("CCTK timed out.");
        }
        reader.join(2_000);
        String output = outputBytes.toString(StandardCharsets.UTF_8);
        if (process.exitValue() != 0)
            throw new IOException("CCTK exit code " + process.exitValue() + ": " + output.strip());
        return output;
    }

    public static Set<String> parseLongOptions(String help) {
        Set<String> result = new HashSet<>();
        Matcher matcher = OPTION.matcher(help);
        while (matcher.find()) result.add(matcher.group(1));
        return result;
    }

    public static Set<String> parseReadOnly(String help) {
        Set<String> result = new HashSet<>();
        Matcher matcher = OPTION.matcher(help);
        while (matcher.find()) if (matcher.group(2) != null) result.add(matcher.group(1));
        return result;
    }

    public static List<Choice> parseChoices(String help) {
        if (help == null) return List.of();
        for (String line : help.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.regionMatches(true, 0, "Arguments:", 0, 10)) continue;
            List<Choice> choices = new ArrayList<>();
            for (String part : trimmed.substring(10).trim().split("\\s*\\|\\s*")) {
                String value = part.trim();
                boolean supported = value.endsWith("+");
                if (supported) value = value.substring(0, value.length() - 1).trim();
                if (!value.isBlank() && !value.startsWith("<")) choices.add(new Choice(value, supported));
            }
            return choices;
        }
        return List.of();
    }

    public static List<SubOptionMeta> parseSubOptions(String help) {
        if (help == null || help.isBlank()) return List.of();
        int marker = help.indexOf("Suboptions:");
        if (marker < 0) return List.of();
        List<SubOptionMeta> result = new ArrayList<>();
        String currentName = null;
        StringBuilder block = new StringBuilder();
        Pattern heading = Pattern.compile("^\\s*([A-Za-z][A-Za-z0-9]*):\\s*$");
        for (String line : help.substring(marker + "Suboptions:".length()).split("\\R")) {
            Matcher matcher = heading.matcher(line);
            if (matcher.matches()) {
                if (currentName != null) addSubOption(result, currentName, block.toString());
                currentName = matcher.group(1);
                block.setLength(0);
            } else if (currentName != null) {
                block.append(line).append('\n');
            }
        }
        if (currentName != null) addSubOption(result, currentName, block.toString());
        return result;
    }

    private static void addSubOption(List<SubOptionMeta> result, String name, String help) {
        ValueRange range = parseValueRange(help);
        List<Choice> choices = parseChoices(help).stream().map(c -> new Choice(c.value(), true)).toList();
        if (range != null || !choices.isEmpty()) result.add(new SubOptionMeta(name, choices, range, help));
    }

    public static ValueRange parseValueRange(String help) {
        if (help == null || help.isBlank()) return null;
        Matcher matcher = Pattern.compile("(?im)^\\s*Arguments:\\s*(\\d+)\\s+to\\s+(\\d+)\\s*$").matcher(help);
        if (!matcher.find()) return null;
        try { return new ValueRange(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))); }
        catch (NumberFormatException ex) { return null; }
    }

    public static boolean hasSyntaxValue(String help, String name) {
        if (help == null || help.isBlank()) return false;
        Pattern pattern = Pattern.compile("(?im)^\\s*cctk\\s+--" + Pattern.quote(name) + "=");
        return pattern.matcher(help).find();
    }

    public static boolean isBooleanSwitch(List<Choice> choices) {
        if (choices.size() != 2 || choices.stream().anyMatch(c -> !c.supported())) return false;
        Set<String> pair = new HashSet<>();
        choices.forEach(c -> pair.add(c.value().toLowerCase(Locale.ROOT)));
        return pair.equals(Set.of("enabled", "disabled")) || pair.equals(Set.of("enable", "disable"))
                || pair.equals(Set.of("on", "off")) || pair.equals(Set.of("yes", "no"))
                || pair.equals(Set.of("true", "false"));
    }

    public static String booleanValue(List<Choice> choices, boolean on) {
        String wanted = choices.stream().map(c -> c.value().toLowerCase(Locale.ROOT))
                .filter(v -> on ? Set.of("enabled", "enable", "on", "yes", "true").contains(v)
                        : Set.of("disabled", "disable", "off", "no", "false").contains(v))
                .findFirst().orElse(null);
        return wanted == null ? null : choices.stream().map(Choice::value)
                .filter(v -> v.equalsIgnoreCase(wanted)).findFirst().orElse(null);
    }

    public static boolean isOnValue(List<Choice> choices, String value) {
        String onValue = booleanValue(choices, true);
        return onValue != null && onValue.equalsIgnoreCase(value);
    }

    public record Choice(String value, boolean supported) {
        @Override public String toString() { return supported ? value : value + " (unsupported)"; }
    }
    public record ValueRange(int minimum, int maximum) {}
    public record SubOptionMeta(String name, List<Choice> choices, ValueRange range, String help) {}
    public record SettingMeta(List<Choice> choices, String help, boolean advancedSchedule,
                       boolean rawValue, ValueRange range, List<SubOptionMeta> subOptions,
                       boolean editable) {}
}
