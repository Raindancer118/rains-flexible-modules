package de.raindancer.e2e;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * What a run proved, two ways.
 *
 * <ul>
 *   <li>What the server saw — the probe's events of every scenario: commands run by players, menus
 *       opened, buttons clicked. Nothing a scenario can claim without doing it.</li>
 *   <li>What a scenario showed and asserted itself — {@link #done}: a refusal said, a chat button whose
 *       effect was seen, a state reached. Written only after the scenario's own assertion passed.</li>
 * </ul>
 */
public final class Coverage {

    private Coverage() {
    }

    private static Path doneFile() {
        return E2e.out().resolve("covered.log");
    }

    /** Records that the scenario just showed {@code id} working — call after its assertion. */
    public static synchronized void done(String id) {
        try {
            Files.writeString(doneFile(), id + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    /** Every id any scenario of this run recorded. */
    public static Set<String> done() {
        try {
            return Files.exists(doneFile()) ? new TreeSet<>(Files.readAllLines(doneFile())) : Set.of();
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    /** One probe event. */
    public record Event(JsonObject json) {

        public String kind() {
            return json.get("event").getAsString();
        }

        public String string(String key) {
            JsonElement value = json.get(key);
            return value == null || value.isJsonNull() ? "" : value.getAsString();
        }

        public int number(String key) {
            JsonElement value = json.get(key);
            return value == null ? -1 : value.getAsInt();
        }
    }

    /** Every event of every scenario of this run — the copies kept under {@code logs/<scenario>/events.log}. */
    public static List<Event> events() {
        Path logs = E2e.out().resolve("logs");
        List<Event> all = new ArrayList<>();
        if (!Files.isDirectory(logs)) {
            return all;
        }
        try (Stream<Path> files = Files.walk(logs)) {
            for (Path file : files.filter(path -> path.getFileName().toString().equals("events.log")).sorted().toList()) {
                for (String line : Files.readAllLines(file)) {
                    if (!line.isBlank()) {
                        all.add(new Event(JsonParser.parseString(line).getAsJsonObject()));
                    }
                }
            }
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
        return all;
    }

    /** The command lines players (never the console) ran. */
    public static List<String> playerCommands(List<Event> events) {
        return events.stream().filter(event -> event.kind().equals("command"))
                .filter(event -> !event.string("sender").equals("console") && !event.string("sender").equals("rcon"))
                .map(event -> event.string("line")).toList();
    }

    /** Every menu class opened, by its simple name. */
    public static Set<String> menusOpened(List<Event> events) {
        Set<String> opened = new LinkedHashSet<>();
        for (Event event : events) {
            if (event.kind().equals("open") && !event.string("holder").isEmpty()) {
                opened.add(simple(event.string("holder")));
            }
        }
        return opened;
    }

    /**
     * Every button a menu offered and nobody clicked, per menu (by simple class name) — buttons read
     * from the menu itself when it opened, told apart as {@link MenuButtons#key} does: a list's entries
     * are one button, any other is its slot and its name without what changes with state.
     */
    public static Map<String, Set<String>> buttonsNeverClicked(List<Event> events, java.util.function.Predicate<String> whichMenus) {
        Map<String, Set<String>> offered = new LinkedHashMap<>();
        Set<String> clicked = new java.util.HashSet<>();
        Set<String> lists = new java.util.HashSet<>();
        Map<String, Map<Integer, String>> shown = new java.util.HashMap<>();
        for (Event event : events) {
            if (event.kind().equals("open") && event.json().has("list") && event.json().get("list").getAsBoolean()) {
                lists.add(event.string("holder"));
            }
        }
        for (Event event : events) {
            String holder = event.string("holder");
            if (simple(holder).isEmpty() || !whichMenus.test(holder)) {
                continue;
            }
            boolean list = lists.contains(holder);
            if (event.kind().equals("open") && event.json().has("buttons")) {
                Map<Integer, String> names = new java.util.HashMap<>();
                event.json().getAsJsonArray("items").forEach(item -> names.put(
                        item.getAsJsonObject().get("slot").getAsInt(), item.getAsJsonObject().get("name").getAsString()));
                shown.put(event.string("player"), names);
                event.json().getAsJsonArray("buttons").forEach(element -> {
                    int slot = element.getAsInt();
                    offered.computeIfAbsent(simple(holder), key -> new java.util.TreeSet<>())
                            .add(MenuButtons.key(simple(holder), slot, names.getOrDefault(slot, ""), list && slot < 36));
                });
            } else if (event.kind().equals("click") && event.json().get("top").getAsBoolean()) {
                int slot = event.number("slot");
                // The name as the page showed it: by the time the click is logged a switch may read its new state.
                String name = shown.getOrDefault(event.string("player"), Map.of()).getOrDefault(slot, event.string("name"));
                clicked.add(MenuButtons.key(simple(holder), slot, name, list && slot < 36));
                clicked.add(MenuButtons.key(simple(holder), slot, event.string("name"), list && slot < 36));
            }
        }
        Map<String, Set<String>> missing = new LinkedHashMap<>();
        offered.forEach((holder, keys) -> {
            Set<String> left = new java.util.TreeSet<>(keys);
            left.removeAll(clicked);
            if (!left.isEmpty()) {
                missing.put(holder, left);
            }
        });
        return missing;
    }

    private static String simple(String className) {
        int dot = className.lastIndexOf('.');
        String simple = dot < 0 ? className : className.substring(dot + 1);
        return simple.contains("$") ? simple.substring(0, simple.indexOf('$')) + "." + simple.substring(simple.indexOf('$') + 1) : simple;
    }
}
