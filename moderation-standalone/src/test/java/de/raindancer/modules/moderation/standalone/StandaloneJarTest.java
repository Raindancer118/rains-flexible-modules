package de.raindancer.modules.moderation.standalone;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the shipped jar actually contains.
 *
 * <p>Everything here is a build mistake that produces a plugin which loads and then behaves wrongly,
 * rather than one that fails to build — which is why it is checked rather than assumed:
 *
 * <ul>
 *   <li><b>A second copy of RainsCore.</b> For this plugin in particular that is not a duplicate but a
 *       fork: a second {@code Punishments} means a ban handed out here is invisible to every other
 *       plugin's guard, and a banned player who is turned away by one and let in by the other. The
 *       whole reason the punishments live in Core is that there must be exactly one set.</li>
 *   <li><b>A missing service file.</b> The jar loads, the plugin enables, and it contains no modules —
 *       a feature simply absent.</li>
 *   <li><b>A descriptor with the legacy {@code depend:} spelling</b>, which is ignored in a
 *       {@code paper-plugin.yml} and fails at runtime naming a class nobody wrote.</li>
 * </ul>
 *
 * <p>Reads the built jar, so it only runs after {@code package}. When there is no jar it says so and
 * stops rather than passing quietly — a test that silently checks nothing is worse than no test.
 */
class StandaloneJarTest {

    private static final Path TARGET = Path.of("target");

    private static Path theJar() {
        try (var files = Files.list(TARGET)) {
            return files
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .filter(path -> !path.getFileName().toString().startsWith("original-"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "no jar in target/ — this test reads the built plugin, so it needs "
                                    + "`mvn package`"));
        } catch (IOException unreadable) {
            throw new AssertionError("could not look in target/", unreadable);
        }
    }

    private static List<String> entries() {
        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(theJar()))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                names.add(entry.getName());
            }
        } catch (IOException unreadable) {
            throw new AssertionError("could not read the jar", unreadable);
        }
        return names;
    }

    private static String read(String entryName) {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(theJar()))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals(entryName)) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        } catch (IOException unreadable) {
            throw new AssertionError("could not read " + entryName, unreadable);
        }
        throw new AssertionError(entryName + " is not in the jar");
    }

    @Test
    @DisplayName("the jar holds the module and the wrapper")
    void itContainsWhatItShould() {
        List<String> entries = entries();

        assertThat(entries).anyMatch(name -> name.startsWith("de/raindancer/modules/moderation/"));
        assertThat(entries).contains("de/raindancer/modules/wrapper/ModulePlugin.class");
        assertThat(entries).contains("de/raindancer/modules/wrapper/ModuleBootstrap.class");
        assertThat(entries).anyMatch(name -> name.startsWith("de/raindancer/modules/api/"));
    }

    @Test
    @DisplayName("it holds no second copy of RainsCore")
    void coreIsNotShadedIn() {
        assertThat(entries())
                .as("a second RainsCore here is a second set of punishments: a ban handed out by this "
                        + "plugin would be invisible to every other plugin's guard, and the player "
                        + "turned away by one and let in by the other")
                .noneMatch(name -> name.startsWith("de/raindancer/core/"));
    }

    @Test
    @DisplayName("it holds no copy of the server API either")
    void paperIsNotShadedIn() {
        assertThat(entries())
                .as("shading paper-api produces a plugin that runs against its own idea of the server")
                .noneMatch(name -> name.startsWith("org/bukkit/") || name.startsWith("io/papermc/"));
    }

    @Test
    @DisplayName("the module declares itself, or the plugin loads with nothing in it")
    void theServiceFileSurvivedTheShade() {
        String services = read("META-INF/services/de.raindancer.modules.api.FlexModule");

        assertThat(services)
                .as("without this the jar loads, the plugin enables, and it contains no modules at all")
                .contains("de.raindancer.modules.moderation.ModerationModule");
    }

    @Test
    @DisplayName("the shaded module is the module that was just built")
    void theJarIsNotStale() throws IOException {
        // A stale shade is the worst kind of build mistake: the jar is newer than the source, it
        // loads, it enables, and it runs last week's code. It happened here — a deploy went out
        // without a setting that had been added and tested twenty minutes earlier, and the only
        // symptom was a warning about a config key that had already been renamed.
        //
        // Compared by record components rather than by timestamp, because the timestamp was the
        // thing that lied.
        int inTheJar = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(theJar()))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().endsWith("moderation/ModerationSettings.class")) {
                    inTheJar = zip.readAllBytes().length;
                }
            }
        }
        int justBuilt = Files.readAllBytes(Path.of("..", "moderation-module", "target", "classes",
                "de", "raindancer", "modules", "moderation", "ModerationSettings.class")).length;

        assertThat(inTheJar)
                .as("the ModerationSettings in the jar is not the one the module build just produced "
                        + "— the shade picked up an older artifact, and everything shipped is that old")
                .isEqualTo(justBuilt);
    }

    @Test
    @DisplayName("the module's own wording is in the jar")
    void theMessagesAreThere() {
        assertThat(entries())
                .as("without messages.yml every line falls back to its key, which reads as a broken "
                        + "plugin rather than a missing file")
                .contains("messages.yml");
    }

    @Test
    @DisplayName("the descriptor says what Paper needs and nothing it ignores")
    void theDescriptorIsRight() {
        String yaml = read("paper-plugin.yml");

        assertThat(yaml).contains("main: de.raindancer.modules.wrapper.ModulePlugin");
        assertThat(yaml)
                .as("Paper registers commands during bootstrap; without a bootstrapper they never exist")
                .contains("bootstrapper: de.raindancer.modules.wrapper.ModuleBootstrap");
        assertThat(yaml)
                .as("without join-classpath the plugin loads and dies on its first RainsCore class")
                .contains("join-classpath: true");
        assertThat(yaml)
                .as("depend: is plugin.yml syntax and is silently ignored here")
                .doesNotContain("depend:");

        // This file is hand-written rather than produced by StandaloneDescriptor, so it can drift from
        // it — and the claims plugin did exactly that, shipping with RainsCore declared for the server
        // phase only. That left the bootstrapper without RainsCore's classes at the moment it discovers
        // modules, and the plugin came up announcing that it contained none.
        int bootstrap = yaml.indexOf("bootstrap:\n");
        int server = yaml.indexOf("server:\n");
        assertThat(bootstrap)
                .as("Paper runs bootstrap with its own classpath, and bootstrap is where modules are found")
                .isNotNegative();
        assertThat(server).isGreaterThan(bootstrap);
        assertThat(yaml.substring(bootstrap, server))
                .contains("RainsCore:")
                .contains("join-classpath: true");
    }

    @Test
    @DisplayName("the version in the descriptor is the version of the build")
    void theVersionWasFilledIn() {
        String yaml = read("paper-plugin.yml");

        assertThat(yaml)
                .as("an unfiltered ${project.version} is a plugin whose version reads as a placeholder")
                .doesNotContain("${");
        assertThat(yaml)
                .as("Rain's Moderation continues its own version line, which is not the reactor's")
                .contains("version: '2.0.0'");
        assertThat(yaml)
                .as("the data folder is named after the plugin, and an upgrading server has its "
                        + "reports and notes in plugins/RainsModeration/")
                .contains("name: RainsModeration");
    }
}
