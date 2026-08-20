package de.raindancer.modules.xaeromap;

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
 * That each package holds what its name says. The same scan every other module has, against the same
 * layout — see {@code MODULE-LAYOUT.md}.
 */
class PackageGrammarTest {

    private static final Path ROOT = Path.of("src/main/java/de/raindancer/modules/xaeromap");

    private record Source(String pkg, String name, String body) {
    }

    private static String code(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }

    private static List<Source> in(String pkg) {
        Path dir = ROOT.resolve(pkg);
        if (!Files.isDirectory(dir)) {
            throw new AssertionError("the " + pkg + " package is gone — this test is about where "
                    + "things live");
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
        for (String pkg : List.of("model", "store", "rules", "service", "listener", "command",
                "util", "claims")) {
            assertThat(in(pkg)).as("the %s package is empty", pkg).isNotEmpty();
        }
    }

    @Test
    @DisplayName("everything in rules is named as a rule")
    void rulesAreCalledRules() {
        List<String> misnamed = new ArrayList<>();
        for (Source source : in("rules")) {
            if (source.name().equals("IXaeroMapRule")) {
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
                    || source.body().contains("wire.send")
                    || source.body().contains(".sendMessage(")
                    || source.body().contains(".sendPluginMessage(")
                    || source.body().contains(".save(");
            if (acts) {
                busy.add(source.name());
            }
        }
        assertThat(busy)
                .as("a rule that acts cannot be asked speculatively — and every rule here is asked "
                        + "once per claim per player per refresh")
                .isEmpty();
    }

    @Test
    @DisplayName("the service takes the settings, whether or not it currently reads any")
    void theServiceTakesTheSettings() {
        List<String> forgetful = new ArrayList<>();
        for (Source source : in("service")) {
            if (source.name().equals("IXaeroMapService")) {
                continue;
            }
            if (!source.body().contains("void settings(XaeroMapSettings")) {
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
            if (source.name().equals("IXaeroMapListener")
                    // Not a Bukkit listener: an incoming plugin message arrives through the
                    // messenger, and this one keeps nothing of its own to forget.
                    || source.name().equals("ClaimChannelListener")) {
                continue;
            }
            if (!source.body().contains("public void forget(")) {
                forgetful.add(source.name());
            }
        }
        assertThat(forgetful)
                .as("this module remembers what every client has been told; a listener that never "
                        + "forgets grows by a map per player who has ever joined")
                .isEmpty();
    }

    @Test
    @DisplayName("the model is data: no server, no packets going out")
    void themodelIsData() {
        List<String> reaching = new ArrayList<>();
        for (Source source : in("model")) {
            if (source.body().contains("import org.bukkit")
                    || source.body().contains("wire.send")) {
                reaching.add(source.name());
            }
        }
        assertThat(reaching)
                .as("what a packet *is* has to be testable without a server — it is the half of "
                        + "this module nothing else can check")
                .isEmpty();
    }

    @Test
    @DisplayName("only the claims package knows claims-module exists")
    void theSeamHoldsIn() {
        List<String> leaked = new ArrayList<>();
        try (Stream<Path> everything = Files.walk(ROOT)) {
            for (Path file : everything.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (ROOT.relativize(file).startsWith("claims")) {
                    continue;
                }
                if (code(Files.readString(file)).contains("de.raindancer.modules.claims")) {
                    leaked.add(ROOT.relativize(file).toString());
                }
            }
        } catch (IOException unreadable) {
            throw new AssertionError("could not walk the module", unreadable);
        }
        assertThat(leaked)
                .as("claims-module's classes are only on the classpath when a claims plugin is "
                        + "installed; a mention outside the seam turns 'no claims plugin' into a "
                        + "NoClassDefFoundError at startup")
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
                .as("two levels under the module root, never three")
                .isEmpty();
    }
}
