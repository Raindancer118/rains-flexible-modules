package de.raindancer.modules.essentials;

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
 * That each package holds what its name says — the same layout tpa-module and moderation-module
 * follow. This module has no {@code screen} or {@code rules}-full-of-many package; what it does have
 * is checked the same way theirs is.
 */
class PackageGrammarTest {

    private static final Path ROOT = Path.of("src/main/java/de/raindancer/modules/essentials");

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
        for (String pkg : List.of("model", "rules", "store", "service", "listener", "command",
                "screen", "util")) {
            assertThat(in(pkg)).as("the %s package is empty", pkg).isNotEmpty();
        }
    }

    @Test
    @DisplayName("every service takes the settings, whether or not it currently reads any")
    void everyServiceTakesTheSettings() {
        List<String> forgetful = new ArrayList<>();
        for (Source source : in("service")) {
            if (source.name().equals("IEssentialsService")) {
                continue;
            }
            if (!source.body().contains("void settings(EssentialsSettings")) {
                forgetful.add(source.name());
            }
        }
        assertThat(forgetful)
                .as("the service forgotten when it starts reading something is the one that keeps "
                        + "yesterday's numbers until the next restart")
                .isEmpty();
    }

    @Test
    @DisplayName("every listener can be told to forget somebody")
    void everyListenerForgets() {
        List<String> forgetful = new ArrayList<>();
        for (Source source : in("listener")) {
            if (source.name().equals("IEssentialsListener")) {
                continue;
            }
            if (!source.body().contains("public void forget(")) {
                forgetful.add(source.name());
            }
        }
        assertThat(forgetful)
                .as("a listener that remembers a player and is never told when they leave grows by "
                        + "an entry per player who has ever been on the server")
                .isEmpty();
    }

    @Test
    @DisplayName("everything in rules is named as a rule")
    void rulesAreCalledRules() {
        List<String> misnamed = new ArrayList<>();
        for (Source source : in("rules")) {
            if (!source.name().endsWith("Rule") && !source.name().endsWith("Rules")) {
                misnamed.add(source.name());
            }
        }
        assertThat(misnamed).isEmpty();
    }

    @Test
    @DisplayName("the model does not reach for the server")
    void theModelStaysPlain() {
        List<String> reaching = new ArrayList<>();
        for (Source source : in("model")) {
            if (source.body().contains("Bukkit.get") || source.body().contains("getServer()")) {
                reaching.add(source.name());
            }
        }
        assertThat(reaching).as("these make the model need a server to be judged").isEmpty();
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
