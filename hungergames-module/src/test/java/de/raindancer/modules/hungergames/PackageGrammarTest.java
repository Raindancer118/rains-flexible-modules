package de.raindancer.modules.hungergames;

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
 * <p>The same scan the claims and moderation modules have, against the same layout — see
 * {@code MODULE-LAYOUT.md}. It is worth repeating rather than sharing because the point of the layout is
 * that somebody who has read one module can find their way around the next one, and a rule enforced in one
 * module and not the others is a rule that stops being true within a month.
 *
 * <h2>Why this module needs it more than the others</h2>
 * It is the biggest in the repository and was ported from a plugin with a different layout, by several
 * hands at once. Every one of these rules is something the source violated: the arena's protection matrix
 * sat next to the listener that used it, the settings catalogue wrote its own file, and the border engine
 * both decided and moved the border. Each of those is fine until the thing that has to be tested
 * separately cannot be.
 */
class PackageGrammarTest {

    private static final Path ROOT = Path.of("src/main/java/de/raindancer/modules/hungergames");

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
        for (String pkg : List.of("model", "store", "rules", "service", "listener", "screen", "command",
                "visual", "util")) {
            assertThat(in(pkg)).as("the %s package is empty", pkg).isNotEmpty();
        }
    }

    @Test
    @DisplayName("everything in rules is named as a rule")
    void rulesAreCalledRules() {
        List<String> misnamed = new ArrayList<>();
        for (Source source : in("rules")) {
            if (source.name().equals("IHungerGamesRule")) {
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
            if (source.name().equals("IHungerGamesRule")) {
                continue;
            }
            boolean isARule = source.body().contains("IHungerGamesRule")
                    || source.body().contains("AbstractRule<")
                    || source.body().contains("implements IRule<");
            if (!isARule) {
                strangers.add(source.name());
            }
        }
        assertThat(strangers)
                .as("these live in rules without being one. A rule decides and does nothing else; what a "
                        + "phase *is* belongs in model, what stores the session in store, what moves the "
                        + "border in service")
                .isEmpty();
    }

    @Test
    @DisplayName("a rule does not save, send, schedule or move anything")
    void rulesHaveNoSideEffects() {
        List<String> busy = new ArrayList<>();
        for (Source source : in("rules")) {
            boolean acts = source.body().contains("saveAsync(")
                    || source.body().contains("messages().send(")
                    || source.body().contains("Scheduling.")
                    || source.body().contains(".sendMessage(")
                    || source.body().contains("setWorldBorder")
                    || source.body().contains("teleport(")
                    || source.body().contains("setGameMode(");
            if (acts) {
                busy.add(source.name());
            }
        }
        assertThat(busy)
                .as("a rule that acts cannot be asked speculatively — and this module asks every one of "
                        + "them speculatively: to grey a button, to draw a preflight list, to decide "
                        + "whether a menu may offer something. The protection matrix is asked several "
                        + "times a tick by whichever region thread owns the block")
                .isEmpty();
    }

    @Test
    @DisplayName("a rule holds no mutable state")
    void rulesAreSafeFromAnyThread() {
        List<String> stateful = new ArrayList<>();
        for (Source source : in("rules")) {
            // A non-final instance field is the tell. On Folia the border timer, a tribute's click and a
            // block break are three different threads and there is one instance of each rule.
            if (source.body().matches("(?s).*\\n    private (?!static)(?!final)[A-Za-z<>\\[\\], .]+ \\w+;.*")) {
                stateful.add(source.name());
            }
        }
        assertThat(stateful)
                .as("a mutable field on a rule is a data race the moment two region threads ask it at "
                        + "once, and this module's rules are asked from every thread there is")
                .isEmpty();
    }

    @Test
    @DisplayName("the model does not reach for the server")
    void theModelStaysPlain() {
        List<String> reaching = new ArrayList<>();
        for (Source source : in("model")) {
            // Bukkit *types* are fine — a phase names the material its icon is drawn with. Reaching for
            // the running server is not: it is what makes a model class impossible to test without one,
            // and every one of these is a value a rule has to be able to judge offline. Including, in
            // this module, who won.
            if (source.body().contains("Bukkit.get") || source.body().contains("getServer()")) {
                reaching.add(source.name());
            }
        }
        assertThat(reaching)
                .as("these reach for the running server from the model. The deciding half of a Hunger "
                        + "Games round has to be testable without booting Paper, and that half includes "
                        + "who won")
                .isEmpty();
    }

    @Test
    @DisplayName("screens, listeners and commands do not talk to the disk")
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
                        + "is one the session's own save does not know about, and a round is written on "
                        + "every mutation precisely so a crash loses nothing")
                .isEmpty();
    }

    @Test
    @DisplayName("every service takes the settings, whether or not it currently reads any")
    void everyServiceTakesTheSettings() {
        List<String> forgetful = new ArrayList<>();
        for (Source source : in("service")) {
            if (source.name().equals("IHungerGamesService")) {
                continue;
            }
            // The API's own value types and exceptions are not services and do not pretend to be.
            if (!source.body().contains("implements IHungerGamesService")
                    && !source.body().contains("IHungerGamesService,")
                    && !source.body().contains(", IHungerGamesService")) {
                continue;
            }
            if (!source.body().contains("void settings(HungerGamesSettings")) {
                forgetful.add(source.name());
            }
        }
        assertThat(forgetful)
                .as("this module has more services than any other in the repository and a config page "
                        + "that writes while a round is running. The one forgotten here is the one that "
                        + "keeps yesterday's numbers for the rest of the tournament")
                .isEmpty();
    }

    @Test
    @DisplayName("every listener can be told to forget somebody")
    void everyListenerForgets() {
        List<String> forgetful = new ArrayList<>();
        for (Source source : in("listener")) {
            if (source.name().equals("IHungerGamesListener")) {
                continue;
            }
            if (!source.body().contains("public void forget(")) {
                forgetful.add(source.name());
            }
        }
        assertThat(forgetful)
                .as("a listener that remembers a player and is never told when they leave grows by an "
                        + "entry per player who has ever been on the server — override it empty and say why")
                .isEmpty();
    }

    @Test
    @DisplayName("forgetting a player is never a route to eliminating them")
    void forgettingIsNotEliminating() {
        List<String> dangerous = new ArrayList<>();
        for (Source source : in("listener")) {
            int at = source.body().indexOf("public void forget(");
            if (at < 0) {
                continue;
            }
            // The body of forget() only — a listener may well eliminate somebody elsewhere.
            int end = source.body().indexOf("\n    }", at);
            String body = end < 0 ? source.body().substring(at) : source.body().substring(at, end);
            // Comments stripped first. Every forget() in this module explains in words that it must never
            // eliminate anybody, which is exactly the sentence a plain substring search reported as a
            // violation — the check was failing on the documentation of the rule it enforces.
            String code = withoutComments(body);
            if (code.contains("eliminate(") || code.contains("ParticipantState.ELIMINATED")
                    || code.contains("declareTimeout(")) {
                dangerous.add(source.name());
            }
        }
        assertThat(dangerous)
                .as("a tribute who disconnects stays ALIVE until something eliminates them — that is the "
                        + "invariant the whole winner logic rests on, and it is why somebody can rejoin "
                        + "mid-round and still be in the game. forget() drops a cache, never a life")
                .isEmpty();
    }

    /** Java source with {@code //} and block comments removed, so a scan sees code rather than prose. */
    private static String withoutComments(String source) {
        return source
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .lines()
                .map(line -> {
                    int slashes = line.indexOf("//");
                    return slashes < 0 ? line : line.substring(0, slashes);
                })
                .reduce("", (all, line) -> all + line + "\n");
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
                .as("two levels under the module root, never three — the same rule RainsCore follows. "
                        + "The source plugin nested gui/admin/, gui/loot/, gui/sponsor/ and gui/team/, "
                        + "which is how it ended up with four pages that were one page")
                .isEmpty();
    }

    @Test
    @DisplayName("the store does not decide, and the rules do not store")
    void theTwoHalvesStayApart() {
        List<String> confused = new ArrayList<>();
        for (Source source : in("store")) {
            // A store that consults a rule is a store deciding. The session is the exception and says so:
            // it delegates to TeamRules because team membership is the one thing whose rules and whose
            // storage genuinely are the same object.
            if (source.name().equals("GameSession")) {
                continue;
            }
            // Actual use, not the word appearing in prose. Every class here explains itself at length,
            // and ParticipantRegistry's javadoc names WinnerRule to say what it is written *against* —
            // which a plain substring read as a dependency and reported as a layering violation.
            if (source.body().contains(".shouldDeny(") || source.body().contains("new WinnerRule")
                    || source.body().contains("WinnerRule.") || source.body().contains("WinnerRule ")) {
                confused.add("store/" + source.name());
            }
        }
        for (Source source : in("rules")) {
            if (source.body().contains("new YamlStore") || source.body().contains("Files.write")
                    || source.body().contains("Files.read")) {
                confused.add("rules/" + source.name());
            }
        }
        assertThat(confused)
                .as("the split is what lets the deciding half be tested without a disk and the storing "
                        + "half without a decision")
                .isEmpty();
    }
}
