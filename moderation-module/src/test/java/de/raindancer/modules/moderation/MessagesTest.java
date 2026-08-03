package de.raindancer.modules.moderation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the wording renders as wording.
 *
 * <h2>The defect this is about</h2>
 * MiniMessage prints an unmatched closing tag <em>literally</em>. So
 * {@code "<green><player></white> is now"} — which opens green and closes a white nobody opened — reaches
 * a player as <b>"Raindancer118&lt;/white&gt; is now Helper."</b> It compiles, it passes every other test,
 * and it is only visible by reading a real message on a real server. Which is exactly how it was found:
 * in the console output of the first live promotion.
 *
 * <p>Cheap to check and worth checking, because every line in this file is one somebody reads.
 */
class MessagesTest {

    private static final Path MESSAGES = Path.of("src/main/resources/messages.yml");

    /** A MiniMessage tag: {@code <white>}, {@code </white>}, {@code <player>}, {@code <colour>}. */
    private static final Pattern TAG = Pattern.compile("</?([a-z_][a-z0-9_:#-]*)>");

    /**
     * Tags that are colours and formats — the ones that can be closed.
     *
     * <p>Anything else in angle brackets is a <em>placeholder</em> the code fills in, and those are
     * never closed. Listing the closable ones rather than the placeholders because the placeholders are
     * different per line and the colours are the same everywhere.
     */
    private static final List<String> CLOSABLE = List.of(
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold",
            "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white",
            "bold", "italic", "underlined", "strikethrough", "obfuscated");

    private static List<String> lines() {
        try {
            return Files.readAllLines(MESSAGES);
        } catch (IOException unreadable) {
            throw new AssertionError("could not read messages.yml", unreadable);
        }
    }

    @Test
    @DisplayName("the file is read, so this cannot pass by looking at nothing")
    void theScanIsNotVacuous() {
        assertThat(lines()).hasSizeGreaterThan(50);
        assertThat(String.join("\n", lines())).contains("moderation:");
    }

    @Test
    @DisplayName("no line closes a colour it never opened")
    void noUnmatchedClosingTags() {
        List<String> broken = new ArrayList<>();

        for (String line : lines()) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#") || !trimmed.contains("<")) {
                continue;
            }
            Deque<String> open = new ArrayDeque<>();
            Matcher tags = TAG.matcher(trimmed);
            while (tags.find()) {
                String name = tags.group(1);
                if (!CLOSABLE.contains(name)) {
                    continue;   // a placeholder, which is never opened or closed
                }
                if (tags.group().startsWith("</")) {
                    if (!open.remove(name)) {
                        broken.add(trimmed + "   ← closes <" + name + "> without opening it");
                        break;
                    }
                } else {
                    open.push(name);
                }
            }
        }

        assertThat(broken)
                .as("MiniMessage prints an unmatched closing tag literally, so this reaches a player "
                        + "as the text \"</white>\" in the middle of a sentence")
                .isEmpty();
    }

    @Test
    @DisplayName("every placeholder is written the way the code fills it in")
    void placeholdersAreLowerCase() {
        // Messages.send matches placeholder names literally, so <Player> is not <player> — it renders
        // as the text "<Player>" and the name never appears.
        List<String> odd = new ArrayList<>();
        for (String line : lines()) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                continue;
            }
            Matcher tags = TAG.matcher(trimmed);
            while (tags.find()) {
                String name = tags.group(1);
                if (!name.equals(name.toLowerCase(java.util.Locale.ROOT))) {
                    odd.add(trimmed);
                }
            }
        }
        assertThat(odd).isEmpty();
    }

    /**
     * The words YAML 1.1 turns into booleans.
     *
     * <p>As a <em>key</em>, {@code on:} does not stay the string "on" — SnakeYAML resolves it to the
     * boolean {@code true}, and the section then holds a key called {@code true}. Same for the rest.
     */
    private static final List<String> YAML_BOOLEANS = List.of(
            "on", "off", "yes", "no", "true", "false", "y", "n");

    @Test
    @DisplayName("no message key is a word YAML turns into a boolean")
    void noBooleanKeys() {
        // The defect this is about, seen in game as: "Moderation » <moderation.tool.instakill.on>"
        //
        // The file was valid YAML and the section existed. But `on:` as a key is resolved by SnakeYAML
        // to the boolean true, so the key became `moderation.tool.instakill.true`, and the lookup for
        // `.on` found nothing and printed its own name. This is the same family as YAML's famous
        // "Norway problem", where `no` becomes false.
        List<String> booleanKeys = new ArrayList<>();
        for (String line : lines()) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#") || !trimmed.contains(":")) {
                continue;
            }
            String key = trimmed.substring(0, trimmed.indexOf(':')).trim();
            if (YAML_BOOLEANS.contains(key.toLowerCase(java.util.Locale.ROOT))) {
                booleanKeys.add(line.strip());
            }
        }

        assertThat(booleanKeys)
                .as("YAML resolves these keys to booleans, so the message is stored under 'true' or "
                        + "'false' and every lookup prints the key instead of the line. Quote them or "
                        + "rename them — 'turned-on' rather than 'on'")
                .isEmpty();
    }

    @Test
    @DisplayName("every key the file defines is reachable by the name it appears to have")
    void keysAreReachable() {
        // Reading it the way the plugin does, so a key mangled on the way in shows up here rather than
        // in somebody's chat. Checks the leaf names against the raw text: a section that YAML turned
        // into `true` has a key nothing in the code will ever ask for.
        org.bukkit.configuration.file.YamlConfiguration yaml =
                new org.bukkit.configuration.file.YamlConfiguration();
        try {
            yaml.loadFromString(String.join("\n", lines()));
        } catch (org.bukkit.configuration.InvalidConfigurationException broken) {
            throw new AssertionError("messages.yml does not parse", broken);
        }
        List<String> mangled = new ArrayList<>();
        for (String key : yaml.getKeys(true)) {
            String leaf = key.contains(".") ? key.substring(key.lastIndexOf('.') + 1) : key;
            if (leaf.equals("true") || leaf.equals("false")) {
                mangled.add(key);
            }
        }

        assertThat(mangled)
                .as("these arrived as booleans rather than as the words in the file, so nothing can "
                        + "look them up")
                .isEmpty();
    }

    /** The file, read the way the plugin reads it. */
    private static org.bukkit.configuration.file.YamlConfiguration loaded() {
        org.bukkit.configuration.file.YamlConfiguration yaml =
                new org.bukkit.configuration.file.YamlConfiguration();
        try {
            yaml.loadFromString(String.join("\n", lines()));
        } catch (org.bukkit.configuration.InvalidConfigurationException broken) {
            throw new AssertionError("messages.yml does not parse", broken);
        }
        return yaml;
    }

    @Test
    @DisplayName("every key the code writes out as a literal is defined")
    void everyLiteralKeyExists() {
        // A missing key is not an error anywhere — Messages prints the key itself, so it reaches a
        // player as "<moderation.tool.instakill.on>". Nothing fails, nothing logs, and it is only ever
        // found by somebody reading their own chat.
        org.bukkit.configuration.file.YamlConfiguration yaml = loaded();
        Pattern literal = Pattern.compile("\"(moderation\\.[a-z0-9.\\-]+)\"");

        List<String> undefined = new ArrayList<>();
        for (Path file : sources()) {
            String body = read(file);
            Matcher keys = literal.matcher(body);
            while (keys.find()) {
                String key = keys.group(1);
                // A prefix a key is built from, not a key itself — the next test covers those. Both
                // separators, because "moderation.you-were-" ends in a hyphen and "moderation.tool."
                // in a dot.
                if (key.endsWith(".") || key.endsWith("-") || yaml.isSet(key)) {
                    continue;
                }
                undefined.add(key + "   (" + file.getFileName() + ")");
            }
        }

        assertThat(undefined)
                .as("these are sent by the code and defined nowhere, so a player is shown the key")
                .isEmpty();
    }

    @Test
    @DisplayName("every key the code builds at runtime is defined too")
    void everyBuiltKeyExists() {
        // The class the static scan cannot see, and the one that actually broke: a key assembled from
        // pieces is invisible to any search for a string literal. So the assembly is repeated here, and
        // any combination the code can produce has to exist.
        org.bukkit.configuration.file.YamlConfiguration yaml = loaded();
        List<String> undefined = new ArrayList<>();

        // PunishmentService.tellThem — every kind that leaves somebody on the server to be told.
        for (de.raindancer.core.moderation.punishment.PunishmentKind kind
                : de.raindancer.core.moderation.punishment.PunishmentKind.values()) {
            if (kind == de.raindancer.core.moderation.punishment.PunishmentKind.BAN
                    || kind == de.raindancer.core.moderation.punishment.PunishmentKind.KICK) {
                continue;   // they are on their way out; the kick screen carries the reason
            }
            String key = "moderation.you-were-"
                    + kind.name().toLowerCase(java.util.Locale.ROOT);
            if (!yaml.isSet(key)) {
                undefined.add(key + "   (PunishmentService)");
            }
        }

        // SelfToolCommand — three tools, on and off, for yourself and for somebody else.
        for (de.raindancer.modules.moderation.command.SelfToolCommand.Tool tool
                : de.raindancer.modules.moderation.command.SelfToolCommand.Tool.values()) {
            for (String state : List.of(".turned-on", ".turned-off")) {
                for (String who : List.of("", "-other")) {
                    String key = "moderation.tool." + tool.word() + state + who;
                    if (!yaml.isSet(key)) {
                        undefined.add(key + "   (SelfToolCommand)");
                    }
                }
            }
        }

        assertThat(undefined)
                .as("these are assembled at runtime and defined nowhere — the case a search for a "
                        + "string literal cannot find")
                .isEmpty();
    }

    @Test
    @DisplayName("every refusal a rule can answer with is a message somebody can read")
    void everyRefusalKeyExists() {
        // The rules answer a Verdict carrying a message key, and the commands and screens send it
        // blind. So a refusal nobody defined is a rule that appears to work and tells the player its
        // own source code.
        org.bukkit.configuration.file.YamlConfiguration yaml = loaded();
        List<String> undefined = new ArrayList<>();

        for (String key : List.of(
                de.raindancer.modules.moderation.rules.StaffRule.NO_PERMISSION,
                de.raindancer.modules.moderation.rules.StaffRule.NOT_YOURSELF,
                de.raindancer.modules.moderation.rules.StaffRule.THEY_ARE_IMMUNE,
                de.raindancer.modules.moderation.rules.StaffRule.NOBODY_THERE,
                de.raindancer.modules.moderation.rules.ReportRule.TOO_SHORT,
                de.raindancer.modules.moderation.rules.ReportRule.NOT_YOURSELF,
                de.raindancer.modules.moderation.rules.ReportRule.NOBODY_THERE,
                de.raindancer.modules.moderation.rules.ReportRule.TOO_SOON,
                de.raindancer.modules.moderation.rules.ReportRule.ALREADY_OPEN,
                de.raindancer.modules.moderation.rules.ReportRule.TOO_MANY,
                de.raindancer.modules.moderation.rules.BanLimitRule.TOO_LONG,
                de.raindancer.modules.moderation.rules.BanLimitRule.NOT_FOR_EVER,
                de.raindancer.modules.moderation.rules.BanLimitRule.NOT_YOURS_TO_LIFT,
                de.raindancer.modules.moderation.rules.PromotionRule.NOT_YOURS,
                de.raindancer.modules.moderation.rules.PromotionRule.ONLY_BELOW_YOU,
                de.raindancer.modules.moderation.rules.PromotionRule.NOT_ABOVE_YOU,
                de.raindancer.modules.moderation.rules.PromotionRule.YOURSELF,
                de.raindancer.modules.moderation.rules.PromotionRule.HANDING_OUT_IS_OFF)) {
            if (!yaml.isSet(key)) {
                undefined.add(key);
            }
        }

        assertThat(undefined)
                .as("a rule whose refusal has no wording tells the player its own key")
                .isEmpty();
    }

    private static List<Path> sources() {
        Path root = Path.of("src/main/java/de/raindancer/modules/moderation");
        try (java.util.stream.Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        } catch (IOException unreadable) {
            throw new AssertionError("could not read the module", unreadable);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException unreadable) {
            throw new AssertionError("could not read " + file, unreadable);
        }
    }

    @Test
    @DisplayName("the file parses as YAML at all")
    void itIsValidYaml() {
        // A tab, or a colon inside an unquoted value, and the whole file silently supplies nothing —
        // every line then falls back to its key, which reads as a broken plugin rather than a broken
        // file.
        org.bukkit.configuration.file.YamlConfiguration yaml =
                new org.bukkit.configuration.file.YamlConfiguration();
        try {
            yaml.loadFromString(String.join("\n", lines()));
        } catch (org.bukkit.configuration.InvalidConfigurationException broken) {
            throw new AssertionError("messages.yml does not parse: " + broken.getMessage(), broken);
        }
        assertThat(yaml.getKeys(true)).isNotEmpty();
    }
}
