package de.raindancer.modules.homes;

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
 * That each package holds what its name says.
 *
 * <p>The same scan the names, claims and moderation modules have, against the same layout — see
 * {@code MODULE-LAYOUT.md}. It is worth repeating rather than sharing because the point of the layout is
 * that somebody who has read one module can find their way around the next one, and a rule enforced in
 * one module and not the others is a rule that stops being true within a month.
 */
class PackageGrammarTest {

    private static final Path ROOT = Path.of("src/main/java/de/raindancer/modules/homes");

    private record Source(String pkg, String name, String body) {
    }

    /**
     * The file with its comments taken out.
     *
     * <p>Every rule below is about what the code <em>does</em>, and this module documents itself at
     * length — the class note on {@code HomeLimitRule} explains the permission scan it must not go back
     * to, and {@code LegacyHomesFile} explains what the old file looked like. Scanned raw, both of those
     * sentences read as the very thing a later rule forbids, and the only way to keep the test passing
     * would be to delete the explanation.
     */
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
        for (String pkg : List.of("model", "store", "rules", "service", "listener", "screen", "command",
                "util")) {
            assertThat(in(pkg)).as("the %s package is empty", pkg).isNotEmpty();
        }
    }

    @Test
    @DisplayName("everything in rules is named as a rule")
    void rulesAreCalledRules() {
        List<String> misnamed = new ArrayList<>();
        for (Source source : in("rules")) {
            if (source.name().equals("IHomeRule")) {
                continue;   // the interface itself
            }
            if (!source.name().endsWith("Rule") && !source.name().endsWith("Rules")) {
                misnamed.add(source.name());
            }
        }
        assertThat(misnamed)
                .as("these are in rules and are not called one — either rename them or move them out")
                .isEmpty();
    }

    @Test
    @DisplayName("everything in rules is a rule")
    void rulesImplementTheInterface() {
        List<String> strangers = new ArrayList<>();
        for (Source source : in("rules")) {
            if (source.name().equals("IHomeRule")) {
                continue;
            }
            boolean isARule = source.body().contains("IHomeRule")
                    || source.body().contains("AbstractRule<")
                    || source.body().contains("implements IRule<");
            if (!isARule) {
                strangers.add(source.name());
            }
        }
        assertThat(strangers)
                .as("these live in rules without being one. A rule decides and does nothing else; what "
                        + "a home *is* belongs in model, what reads or writes it in store, what acts in "
                        + "service")
                .isEmpty();
    }

    @Test
    @DisplayName("a rule does not write, send or schedule")
    void rulesHaveNoSideEffects() {
        // Asked once to draw the counter, once per home to draw the page, and again on the click that
        // sends somebody somewhere. A rule that acted would charge a player for opening a window.
        List<String> busy = new ArrayList<>();
        for (Source source : in("rules")) {
            boolean acts = source.body().contains("Scheduling.")
                    || source.body().contains(".sendMessage(")
                    || source.body().contains("messages().send(")
                    || source.body().contains(".save(")
                    || source.body().contains("places.");
            if (acts) {
                busy.add(source.name());
            }
        }
        assertThat(busy)
                .as("a rule that acts cannot be asked speculatively, and the menu asks it on every "
                        + "single home on every single draw, and once more for the counter")
                .isEmpty();
    }

    @Test
    @DisplayName("the model does not reach for the server")
    void theModelStaysPlain() {
        // Home wraps a place and reads tags off it, which is fine — a home *is* where it is. What it
        // must not do is reach for the running server, because the half of this module that decides how
        // many homes somebody gets has been wrong before, and the only way that was ever going to be
        // found is a test that needs no server to run.
        List<String> reaching = new ArrayList<>();
        for (Source source : in("model")) {
            if (source.body().contains("Bukkit.get") || source.body().contains("getServer()")) {
                reaching.add(source.name());
            }
        }
        assertThat(reaching).as("these make the model need a server to be judged").isEmpty();
    }

    @Test
    @DisplayName("screens, listeners and commands do not talk to the disk")
    void onlyTheStoreStores() {
        List<String> writing = new ArrayList<>();
        for (String pkg : List.of("screen", "listener", "command")) {
            for (Source source : in(pkg)) {
                if (source.body().contains("Files.write") || source.body().contains("new YamlConfiguration")) {
                    writing.add(pkg + "/" + source.name());
                }
            }
        }
        assertThat(writing)
                .as("writing belongs in the store, behind the services — a screen that writes its own "
                        + "file is one nothing else knows about")
                .isEmpty();
    }

    @Test
    @DisplayName("the listeners decide nothing: every one of them asks a service")
    void listenersOnlyRoute() {
        // The whole reason this module can be tested at all. A listener that worked out for itself how
        // many homes somebody may have would be a second answer to that question — and the second
        // answer is the one that gave every operator a hundred.
        List<String> deciding = new ArrayList<>();
        for (Source source : in("listener")) {
            if (source.name().equals("IHomeListener")) {
                continue;
            }
            if (!source.body().contains("services.")) {
                deciding.add(source.name());
            }
        }
        assertThat(deciding).as("these do not go through the services at all").isEmpty();
    }

    @Test
    @DisplayName("every service takes the settings, whether or not it currently reads any")
    void everyServiceTakesTheSettings() {
        List<String> forgetful = new ArrayList<>();
        for (Source source : in("service")) {
            if (source.name().equals("IHomeService")) {
                continue;
            }
            if (!source.body().contains("void settings(HomeSettings")) {
                forgetful.add(source.name());
            }
        }
        assertThat(forgetful)
                .as("the service forgotten when it starts reading something is the one that keeps "
                        + "yesterday's warm-up or cooldown until the next restart, and that gets "
                        + "reported as 'the config does not work'")
                .isEmpty();
    }

    @Test
    @DisplayName("every listener can be told to forget somebody")
    void everyListenerForgets() {
        List<String> forgetful = new ArrayList<>();
        for (Source source : in("listener")) {
            if (source.name().equals("IHomeListener")) {
                continue;
            }
            if (!source.body().contains("public void forget(")) {
                forgetful.add(source.name());
            }
        }
        assertThat(forgetful)
                .as("a listener that remembers a player and is never told when they leave grows by an "
                        + "entry per player who has ever been on the server — override it empty and say so")
                .isEmpty();
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
