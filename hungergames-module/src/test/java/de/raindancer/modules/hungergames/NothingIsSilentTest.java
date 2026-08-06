package de.raindancer.modules.hungergames;

import de.raindancer.core.ui.effect.Effect;
import de.raindancer.modules.hungergames.service.HungerGamesCues;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That every cue this module plays is one it has defined.
 *
 * <h2>The bug this test exists for, and why nothing else could have caught it</h2>
 * The module shipped calling {@code effects.play(uuid, "hungergames:countdown")} — and three other cue names
 * — with <em>none of them defined anywhere</em>. Core's {@link de.raindancer.core.ui.effect.Effects} answers
 * an unknown cue by logging one warning and playing nothing, which is correct behaviour for a shared registry
 * and meant this module ran a whole tournament without a sound: no countdown tick, no bell at the start, no
 * launch, no lamps coming on.
 *
 * <p>Nothing else could have found it. It compiles, because a cue is a string. Every unit test passes,
 * because a fake sink records the calls the code makes rather than the cues the server knows. It does not
 * throw, does not warn twice, and does not appear in a stack trace. The only symptom is silence, and silence
 * on a Minecraft server is indistinguishable from a resource pack that did not load, a client with its volume
 * down, or a plugin that is broken in some other way entirely.
 *
 * <p>So this test reads the module's own source for every cue name it passes to {@code play} or
 * {@code playAt}, and asserts each one is a key {@link HungerGamesCues#defaults()} produces. It is a source
 * scan rather than a runtime check because the failure is at start-up on a real server, and the point is to
 * fail in the build instead.
 */
class NothingIsSilentTest {

    private static final Path SOURCE = Path.of("src/main/java/de/raindancer/modules/hungergames");

    /**
     * Where a call to {@code play}, {@code playAt} or {@code playForAll} begins.
     *
     * <p>Only the opening. The arguments are found by matching brackets rather than by a pattern, because
     * every real call has nested brackets in it — {@code effects.playAt(world.getName(), x, y, z, cue)} —
     * and the first {@code )} a regex finds belongs to {@code getName}, not to the call. The version of this
     * test that used a pattern reported five false positives for exactly that reason.
     */
    private static final Pattern PLAY_CALL = Pattern.compile("\\.play(?:At|ForAll)?\\(");

    /** A cue name written out as a literal, wherever it appears. */
    private static final Pattern CUE_LITERAL = Pattern.compile("\"(hungergames:[a-z0-9-]+)\"");

    /**
     * The arguments of the call that starts at {@code from}, found by counting brackets.
     *
     * <p>Returns the text between the call's own brackets, so a nested call is included whole rather than
     * cutting the argument list short at its closing bracket.
     */
    private static String argumentsAt(String code, int from) {
        int depth = 1;
        for (int i = from; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return code.substring(from, i);
                }
            }
        }
        return code.substring(from);
    }

    private static List<Path> sourceFiles() {
        try (Stream<Path> walk = Files.walk(SOURCE)) {
            return walk.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        } catch (IOException unreadable) {
            throw new AssertionError("could not read the module's own source", unreadable);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException unreadable) {
            throw new AssertionError("could not read " + file, unreadable);
        }
    }

    /** Java source with comments removed, so a cue name mentioned in prose is not mistaken for a call. */
    private static String withoutComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "")
                .lines()
                .map(line -> {
                    int slashes = line.indexOf("//");
                    return slashes < 0 ? line : line.substring(0, slashes);
                })
                .reduce("", (all, line) -> all + line + "\n");
    }

    @Nested
    @DisplayName("what the module plays")
    class Played {

        @Test
        @DisplayName("every cue played is a cue defined")
        void nothingIsPlayedThatDoesNotExist() {
            Set<String> defined = HungerGamesCues.defaults().keySet();
            List<String> missing = new ArrayList<>();

            for (Path file : sourceFiles()) {
                String code = withoutComments(read(file));
                // Inside a play call's own brackets, and nowhere else. A wider scan was tried and reported
                // "hungergames:cornucopia" — which is a protected area's id, not a cue. The module's own
                // prefix is shared by everything it namespaces, so the prefix alone cannot tell them apart;
                // what a name is used *for* can.
                Matcher calls = PLAY_CALL.matcher(code);
                while (calls.find()) {
                    Matcher found = CUE_LITERAL.matcher(argumentsAt(code, calls.end()));
                    while (found.find()) {
                        String cue = found.group(1);
                        if (!defined.contains(cue)) {
                            missing.add(file.getFileName() + " plays '" + cue + "'");
                        }
                    }
                }
            }
            assertThat(missing)
                    .as("Core answers an unknown cue by playing nothing, so each of these is a silence "
                            + "nobody can see in a log or a stack trace — see this class's note")
                    .isEmpty();
        }

        @Test
        @DisplayName("the scan actually found some calls, so it cannot pass by finding nothing")
        void theScanIsNotVacuous() {
            int calls = 0;
            for (Path file : sourceFiles()) {
                Matcher matcher = PLAY_CALL.matcher(withoutComments(read(file)));
                while (matcher.find()) {
                    calls++;
                }
            }
            assertThat(calls)
                    .as("a pattern that stopped matching would make the test above pass on an empty set, "
                            + "which is the failure mode of every source scan")
                    .isGreaterThanOrEqualTo(4);
        }

        @Test
        @DisplayName("no cue name is assembled at runtime, because one cannot be checked")
        void everyCueNameIsALiteral() {
            List<String> suspicious = new ArrayList<>();

            for (Path file : sourceFiles()) {
                String code = withoutComments(read(file));
                Matcher calls = PLAY_CALL.matcher(code);
                while (calls.find()) {
                    String arguments = argumentsAt(code, calls.end());
                    boolean namedHere = arguments.contains("\"hungergames:")
                            || arguments.contains("HungerGamesCues.");
                    // A call that passes its cue in from a parameter is fine and is how a service takes one
                    // — what must not exist is a name *built* from pieces, which nothing can check.
                    boolean takesOneIn = arguments.contains("cue") || arguments.contains("step")
                            || arguments.contains("burst");
                    if (!namedHere && !takesOneIn) {
                        suspicious.add(file.getFileName() + ": " + arguments.strip());
                    }
                }
            }
            assertThat(suspicious)
                    .as("a cue whose name is built at runtime cannot be checked by anything, so the "
                            + "silence it would cause is only findable by listening to a live round")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("the definitions themselves")
    class Definitions {

        @Test
        @DisplayName("no cue is defined as silence by accident")
        void nothingShippedSilent() {
            List<String> silent = HungerGamesCues.defaults().entrySet().stream()
                    .filter(entry -> entry.getValue().isSilent())
                    .map(Map.Entry::getKey)
                    .toList();

            assertThat(silent)
                    .as("a deliberate silence is a server owner's decision, made from the effects screen. "
                            + "A cue that ships silent is a sound whose name was mistyped")
                    .isEmpty();
        }

        @Test
        @DisplayName("every layered cue really has its layers")
        void theLayersSurvivedParsing() {
            // The whole reason SoundSequence was added to Core. If parsing quietly kept only the first
            // sound, these would still play — one third of the noise each, which is exactly the failure
            // that is hard to notice.
            Effect smokeBomb = HungerGamesCues.defaults().get(HungerGamesCues.ITEM_SMOKE_BOMB);

            assertThat(smokeBomb.sounds().steps())
                    .as("the smoke bomb is a primed-TNT hiss over an extinguish, and one without the other "
                            + "does not read as smoke")
                    .hasSize(2);
            assertThat(smokeBomb.bursts().bursts())
                    .as("a hundred and forty large smoke particles plus sixty campfire wisps — either half "
                            + "alone looks like a rendering glitch")
                    .hasSize(2);
        }

        @Test
        @DisplayName("a delayed sound keeps its delay")
        void delaysSurvivedParsing() {
            Effect cannon = HungerGamesCues.defaults().get(HungerGamesCues.CANNON);

            assertThat(cannon.sounds().lengthMillis())
                    .as("the cannon is an explosion with thunder rolling in behind it; with the delay lost "
                            + "they fire together and it is one flat bang")
                    .isGreaterThan(0L);
        }

        @Test
        @DisplayName("no two cues share a name")
        void nothingCollides() {
            Set<String> seen = new LinkedHashSet<>();
            List<String> clashes = new ArrayList<>();
            for (String name : HungerGamesCues.names()) {
                if (!seen.add(name)) {
                    clashes.add(name);
                }
            }
            assertThat(clashes).isEmpty();
        }

        @Test
        @DisplayName("every cue is namespaced to this module")
        void nothingReachesIntoAnotherPluginsNames() {
            assertThat(HungerGamesCues.names())
                    .as("the registry is the whole server's. A cue defined without a prefix would rebind "
                            + "somebody else's sound, and the plugin that lost it has no way of knowing")
                    .allMatch(name -> name.startsWith(HungerGamesCues.PREFIX));
        }
    }
}
