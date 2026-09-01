package de.raindancer.modules.xpbottle;

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
 * That each package holds what its name says — the same rule every module in this reactor is
 * checked against. See {@code claims-module}'s own copy for why this is a source scan rather than
 * a convention somebody has to remember.
 */
class PackageGrammarTest {

    private static final Path ROOT = Path.of("src/main/java/de/raindancer/modules/xpbottle");

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
        assertThat(in("rules")).isNotEmpty();
        assertThat(in("model")).isNotEmpty();
        assertThat(in("service")).isNotEmpty();
        assertThat(in("store")).isNotEmpty();
    }

    @Test
    @DisplayName("everything in rules is named as a rule")
    void rulesAreCalledRules() {
        List<String> misnamed = new ArrayList<>();
        for (Source source : in("rules")) {
            if (source.name().equals("IXpBottleRule")) {
                continue;
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
            if (source.name().equals("IXpBottleRule")) {
                continue;
            }
            if (!source.body().contains("IXpBottleRule")) {
                strangers.add(source.name());
            }
        }
        assertThat(strangers)
                .as("these live in rules without being one. A rule decides and does nothing else; what a "
                        + "snapshot *is* belongs in model, what stores it in store, what acts in service")
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
                    || source.body().contains(".sendMessage(");
            if (acts) {
                busy.add(source.name());
            }
        }
        assertThat(busy)
                .as("a rule that acts cannot be asked speculatively")
                .isEmpty();
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
        assertThat(reaching)
                .as("these reach for the running server from the model, which is what makes a model class "
                        + "impossible to test without booting one")
                .isEmpty();
    }

    @Test
    @DisplayName("screens and listeners do not talk to the disk")
    void onlyTheStoreStores() {
        List<String> writing = new ArrayList<>();
        for (String pkg : List.of("screen", "listener")) {
            for (Source source : in(pkg)) {
                if (source.body().contains("Files.write") || source.body().contains("new YamlConfiguration")) {
                    writing.add(pkg + "/" + source.name());
                }
            }
        }
        assertThat(writing)
                .as("writing belongs in store, behind the services")
                .isEmpty();
    }
}
