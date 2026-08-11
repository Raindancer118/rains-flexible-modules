package de.raindancer.modules.rtp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That each package holds what its name says. The same scan the other modules have, against the same
 * layout — see {@code MODULE-LAYOUT.md}.
 *
 * <h2>{@code model} and {@code store} — the one thing here with an identity to persist</h2>
 * The pool of already-checked landings {@code RtpLocationPoolService} keeps ready: a list of places
 * with a life longer than one trip, the exact shape {@code HomeCatalogue} and the moderation report
 * queue already have theirs in. There is also a {@code screen} package, small as it is — whether to
 * check a landing for safety is a real choice a player makes, and that is a menu's job, not a command
 * flag's.
 */
class PackageGrammarTest {

    private static final Path ROOT = Path.of("src/main/java/de/raindancer/modules/rtp");

    private record Source(String pkg, String name, String body) {
    }

    private static String code(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }

    private static List<Source> in(String pkg) {
        Path dir = ROOT.resolve(pkg);
        if (!Files.isDirectory(dir)) {
            throw new AssertionError("the " + pkg + " package is gone — this test is about where things live");
        }
        try (Stream<Path> files = Files.list(dir)) {
            List<Source> found = new ArrayList<>();
            for (Path file : files.sorted().toList()) {
                found.add(new Source(pkg, file.getFileName().toString().replace(".java", ""),
                        code(Files.readString(file))));
            }
            return found;
        } catch (IOException unreadable) {
            throw new AssertionError("could not read " + pkg, unreadable);
        }
    }

    @Test
    @DisplayName("the scan found the packages, so a rename cannot quietly empty it")
    void theScanIsNotVacuous() {
        for (String pkg : List.of("rules", "service", "listener", "command", "util", "screen",
                "model", "store")) {
            assertThat(in(pkg)).as("the %s package is empty", pkg).isNotEmpty();
        }
    }

    @Test
    @DisplayName("everything in rules is named as a rule")
    void rulesAreCalledRules() {
        List<String> misnamed = new ArrayList<>();
        for (Source source : in("rules")) {
            if (source.name().equals("IRtpRule")) {
                continue;
            }
            if (!source.name().endsWith("Rule") && !source.name().endsWith("Rules")) {
                misnamed.add(source.name());
            }
        }
        assertThat(misnamed).isEmpty();
    }

    @Test
    @DisplayName("a rule does not write, send or schedule")
    void rulesHaveNoSideEffects() {
        List<String> busy = new ArrayList<>();
        for (Source source : in("rules")) {
            boolean acts = source.body().contains("Scheduling.")
                    || source.body().contains(".sendMessage(")
                    || source.body().contains("messages().send(")
                    || source.body().contains(".save(");
            if (acts) {
                busy.add(source.name());
            }
        }
        assertThat(busy)
                .as("a rule that acts cannot be asked speculatively, and a command asks it before "
                        + "spending a warm-up on a trip that was never going to be allowed")
                .isEmpty();
    }

    @Test
    @DisplayName("the listener decides nothing: it goes through the service")
    void listenersOnlyRoute() {
        List<String> deciding = new ArrayList<>();
        for (Source source : in("listener")) {
            if (source.name().equals("IRtpListener")) {
                continue;
            }
            if (!source.body().contains("service.")) {
                deciding.add(source.name());
            }
        }
        assertThat(deciding).as("these do not go through the service at all").isEmpty();
    }

    @Test
    @DisplayName("the service takes the settings, whether or not it currently reads any")
    void theServiceTakesTheSettings() {
        List<String> forgetful = new ArrayList<>();
        for (Source source : in("service")) {
            if (source.name().equals("IRtpService")) {
                continue;
            }
            if (!source.body().contains("void settings(RtpSettings")) {
                forgetful.add(source.name());
            }
        }
        assertThat(forgetful)
                .as("the service forgotten when it starts reading something is the one that keeps "
                        + "yesterday's radius or cooldown until the next restart")
                .isEmpty();
    }

    @Test
    @DisplayName("every listener can be told to forget somebody")
    void everyListenerForgets() {
        List<String> forgetful = new ArrayList<>();
        for (Source source : in("listener")) {
            if (source.name().equals("IRtpListener")) {
                continue;
            }
            if (!source.body().contains("public void forget(")) {
                forgetful.add(source.name());
            }
        }
        assertThat(forgetful).isEmpty();
    }

    @Test
    @DisplayName("nothing lives three levels under the module root")
    void twoLevelsNeverThree() {
        List<String> tooDeep = new ArrayList<>();
        try (Stream<Path> everything = Files.walk(ROOT)) {
            for (Path file : everything.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (ROOT.relativize(file).getNameCount() > 2) {
                    tooDeep.add(ROOT.relativize(file).toString());
                }
            }
        } catch (IOException unreadable) {
            throw new AssertionError("could not walk the module", unreadable);
        }
        assertThat(tooDeep)
                .as("two levels under the module root, never three — the same rule RainsCore follows")
                .isEmpty();
    }
}
