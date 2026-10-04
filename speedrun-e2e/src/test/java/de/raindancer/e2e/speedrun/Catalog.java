package de.raindancer.e2e.speedrun;

import de.raindancer.core.data.settings.SettingsSchema;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.speedrun.SpeedrunLobbyState;
import de.raindancer.modules.speedrun.SpeedrunSettings;
import de.raindancer.modules.speedrun.manhunt.ManhuntGame;
import de.raindancer.modules.speedrun.manhunt.ManhuntSettings;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Everything in RainsSpeedrun a player or an admin can do, read from the code itself — never a list
 * kept by hand beside it, which would be the first thing to fall behind. Each entry is one id; the
 * scenarios say which ids they play ({@link Covers}), and {@link CoverageTest} fails for any id
 * nobody plays.
 *
 * <ul>
 *   <li>{@code word:speedrun:<w>} every {@code /speedrun} word; {@code refused:speedrun:<w>} each one
 *       an ordinary player is refused.</li>
 *   <li>{@code word:manhunt:<w>}, {@code refused:manhunt:<w>}; {@code word:whitelist:<w>},
 *       {@code refused:whitelist:<w>}.</li>
 *   <li>{@code command:<name>} every command; {@code refused:<name>} those an ordinary player may not use.</li>
 *   <li>{@code menu:<Class>} every page — its every button is checked at run time ({@link ZzRuntimeCoverageTest}).</li>
 *   <li>{@code setting:<key>} every setting of the lobby and of Manhunt.</li>
 *   <li>{@code chat:<message-key>} every message with a click in it; {@code chat:code:<label>} every
 *       button the code draws itself.</li>
 *   <li>{@code state:<STATE>} every lobby state; {@code transition:<FROM>-><TO>} every way between them.</li>
 *   <li>{@code behaviour:…} what the brief names that is not one of the above.</li>
 * </ul>
 */
final class Catalog {

    /** One thing that has to be played, and which kind it is. */
    record Entry(String id, String category) {
    }

    /** Every way the lobby moves between its states — see SpeedrunLobby's own state() and its callers. */
    static final List<String> TRANSITIONS = List.of(
            "READY->COUNTDOWN",      // the start block, /speedrun start
            "COUNTDOWN->RUNNING",    // the countdown reaches zero
            "COUNTDOWN->READY",      // refused at zero, or reset mid-countdown
            "RUNNING->PAUSED",       // every racer offline
            "PAUSED->RUNNING",       // a racer back
            "RUNNING->FINISHED",     // the goal, a death policy, the game's own end
            "FINISHED->READY",       // the world remade
            "RUNNING->READY",        // /speedrunreset mid-run
            "READY->RUNNING",        // /speedrun resume over a world as it stands
            "FINISHED->RUNNING");    // a resume over a finished run

    /** What the brief asks for that is no word, page, setting or button of its own. */
    static final List<String> BEHAVIOURS = List.of(
            "behaviour:race:clean-slate", "behaviour:race:practice-kit", "behaviour:race:hud",
            "behaviour:race:countdown-freeze", "behaviour:race:goal-ends-run",
            "behaviour:manhunt:sides", "behaviour:manhunt:hunter-compass", "behaviour:manhunt:runner-compass",
            "behaviour:manhunt:team-compass", "behaviour:manhunt:structure-compass",
            "behaviour:manhunt:head-start-freeze", "behaviour:manhunt:give-all",
            "behaviour:manhunt:offline-grace", "behaviour:manhunt:caught",
            "behaviour:compass:no-chest", "behaviour:compass:no-drop", "behaviour:compass:back-after-death",
            "behaviour:compass:points-at-runner", "behaviour:compass:right-click-cycles",
            "behaviour:variant:lives", "behaviour:variant:respawn-delay", "behaviour:variant:glowing",
            "behaviour:variant:head-start-scaling",
            "behaviour:latejoin:speedrun:OFF", "behaviour:latejoin:speedrun:SPECTATE", "behaviour:latejoin:speedrun:RACE",
            "behaviour:latejoin:manhunt:OFF", "behaviour:latejoin:manhunt:SPECTATE", "behaviour:latejoin:manhunt:RACE",
            "behaviour:reconnect:keeps-state", "behaviour:resume:after-restart",
            "behaviour:migration:manhunt-files", "behaviour:migration:history-to-core");

    /**
     * Entries no player can ever reach on a working server, each with why — kept in the catalogue so the
     * report shows them, never silently dropped. An entry here that becomes reachable is a test failure
     * ({@link CoverageTest}): it has to get a scenario then.
     */
    static final Map<String, String> NOT_PLAYABLE = Map.of(
            "chat:manhunt.settings-unavailable",
            "only sent when Core's settings screen is not running — never on a server where RainsCore started; "
                    + "unit-tested in Pages",
            "chat:manhunt.stats.usage",
            "only sent to the console (a player's /manhunt stats opens their own page), which cannot click");

    private Catalog() {
    }

    static List<Entry> all() {
        List<Entry> all = new ArrayList<>();
        Map<String, PermissionDefault> defaults = permissionDefaults();
        speedrunWords(all, defaults);
        manhuntWords(all);
        commands(all, defaults);
        menus(all);
        settings(all);
        chatButtons(all);
        for (SpeedrunLobbyState state : SpeedrunLobbyState.values()) {
            all.add(new Entry("state:" + state.name(), "states"));
        }
        TRANSITIONS.forEach(transition -> all.add(new Entry("transition:" + transition, "states")));
        BEHAVIOURS.forEach(behaviour -> all.add(new Entry(behaviour, "behaviours")));
        return all;
    }

    // ---------------------------------------------------------------------------- reading the code

    private static Map<String, PermissionDefault> permissionDefaults() {
        Map<String, PermissionDefault> defaults = new LinkedHashMap<>();
        for (Permission permission : de.raindancer.modules.speedrun.util.PermissionNodes.declared()) {
            defaults.put(permission.getName(), permission.getDefault());
        }
        for (Permission permission : de.raindancer.modules.speedrun.manhunt.util.PermissionNodes.declared()) {
            defaults.put(permission.getName(), permission.getDefault());
        }
        return defaults;
    }

    /** Whether an ordinary player — no op, no node given — would be refused this node. */
    private static boolean refusedByDefault(String node, Map<String, PermissionDefault> defaults) {
        return node != null && defaults.getOrDefault(node, PermissionDefault.OP) == PermissionDefault.OP;
    }

    private static void speedrunWords(List<Entry> all, Map<String, PermissionDefault> defaults) {
        try {
            Class<?> command = Class.forName("de.raindancer.modules.speedrun.SpeedrunJoinCommand");
            Field field = command.getDeclaredField("WORDS");
            field.setAccessible(true);
            for (Object word : (List<?>) field.get(null)) {
                Method name = word.getClass().getDeclaredMethod("name");
                Method access = word.getClass().getDeclaredMethod("access");
                name.setAccessible(true);
                access.setAccessible(true);
                String w = (String) name.invoke(word);
                Enum<?> rule = (Enum<?>) access.invoke(word);
                Method node = rule.getClass().getDeclaredMethod("node");
                node.setAccessible(true);
                all.add(new Entry("word:speedrun:" + w, "words"));
                // START is refused by its rule — start-block-staff-only — not by default.
                if (rule.name().equals("START") || refusedByDefault((String) node.invoke(rule), defaults)) {
                    all.add(new Entry("refused:speedrun:" + w, "refusals"));
                }
            }
        } catch (ReflectiveOperationException unreadable) {
            throw new IllegalStateException("SpeedrunJoinCommand.WORDS changed shape", unreadable);
        }
    }

    private static void manhuntWords(List<Entry> all) {
        try {
            Class<?> command = Class.forName("de.raindancer.modules.speedrun.manhunt.command.ManhuntCommand");
            for (String list : List.of("PLAYER_WORDS", "ADMIN_WORDS")) {
                Field field = command.getDeclaredField(list);
                field.setAccessible(true);
                for (Object word : (Collection<?>) field.get(null)) {
                    all.add(new Entry("word:manhunt:" + word, "words"));
                    if (list.equals("ADMIN_WORDS")) {
                        all.add(new Entry("refused:manhunt:" + word, "refusals"));
                    }
                }
            }
        } catch (ReflectiveOperationException unreadable) {
            throw new IllegalStateException("ManhuntCommand's word lists changed shape", unreadable);
        }
        // /whitelist's own words — see WhitelistCommand.execute.
        for (String word : List.of("open", "close", "clear", "vip")) {
            all.add(new Entry("word:whitelist:" + word, "words"));
            all.add(new Entry("refused:whitelist:" + word, "refusals"));
        }
    }

    private static void commands(List<Entry> all, Map<String, PermissionDefault> defaults) {
        List<ModuleCommand> declared = new ArrayList<>(speedrunCommands());
        declared.addAll(ManhuntGame.commands());
        for (ModuleCommand command : declared) {
            all.add(new Entry("command:" + command.name(), "commands"));
            String node = command.permission() != null ? command.permission() : command.handler().permission();
            if (refusedByDefault(node, defaults)) {
                all.add(new Entry("refused:" + command.name(), "refusals"));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ModuleCommand> speedrunCommands() {
        try {
            Method declared = Class.forName("de.raindancer.modules.speedrun.SpeedrunCommands").getDeclaredMethod("declared");
            declared.setAccessible(true);
            return (List<ModuleCommand>) declared.invoke(null);
        } catch (ReflectiveOperationException unreadable) {
            throw new IllegalStateException("SpeedrunCommands.declared changed shape", unreadable);
        }
    }

    /** Every page: each non-abstract Core menu among the module's own classes. */
    private static void menus(List<Entry> all) {
        for (String name : moduleMenus()) {
            all.add(new Entry("menu:" + name, "menus"));
        }
    }

    /** The module's menus, as the probe names them — simple names, Outer.Inner for a nested one. */
    static List<String> moduleMenus() {
        List<String> menus = new ArrayList<>();
        Path location = classesOf(SpeedrunSettings.class);
        try (java.nio.file.FileSystem jar = Files.isDirectory(location) ? null
                : java.nio.file.FileSystems.newFileSystem(location);
             Stream<Path> files = Files.walk(jar == null ? location : jar.getPath("/"))) {
            Path classes = jar == null ? location : jar.getPath("/");
            for (Path file : files.filter(path -> path.toString().endsWith(".class")).sorted().toList()) {
                String className = classes.relativize(file).toString().replace('/', '.').replaceAll("\\.class$", "");
                if (!className.startsWith("de.raindancer.modules.speedrun")) {
                    continue;
                }
                Class<?> type;
                try {
                    type = Class.forName(className, false, Catalog.class.getClassLoader());
                } catch (ClassNotFoundException | LinkageError skipped) {
                    continue;
                }
                if (Menu.class.isAssignableFrom(type) && !Modifier.isAbstract(type.getModifiers())
                        && !type.isAnonymousClass()) {
                    String simple = type.getName().substring(type.getName().lastIndexOf('.') + 1).replace('$', '.');
                    menus.add(simple);
                }
            }
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
        return menus;
    }

    private static Path classesOf(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException wrong) {
            throw new IllegalStateException(wrong);
        }
    }

    private static void settings(List<Entry> all) {
        for (String key : SettingsSchema.of(SpeedrunSettings.class, SpeedrunSettings.DEFAULTS).keys()) {
            all.add(new Entry("setting:" + key, "settings"));
        }
        for (String key : SettingsSchema.of(ManhuntSettings.class, ManhuntSettings.DEFAULTS).keys()) {
            all.add(new Entry("setting:" + key, "settings"));
        }
    }

    private static final Pattern CODE_LABEL = Pattern.compile("\\.label\\(\"([^\"]*)\"\\)");

    /** Every message with a click in it, and every button the code labels itself. */
    private static void chatButtons(List<Entry> all) {
        try (InputStream in = SpeedrunSettings.class.getResourceAsStream("messages.yml")) {
            Map<?, ?> messages = new Yaml().load(in);
            withClicks(messages, "", all);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
        Path sources = Path.of(System.getProperty("e2e.sources", "../speedrun-module/src/main")).resolve("java");
        try (Stream<Path> files = Files.walk(sources)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                Matcher label = CODE_LABEL.matcher(Files.readString(file));
                while (label.find()) {
                    all.add(new Entry("chat:code:" + plain(label.group(1)), "chat buttons"));
                }
            }
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
        // Built from a value rather than a literal: the lobby's own fixes under a refused start, a shared
        // position, and the hunt summary's button.
        for (String fix : List.of("Race for the dragon", "Create the worlds", "Play a plain race",
                "Bring everybody here", "Reset the world")) {
            all.add(new Entry("chat:fix:" + fix, "chat buttons"));
        }
        all.add(new Entry("chat:code:position-share", "chat buttons"));
        all.add(new Entry("chat:manhunt.summary.open-button", "chat buttons"));
    }

    private static void withClicks(Map<?, ?> node, String path, List<Entry> all) {
        for (Map.Entry<?, ?> entry : node.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (entry.getKey() instanceof Boolean on) {
                key = on ? "on" : "off";   // YAML reads a bare on/off as a boolean
            }
            String full = path.isEmpty() ? key : path + "." + key;
            if (entry.getValue() instanceof Map<?, ?> deeper) {
                withClicks(deeper, full, all);
            } else if (String.valueOf(entry.getValue()).contains("<click:")) {
                all.add(new Entry("chat:" + full, "chat buttons"));
            }
        }
    }

    /** A MiniMessage label as the player reads it: {@code [Copy seed]}. */
    static String plain(String miniMessage) {
        return miniMessage.replaceAll("<[^>]*>", "");
    }

    /** Ids by category, for a report. */
    static Map<String, List<String>> byCategory(List<Entry> entries) {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        entries.forEach(entry -> grouped.computeIfAbsent(entry.category(), key -> new ArrayList<>()).add(entry.id()));
        return grouped;
    }

    /** The ids alone. */
    static Set<String> ids(List<Entry> entries) {
        Set<String> ids = new java.util.LinkedHashSet<>();
        entries.forEach(entry -> ids.add(entry.id()));
        return ids;
    }
}
