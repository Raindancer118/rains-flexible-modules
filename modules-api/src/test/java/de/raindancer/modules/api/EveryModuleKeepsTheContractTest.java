package de.raindancer.modules.api;

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
 * That no module can quietly opt out of {@link WordingContract}.
 *
 * <h2>Why this test exists</h2>
 * Because the contract on its own did not work, and it failed in the way that is easiest to miss: it
 * was not wrong, it was simply <em>absent</em>. Three modules implemented it; four did not, because
 * they were written afterwards and nothing said they had to. A player walked into the game and was
 * told:
 *
 * <pre>rainshome&lt;/white&gt; is set, here.</pre>
 *
 * Eighteen lines across warps, homes and teleport requests had it, all the same shape, and every one
 * of them was in a module the contract had never been pointed at.
 *
 * <p>So a rule that has to be remembered is not a rule, and "add a test to the new module" is exactly
 * the kind of remembering nobody does at half past eleven. This is the part that cannot be forgotten:
 * a module with wording and no {@code WordingContract} fails the build, here, by name.
 *
 * <h2>Why it looks at the filesystem</h2>
 * Because the thing being checked is that a class <em>exists</em>, and a class that does not exist
 * cannot be found by reflection. Walking the reactor is the only way to ask "is there a module here
 * that nothing is checking".
 */
class EveryModuleKeepsTheContractTest {

    /** The reactor root, from {@code modules-api}'s own working directory. */
    private static final Path REACTOR = Path.of("..");

    /** A module that ships wording, and whether anything holds it to the contract. */
    private record Module(String name, boolean hasWording, boolean keepsTheContract) {
    }

    private static List<Module> modules() {
        List<Module> found = new ArrayList<>();
        try (Stream<Path> entries = Files.list(REACTOR)) {
            for (Path dir : entries.sorted().toList()) {
                String name = dir.getFileName().toString();
                if (!Files.isDirectory(dir) || !name.endsWith("-module")) {
                    continue;
                }
                found.add(new Module(name, hasWording(dir), keepsTheContract(dir)));
            }
        } catch (IOException unreadable) {
            throw new AssertionError("could not walk the reactor at " + REACTOR.toAbsolutePath(),
                    unreadable);
        }
        return found;
    }

    /** Whether the module ships any {@code messages.yml} at all. */
    private static boolean hasWording(Path module) {
        Path resources = module.resolve("src/main/resources");
        if (!Files.isDirectory(resources)) {
            return false;
        }
        try (Stream<Path> files = Files.walk(resources)) {
            return files.anyMatch(file -> file.getFileName().toString().equals("messages.yml"));
        } catch (IOException unreadable) {
            return false;
        }
    }

    /** Whether any of its tests implements the contract. Named, not inferred from a filename. */
    private static boolean keepsTheContract(Path module) {
        Path tests = module.resolve("src/test/java");
        if (!Files.isDirectory(tests)) {
            return false;
        }
        try (Stream<Path> files = Files.walk(tests)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (Files.readString(file).contains("implements WordingContract")) {
                    return true;
                }
            }
        } catch (IOException unreadable) {
            return false;
        }
        return false;
    }

    @Test
    @DisplayName("the scan finds the modules, so it cannot pass by looking at nothing")
    void theScanIsNotVacuous() {
        assertThat(modules())
                .as("no modules found beside modules-api — the reactor layout moved and this test is "
                        + "now checking an empty list, which is the one way it could pass while being "
                        + "useless")
                .hasSizeGreaterThan(3);
        assertThat(modules()).anyMatch(Module::hasWording);
    }

    @Test
    @DisplayName("every module that says anything to a player keeps the wording contract")
    void nobodyOptsOut() {
        List<String> unchecked = modules().stream()
                .filter(Module::hasWording)
                .filter(module -> !module.keepsTheContract())
                .map(Module::name)
                .toList();

        assertThat(unchecked)
                .as("these ship a messages.yml that nothing holds to WordingContract. That is how "
                        + "eighteen broken lines reached a live server: the contract was right, and "
                        + "four modules had simply never been pointed at it. Add a WordingTest "
                        + "implementing WordingContract — it is about thirty lines and says only "
                        + "where to look")
                .isEmpty();
    }

    @Test
    @DisplayName("a module with no wording is not asked for one")
    void modulesWithNothingToSayAreLeftAlone() {
        // farmworld has no messages.yml yet. Demanding a wording test of a module with no wording
        // would be a failing build with nothing to fix, and the next person would delete the rule
        // rather than the module.
        assertThat(modules())
                .filteredOn(module -> !module.hasWording())
                .allSatisfy(module -> assertThat(module.keepsTheContract())
                        .as("%s has no wording, so it needs no wording test", module.name())
                        .isFalse());
    }
}
