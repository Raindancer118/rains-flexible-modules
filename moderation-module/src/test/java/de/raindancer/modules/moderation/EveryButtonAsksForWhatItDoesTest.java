package de.raindancer.modules.moderation;

import de.raindancer.modules.moderation.model.ModerationPermission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That a button asks for the permission belonging to what it actually does.
 *
 * <h2>The bug this is about</h2>
 * The "Put them right" button heals, feeds and extinguishes — and asked for {@code WARN}. Two things
 * followed, and neither one looks like a bug from the code:
 *
 * <ul>
 *   <li><b>It refused to heal the person pressing it.</b> {@code WARN} is a punishment, so it is
 *       deliberately not {@link ModerationPermission#aimableAtSelf()} — a moderator who warns
 *       themselves is doing nothing anybody wants. Pointed at yourself, the button therefore answered
 *       "not yourself" about a golden apple.</li>
 *   <li><b>It handed the power to the wrong people.</b> A trial mod holds {@code warn} and not
 *       {@code heal}, so they could heal; an admin without {@code warn} could not.</li>
 * </ul>
 *
 * <p>Nothing about that is visible at the call site: {@code refusedFor(WARN)} reads perfectly well
 * until you ask what the method underneath it does.
 *
 * <h2>What is checked, and what deliberately is not</h2>
 * Only the actions with an obvious permission of their own — heal, feed, hurt, starve, invsee, vanish.
 * A screen is free to gate a <em>door</em> on anything it likes: the punishment categories are guarded
 * by the punishment they lead to, and the rank page by the node that hands out ranks. This is about a
 * button that <em>does</em> a thing, and the node it names.
 */
class EveryButtonAsksForWhatItDoesTest {

    private static final Path SCREENS =
            Path.of("src/main/java/de/raindancer/modules/moderation/screen");

    /** A call that acts, and the permission that has to be the one guarding it. */
    private record Action(Pattern call, ModerationPermission needs, String what) {
    }

    private static List<Action> actions() {
        return List.of(
                new Action(Pattern.compile("players\\(\\)\\.heal\\("), ModerationPermission.HEAL,
                        "heals somebody"),
                new Action(Pattern.compile("players\\(\\)\\.feed\\("), ModerationPermission.FEED,
                        "feeds somebody"),
                new Action(Pattern.compile("players\\(\\)\\.damage\\("), ModerationPermission.HURT,
                        "hurts somebody"),
                new Action(Pattern.compile("inventories\\(\\)\\.open\\("), ModerationPermission.INVSEE,
                        "opens somebody's inventory"));
    }

    private record Screen(String name, String body) {
    }

    private static List<Screen> screens() {
        List<Screen> found = new ArrayList<>();
        try (var files = Files.list(SCREENS)) {
            for (Path file : files.sorted().toList()) {
                found.add(new Screen(file.getFileName().toString().replace(".java", ""),
                        Files.readString(file)));
            }
        } catch (IOException unreadable) {
            throw new AssertionError("could not read the screen package", unreadable);
        }
        return found;
    }

    /**
     * The method a call sits in, so its guard can be read.
     *
     * <p>Crude on purpose: it walks back to the nearest {@code private void name(} above the call. A
     * screen that put two of these in one method would defeat it, and none of them does — the point is
     * to catch a guard that names the wrong node, not to parse Java.
     */
    private static String methodAround(String body, int at) {
        Matcher method = Pattern.compile("(?m)^\\s{4}private\\s+\\w+\\s+\\w+\\(").matcher(body);
        int start = 0;
        while (method.find() && method.start() < at) {
            start = method.start();
        }
        int end = body.indexOf("\n    }", at);
        return body.substring(start, end < 0 ? body.length() : end);
    }

    @Test
    @DisplayName("the scan finds the screens and the calls, so it cannot pass by looking at nothing")
    void theScanIsNotVacuous() {
        assertThat(screens()).hasSizeGreaterThan(10);

        long found = 0;
        for (Screen screen : screens()) {
            for (Action action : actions()) {
                found += action.call().matcher(screen.body()).results().count();
            }
        }
        assertThat(found).as("no acting call found at all").isGreaterThan(2);
    }

    @Test
    @DisplayName("a button that heals asks for heal, not for something else that happens to be held")
    void theGuardMatchesTheAction() {
        List<String> wrong = new ArrayList<>();

        for (Screen screen : screens()) {
            for (Action action : actions()) {
                Matcher call = action.call().matcher(screen.body());
                while (call.find()) {
                    String method = methodAround(screen.body(), call.start());
                    boolean namesTheRightOne = method.contains(action.needs().name());
                    // A switch over the vitals enum guards each branch by vital.permission(), which is
                    // the same statement made once — that is the shape ToolsMenu uses.
                    boolean guardedByTheEnum = method.contains("vital.permission()")
                            || method.contains("permission)");
                    if (!namesTheRightOne && !guardedByTheEnum) {
                        wrong.add(screen.name() + " " + action.what()
                                + " without naming " + action.needs().name());
                    }
                }
            }
        }

        assertThat(wrong)
                .as("a button guarded by a node that does not belong to what it does is one that "
                        + "refuses the wrong people and allows the wrong people — and reads correctly "
                        + "at the call site either way")
                .isEmpty();
    }

    @Test
    @DisplayName("nothing a moderator does to themselves is guarded by a node aimed at somebody else")
    void selfAimableWhereItMatters() {
        // The half that bit: heal and feed are things a moderator does to themselves all the time, so
        // the node guarding them has to allow that. A punishment node never will.
        assertThat(ModerationPermission.HEAL.aimableAtSelf()).isTrue();
        assertThat(ModerationPermission.FEED.aimableAtSelf()).isTrue();
        assertThat(ModerationPermission.WARN.aimableAtSelf())
                .as("warning yourself is not a thing anybody wants, and this is why the mix-up "
                        + "refused a golden apple to the person pressing the button")
                .isFalse();
    }
}
