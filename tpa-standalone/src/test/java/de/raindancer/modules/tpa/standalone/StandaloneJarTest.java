package de.raindancer.modules.tpa.standalone;

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
 *   <li><b>A second copy of RainsCore.</b> Two settings registries, two message tables, two menu
 *       listeners and two place stores on one server, none of which know about each other's. The last
 *       of those is the interesting one here: the warps <em>are</em> Core's places, so a second copy
 *       is a server where the warps an admin makes and the warps players see are different sets.</li>
 *   <li><b>A missing service file.</b> The jar loads, the plugin enables, and it contains no modules —
 *       a feature simply absent.</li>
 *   <li><b>A missing messages.yml, or one at the wrong path.</b> Every line falls back to its key, so
 *       {@code /warp} answers {@code warps.list-empty}. It has to be beside the module's classes:
 *       RainsCore ships one at its own jar root and {@code join-classpath} puts it on this plugin's
 *       classpath, so a root lookup is a race between two files with the same name.</li>
 *   <li><b>A descriptor with the legacy {@code depend:} spelling</b>, which is ignored in a
 *       {@code paper-plugin.yml} and fails at runtime naming a class nobody wrote.</li>
 * </ul>
 *
 * <p>Reads the built jar, so it only runs after {@code package}. When there is no jar it says so and
 * stops rather than passing quietly — a test that silently checks nothing is worse than no test.
 */
class StandaloneJarTest {

    private static final Path TARGET = Path.of("target");

    private static final String MESSAGES = "de/raindancer/modules/tpa/messages.yml";

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

        assertThat(entries).anyMatch(name -> name.startsWith("de/raindancer/modules/tpa/"));
        assertThat(entries).contains("de/raindancer/modules/wrapper/ModulePlugin.class");
        assertThat(entries).contains("de/raindancer/modules/wrapper/ModuleBootstrap.class");
        assertThat(entries).anyMatch(name -> name.startsWith("de/raindancer/modules/api/"));
    }

    @Test
    @DisplayName("it holds no second copy of RainsCore")
    void coreIsNotShadedIn() {
        assertThat(entries())
                .as("a second RainsCore is a second of everything shared: two settings "
                        + "registries, two message tables, two menu listeners and two idea of who "
                        + "is part-way through a teleport, none of which know about the first")
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
                .contains("de.raindancer.modules.tpa.TpaModule");
    }

    @Test
    @DisplayName("the module's own wording is in the jar, beside its classes")
    void theMessagesAreThere() {
        assertThat(entries())
                .as("without messages.yml every line falls back to its key, which reads as a broken "
                        + "plugin rather than a missing file")
                .contains(MESSAGES);
        assertThat(entries())
                .as("at the jar root it would race RainsCore's own messages.yml, which "
                        + "join-classpath puts on the same classpath")
                .doesNotContain("messages.yml");
        assertThat(read(MESSAGES)).contains("tpa:");
    }

    @Test
    @DisplayName("the shaded module is the module that was just built")
    void theJarIsNotStale() throws IOException {
        // A stale shade is the worst kind of build mistake: the jar is newer than the source, it
        // loads, it enables, and it runs last week's code. Compared by class size rather than by
        // timestamp, because the timestamp is the thing that lies.
        int inTheJar = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(theJar()))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().endsWith("tpa/TpaSettings.class")) {
                    inTheJar = zip.readAllBytes().length;
                }
            }
        }
        int justBuilt = Files.readAllBytes(Path.of("..", "tpa-module", "target", "classes",
                "de", "raindancer", "modules", "tpa", "TpaSettings.class")).length;

        assertThat(inTheJar)
                .as("the TpaSettings in the jar is not the one the module build just produced — "
                        + "the shade picked up an older artifact, and everything shipped is that old")
                .isEqualTo(justBuilt);
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
        assertThat(yaml)
                .as("the one thing this module schedules goes through Core's per-entity scheduling, "
                        + "so this is true")
                .contains("folia-supported: true");

        // This file is hand-written rather than produced by StandaloneDescriptor, so it can drift
        // from it — and the claims plugin did exactly that, shipping with RainsCore declared for the
        // server phase only. That left the bootstrapper without RainsCore's classes at the moment it
        // discovers modules, and the plugin came up announcing that it contained none.
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
        assertThat(yaml).contains("version: '" + declaredVersion() + "'");
        assertThat(yaml)
                .as("the data folder is named after the plugin, and an upgrading server has its "
                        + "own settings — and its requests — in plugins/RainsTPA/")
                .contains("name: RainsTPA");
    }

    /** What this module's own pom says its plugin version is. The single place it is written. */
    private static String declaredVersion() {
        // Read from the pom rather than written out here. A literal made every version bump a red build
        // for a reason unrelated to the change that bumped it, which trains people to edit the assertion
        // without reading it — and this assertion exists to catch an unfilled placeholder.
        try {
            String pom = java.nio.file.Files.readString(java.nio.file.Path.of("pom.xml"));
            java.util.regex.Matcher found = java.util.regex.Pattern
                    .compile("<plugin\\.version>([^<]+)</plugin\\.version>").matcher(pom);
            assertThat(found.find()).as("pom.xml has no <plugin.version>").isTrue();
            return found.group(1);
        } catch (java.io.IOException unreadable) {
            throw new AssertionError("could not read pom.xml", unreadable);
        }
    }

    @org.junit.jupiter.api.Test
    @DisplayName("the module reports the same version the plugin does")
    void theModuleAgreesAboutItsVersion() {
        // Two places say the version: paper-plugin.yml, which the server prints, and the module's own
        // ModuleInfo, which its startup banner prints. They drift, and then the banner under the boot
        // line names a version that is not what is actually deployed.
        assertThat(moduleVersion())
                .as("ModuleInfo and pom.xml's <plugin.version> have to say the same thing")
                .isEqualTo(declaredVersion());
    }

    /**
     * The version in the module's own ModuleInfo, read from its source.
     *
     * <p>Read rather than called: info() is an instance method, and constructing the module means
     * constructing a server. The literal is what would be wrong, and the literal is what is checked.
     */
    private static String moduleVersion() {
        java.nio.file.Path source = java.nio.file.Path.of("../tpa-module/src/main/java/de/raindancer/modules/tpa/TpaModule.java");
        try {
            java.util.regex.Matcher found = java.util.regex.Pattern
                    .compile("ModuleInfo\\.of\\([^)]*?\"([\\d.]+)\"\\)")
                    .matcher(java.nio.file.Files.readString(source));
            assertThat(found.find()).as("no ModuleInfo.of(..) in %s", source).isTrue();
            return found.group(1);
        } catch (java.io.IOException unreadable) {
            throw new AssertionError("could not read " + source, unreadable);
        }
    }
}
