package de.raindancer.modules.hungergames;

import de.raindancer.modules.hungergames.service.AnnouncementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every sentence this module announces has wording behind it, under the right name.
 *
 * <h2>What was seen, by everybody at once</h2>
 * A player earned a sponsor token and was told: <b>{@code TheHungerGames » <sponsor-token-earned>}</b>. Core's
 * {@code Messages} registry is server-wide and flat, so the key is {@code hungergames.sponsor-token-earned};
 * five callers passed the bare name, and Core's answer to a key it does not know is to render the key — with
 * the raw jar name in front, because the fallback carries no brand either.
 *
 * <p>The five were the sponsor token, a shop purchase, a refused purchase, a beacon spawning and a supply drop
 * landing. Every one of them is a line that only ever appears in front of a full server, and not one of them
 * is on a path a unit test walks — which is exactly why the missing wording survived a green build.
 *
 * <h2>Why this reads the source rather than trusting the fix</h2>
 * {@link AnnouncementService#qualified} now puts the namespace on, so all five work today. That does not make
 * this test redundant: it catches the next key that is <em>typed wrong</em> or <em>never written</em>, which is
 * the actual recurring mistake — the namespace was only one of its two forms. A key spelled
 * {@code sponsor-tokens-earned} would be just as invisible, and the fix cannot help with that.
 *
 * <p>So: every announcement key in the source, qualified the way the service qualifies it, must exist in
 * {@code messages.yml}. The list is read out of the code, so a new announcement is covered by writing it.
 */
class EveryAnnouncementHasWordingTest {

    private static final Path SOURCE = Path.of("src/main/java/de/raindancer/modules/hungergames");
    private static final Path WORDING =
            Path.of("src/main/resources/de/raindancer/modules/hungergames/messages.yml");

    @Test
    @DisplayName("every announcement key in the source has a line in messages.yml")
    void nothingIsAnnouncedWithoutWording() {
        Set<String> keys = announcementKeysInSource();

        assertThat(keys)
                .as("the scanner found nothing, which means it stopped matching the code rather than that "
                        + "the code stopped announcing — the same silent-pass this test exists to prevent")
                .isNotEmpty();

        // Named, not just counted. "isNotEmpty" is satisfied by a scanner that finds one key out of eleven,
        // and a scanner that quietly stops finding the interesting ones is worse than no scanner: the build
        // stays green and the check stops existing. These five are the ones that were actually broken.
        assertThat(keys).contains("sponsor-token-earned", "sponsor-purchase", "sponsor-not-enough",
                "sponsor-beacon-spawned", "supply-drop-warning");
        assertThat(keys).contains("hungergames.winner", "hungergames.kill", "hungergames.elimination",
                "hungergames.remaining-players");

        YamlConfiguration wording = readWording();
        List<String> missing = new ArrayList<>();
        for (String key : keys) {
            String qualified = AnnouncementService.qualified(key);
            if (!wording.isString(qualified)) {
                missing.add(key + " → " + qualified);
            }
        }

        assertThat(missing)
                .as("announced with no wording behind it, so a full server would read the key itself")
                .isEmpty();
    }

    @Test
    @DisplayName("the namespace is put on bare keys, and only on bare keys")
    void qualifyingIsIdempotent() {
        assertThat(AnnouncementService.qualified("winner")).isEqualTo("hungergames.winner");
        assertThat(AnnouncementService.qualified("hungergames.winner")).isEqualTo("hungergames.winner");
        // Another plugin's key, left alone: re-homing it here would turn a deliberate reference into a
        // missing one.
        assertThat(AnnouncementService.qualified("core.something")).isEqualTo("core.something");
    }

    /**
     * The keys, read out of the code.
     *
     * <p>By argument position rather than by "any quoted string on the line": the calls also carry
     * placeholder names ({@code "amount"}, {@code "cost"}) which are not keys at all, and a scanner that
     * collected those would report failures nobody can fix and be switched off within a week.
     */
    private static Set<String> announcementKeysInSource() {
        Set<String> keys = new LinkedHashSet<>();
        for (Path file : javaFiles()) {
            String text = read(file);
            // send(player, recipient, KEY, styles, values...) — the third argument.
            collect(text, "announcements.send(", 2, keys);
            // everybody(KEY, values...) — the first.
            collect(text, "announcements.everybody(", 0, keys);
        }
        return keys;
    }

    /** Every occurrence of {@code call}, taking the argument at {@code index} when it is a string literal. */
    private static void collect(String text, String call, int index, Set<String> into) {
        int at = text.indexOf(call);
        while (at >= 0) {
            String argument = argumentAt(text, at + call.length(), index);
            if (argument != null && argument.startsWith("\"") && argument.endsWith("\"")) {
                into.add(argument.substring(1, argument.length() - 1));
            }
            at = text.indexOf(call, at + call.length());
        }
    }

    /**
     * The nth argument of a call whose opening bracket has already been passed.
     *
     * <p>Bracket-aware, because the arguments are things like {@code new Style[]{CHAT, ACTIONBAR}} and
     * {@code describe(winner)} — splitting on commas would cut those in half and hand back nonsense. A
     * previous version of a scanner in this module did exactly that and matched nothing at all, silently.
     */
    private static String argumentAt(String text, int from, int index) {
        int depth = 0;
        int argument = 0;
        StringBuilder current = new StringBuilder();
        boolean inString = false;

        for (int at = from; at < text.length(); at++) {
            char c = text.charAt(at);
            if (inString) {
                current.append(c);
                if (c == '"' && text.charAt(at - 1) != '\\') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> {
                    inString = true;
                    current.append(c);
                }
                case '(', '[', '{' -> {
                    depth++;
                    current.append(c);
                }
                case ')', ']', '}' -> {
                    if (depth == 0) {
                        return argument == index ? current.toString().trim() : null;   // end of the call
                    }
                    depth--;
                    current.append(c);
                }
                case ',' -> {
                    if (depth == 0) {
                        if (argument == index) {
                            return current.toString().trim();
                        }
                        argument++;
                        current.setLength(0);
                    } else {
                        current.append(c);
                    }
                }
                default -> current.append(c);
            }
        }
        return null;
    }

    private static List<Path> javaFiles() {
        try (Stream<Path> walk = Files.walk(SOURCE)) {
            return walk.filter(path -> path.toString().endsWith(".java")).toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static YamlConfiguration readWording() {
        return YamlConfiguration.loadConfiguration(WORDING.toFile());
    }
}
