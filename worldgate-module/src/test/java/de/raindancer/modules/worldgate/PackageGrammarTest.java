package de.raindancer.modules.worldgate;

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
 * That each package holds what its name says, mirroring rtp-module's own scan against this module's
 * fixed layout: {@code command · listener · model · rules · service · store · util}. No {@code screen}
 * package — this feature is command-only, nothing here opens an inventory.
 */
class PackageGrammarTest {

    private static final Path ROOT = Path.of("src/main/java/de/raindancer/modules/worldgate");

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
        for (String pkg : List.of("command", "listener", "model", "rules", "service", "store", "util")) {
            assertThat(in(pkg)).as("the %s package is empty", pkg).isNotEmpty();
        }
    }

    @Test
    @DisplayName("there is deliberately no screen package — this module is command-only")
    void noScreenPackage() {
        assertThat(Files.isDirectory(ROOT.resolve("screen")))
                .as("nothing here needed a GUI; locking a dimension is a command an admin types")
                .isFalse();
    }

    @Test
    @DisplayName("everything in rules is named as a rule")
    void rulesAreCalledRules() {
        List<String> misnamed = new ArrayList<>();
        for (Source source : in("rules")) {
            if (source.name().equals("IWorldGateRule")) {
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
                .as("a rule that acts cannot be asked speculatively, and a listener asks it before "
                        + "cancelling anything")
                .isEmpty();
    }

    @Test
    @DisplayName("the listener decides nothing itself: it goes through the rule and the service")
    void listenerOnlyRoutes() {
        List<String> deciding = new ArrayList<>();
        for (Source source : in("listener")) {
            if (source.name().equals("IWorldGateListener")) {
                continue;
            }
            if (!source.body().contains("rule.") || !source.body().contains("service.")) {
                deciding.add(source.name());
            }
        }
        assertThat(deciding).as("these do not go through both the rule and the service").isEmpty();
    }

    @Test
    @DisplayName("the service takes the settings, whether or not it currently reads any")
    void theServiceTakesTheSettings() {
        List<String> forgetful = new ArrayList<>();
        for (Source source : in("service")) {
            if (source.name().equals("IWorldGateService")) {
                continue;
            }
            if (!source.body().contains("void settings(WorldGateSettings")) {
                forgetful.add(source.name());
            }
        }
        assertThat(forgetful)
                .as("a service forgotten when it starts reading something is the one that keeps "
                        + "yesterday's world names until the next restart")
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
