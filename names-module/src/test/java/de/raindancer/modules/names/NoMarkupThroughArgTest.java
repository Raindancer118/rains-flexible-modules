package de.raindancer.modules.names;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That nothing hands markup to a placeholder that escapes it.
 *
 * <h2>The bug this is about</h2>
 * It happened in the moderation module, where {@code /history} printed a line ending
 * {@code (9 hours ago, for ever) &lt;green&gt;lifted}. The test is repeated here for the reason every
 * grammar test in this repository is: a rule checked in one module and merely written down in the
 * next is a rule that lasts until the first hurried afternoon, and nothing about this mistake is
 * particular to moderation.
 *
 * <p>There, the state was built as a string of markup, {@code " <green>lifted"}, and passed through
 * {@code Chat.arg}. That is {@code Placeholder.unparsed}: it escapes what it is given, always — and
 * rightly, because nearly everything going through it is text a player typed and markup from a player
 * is a way to forge messages. So the tag was shown instead of applied.
 *
 * <p>It is the mirror of the unmatched-closing-tag bug that {@code WordingRendersTest} covers, and
 * neither test sees the other's: that one renders {@code messages.yml}, and this one never reached it
 * because the markup was in Java. Both end the same way, with a player reading a tag.
 *
 * <h2>What the right answer is</h2>
 * {@code Chat.formatted}, which takes a {@code Component} — deliberately harder to reach for, and
 * deliberately named for what it costs. Wording that carries a colour is wording, so it belongs in
 * {@code messages.yml} and comes back from {@code Messages.get} already a component.
 *
 * <h2>What this checks, and what it cannot</h2>
 * Two shapes, both static: markup written straight into an {@code arg} call, and the shape the real
 * bug had — a helper returning a string that is only ever a piece of markup. It cannot follow a value
 * through a field or a third method, and does not pretend to: it catches the two ways this has
 * actually been written.
 */
class NoMarkupThroughArgTest {

    private static final Path SOURCE = Path.of("src/main/java/de/raindancer/modules/names");

    /** The colour and format tags. A {@code <player>} is a placeholder, not markup. */
    private static final Set<String> FORMATS = Set.of(
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold",
            "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white",
            "bold", "italic", "underlined", "strikethrough", "obfuscated");

    private static final Pattern TAG = Pattern.compile("<(/?)([a-z_]+)>");

    private record Source(String name, String body) {
    }

    private static List<Source> module() {
        try (Stream<Path> files = Files.walk(SOURCE)) {
            List<Source> found = new ArrayList<>();
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                String body = Files.readString(file)
                        .replaceAll("(?s)/\\*.*?\\*/", " ")
                        .replaceAll("(?m)//.*$", " ");
                found.add(new Source(file.getFileName().toString(), body));
            }
            return found;
        } catch (IOException unreadable) {
            throw new AssertionError("could not read the module", unreadable);
        }
    }

    private static boolean isMarkup(String text) {
        Matcher tag = TAG.matcher(text);
        while (tag.find()) {
            if (FORMATS.contains(tag.group(2))) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("the scan reads the module, so it cannot pass by looking at nothing")
    void theScanIsNotVacuous() {
        assertThat(module()).hasSizeGreaterThan(10);
        assertThat(isMarkup(" <green>lifted")).isTrue();
        assertThat(isMarkup("<player> was banned")).as("a placeholder is not markup").isFalse();
    }

    @Test
    @DisplayName("no colour is written straight into a placeholder that escapes it")
    void noMarkupWrittenIntoAnArg() {
        Pattern arg = Pattern.compile("Chat\\.arg\\(([^;]{0,200}?)\\)");
        List<String> leaking = new ArrayList<>();

        for (Source source : module()) {
            Matcher call = arg.matcher(source.body());
            while (call.find()) {
                Matcher literal = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(call.group(1));
                while (literal.find()) {
                    if (isMarkup(literal.group(1))) {
                        leaking.add(source.name() + ": Chat.arg(" + call.group(1).trim() + ")");
                    }
                }
            }
        }

        assertThat(leaking)
                .as("arg() escapes what it is given, so this is shown to a player as a tag rather "
                        + "than applied as a colour — use Chat.formatted with a Component, and put "
                        + "the words in messages.yml")
                .isEmpty();
    }

    @Test
    @DisplayName("no helper returns a piece of markup as a String")
    void noHelperBuildsMarkupAsAString() {
        // The shape the real bug had: `private static String stateOf(...)` returning " <green>lifted".
        // Nothing about the call site looked wrong — Chat.arg("state", stateOf(past, now)) reads
        // perfectly — so the only place to catch it is where the markup is made.
        Pattern returning = Pattern.compile("return\\s+\"((?:[^\"\\\\]|\\\\.)*)\"\\s*;");
        List<String> building = new ArrayList<>();

        for (Source source : module()) {
            Matcher method = Pattern.compile("(private|static|public)[^\\n]*\\bString\\s+(\\w+)\\s*\\(")
                    .matcher(source.body());
            while (method.find()) {
                int from = method.end();
                int to = Math.min(source.body().length(), from + 700);
                Matcher returned = returning.matcher(source.body().substring(from, to));
                while (returned.find()) {
                    if (isMarkup(returned.group(1))) {
                        building.add(source.name() + "#" + method.group(2)
                                + " returns markup: \"" + returned.group(1).trim() + "\"");
                    }
                }
            }
        }

        assertThat(building)
                .as("a String of markup is a value, and every value put into a message is escaped — "
                        + "return a Component from Messages.get instead, so the words live in "
                        + "messages.yml and the colour survives")
                .isEmpty();
    }
}
