package de.raindancer.modules.moderation;

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
 * <p>The same scan the claims module has, against the same layout — see {@code MODULE-LAYOUT.md}. It is
 * worth repeating rather than sharing because the point of the layout is that somebody who has read one
 * module can find their way around the next one, and a rule enforced in one module and not the other is
 * a rule that stops being true within a month.
 */
class PackageGrammarTest {

    private static final Path ROOT = Path.of("src/main/java/de/raindancer/modules/moderation");

    private record Source(String pkg, String name, String body) {
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
                        Files.readString(file)));
            }
            return found;
        } catch (IOException unreadable) {
            throw new AssertionError("could not read " + pkg, unreadable);
        }
    }

    @Test
    @DisplayName("the scan found the packages, so a rename cannot quietly empty it")
    void theScanIsNotVacuous() {
        for (String pkg : List.of("model", "store", "rules", "service", "listener", "screen", "command")) {
            assertThat(in(pkg)).as("the %s package is empty", pkg).isNotEmpty();
        }
    }

    @Test
    @DisplayName("everything in rules is named as a rule")
    void rulesAreCalledRules() {
        List<String> misnamed = new ArrayList<>();
        for (Source source : in("rules")) {
            if (source.name().equals("IModerationRule")) {
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
            if (source.name().equals("IModerationRule")) {
                continue;
            }
            boolean isARule = source.body().contains("IModerationRule")
                    || source.body().contains("AbstractRule<")
                    || source.body().contains("implements IRule<");
            if (!isARule) {
                strangers.add(source.name());
            }
        }
        assertThat(strangers)
                .as("these live in rules without being one. A rule decides and does nothing else; what "
                        + "a punishment *is* belongs in model, what stores it in store, what acts in service")
                .isEmpty();
    }

    @Test
    @DisplayName("a rule does not save, send or schedule")
    void rulesHaveNoSideEffects() {
        List<String> busy = new ArrayList<>();
        for (Source source : in("rules")) {
            boolean acts = source.body().contains("saveAsync(")
                    || source.body().contains("messages().send(")
                    || source.body().contains("Scheduling.")
                    || source.body().contains(".sendMessage(")
                    || source.body().contains("punish(")
                    || source.body().contains("audit(");
            if (acts) {
                busy.add(source.name());
            }
        }
        assertThat(busy)
                .as("a rule that acts cannot be asked speculatively — and 'would this be allowed?' is "
                        + "what a screen asks to decide whether to grey a button")
                .isEmpty();
    }

    @Test
    @DisplayName("the model does not reach for the server")
    void theModelStaysPlain() {
        List<String> reaching = new ArrayList<>();
        for (Source source : in("model")) {
            // Bukkit *types* are fine — a report state names the material it is drawn with. Reaching
            // for the running server is not: it is what makes a model class impossible to test
            // without one, and every one of these is a value a rule has to be able to judge offline.
            if (source.body().contains("Bukkit.get") || source.body().contains("getServer()")) {
                reaching.add(source.name());
            }
        }
        assertThat(reaching)
                .as("these reach for the running server from the model, which is what makes a model "
                        + "class impossible to test without booting one")
                .isEmpty();
    }

    @Test
    @DisplayName("screens and listeners do not talk to the disk")
    void onlyTheStoreStores() {
        List<String> writing = new ArrayList<>();
        for (String pkg : List.of("screen", "listener", "command")) {
            for (Source source : in(pkg)) {
                if (source.body().contains("Files.write") || source.body().contains("new YamlStore(")
                        || source.body().contains("new YamlConfiguration")) {
                    writing.add(pkg + "/" + source.name());
                }
            }
        }
        assertThat(writing)
                .as("writing belongs in store, behind the services — a screen that writes its own file "
                        + "is one the auto-save does not know about")
                .isEmpty();
    }

    @Test
    @DisplayName("every service takes the settings, whether or not it currently reads any")
    void everyServiceTakesTheSettings() {
        List<String> forgetful = new ArrayList<>();
        for (Source source : in("service")) {
            if (source.name().equals("IModerationService")) {
                continue;
            }
            if (!source.body().contains("void settings(ModerationSettings")) {
                forgetful.add(source.name());
            }
        }
        assertThat(forgetful)
                .as("the service forgotten when it starts reading something is the one that keeps "
                        + "yesterday's numbers until the next restart, and that gets reported as "
                        + "'the config does not work'")
                .isEmpty();
    }

    @Test
    @DisplayName("every listener can be told to forget somebody")
    void everyListenerForgets() {
        List<String> forgetful = new ArrayList<>();
        for (Source source : in("listener")) {
            if (source.name().equals("IModerationListener")) {
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
