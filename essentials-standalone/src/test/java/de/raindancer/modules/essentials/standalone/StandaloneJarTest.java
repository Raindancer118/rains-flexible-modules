package de.raindancer.modules.essentials.standalone;

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
 * What the shipped jar actually contains — the same checks tpa-standalone's own test makes, against
 * the same build mistakes: a second RainsCore shaded in, a missing service file, a missing or
 * misplaced {@code messages.yml}, a descriptor with the legacy {@code depend:} spelling, or a stale
 * shade.
 */
class StandaloneJarTest {

    private static final Path TARGET = Path.of("target");

    private static final String MESSAGES = "de/raindancer/modules/essentials/messages.yml";

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

        assertThat(entries).anyMatch(name -> name.startsWith("de/raindancer/modules/essentials/"));
        assertThat(entries).contains("de/raindancer/modules/wrapper/ModulePlugin.class");
        assertThat(entries).contains("de/raindancer/modules/wrapper/ModuleBootstrap.class");
        assertThat(entries).anyMatch(name -> name.startsWith("de/raindancer/modules/api/"));
    }

    @Test
    @DisplayName("it holds no second copy of RainsCore")
    void coreIsNotShadedIn() {
        assertThat(entries())
                .as("a second RainsCore is a second of everything shared: two settings registries, "
                        + "two message tables, two identity stores, none of which know about the "
                        + "first")
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
                .contains("de.raindancer.modules.essentials.EssentialsModule");
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
        assertThat(read(MESSAGES)).contains("essentials:");
    }

    @Test
    @DisplayName("the bundled nickname blocklist is in the jar, beside its classes")
    void theBlocklistIsThere() {
        String blocklist = "de/raindancer/modules/essentials/blocklist.yml";
        assertThat(entries())
                .as("without this a fresh install starts with no blocklist file at all until one "
                        + "is written some other way")
                .contains(blocklist);
        assertThat(read(blocklist))
                .contains("action: ban")
                .contains("action: report");
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
        assertThat(yaml).contains("name: RainsEssentials");
    }

    /** What this module's own pom says its plugin version is. The single place it is written. */
    private static String declaredVersion() {
        try {
            String pom = Files.readString(Path.of("pom.xml"));
            java.util.regex.Matcher found = java.util.regex.Pattern
                    .compile("<plugin\\.version>([^<]+)</plugin\\.version>").matcher(pom);
            assertThat(found.find()).as("pom.xml has no <plugin.version>").isTrue();
            return found.group(1);
        } catch (IOException unreadable) {
            throw new AssertionError("could not read pom.xml", unreadable);
        }
    }

    @Test
    @DisplayName("the module reports the same version the plugin does")
    void theModuleAgreesAboutItsVersion() {
        assertThat(moduleVersion())
                .as("ModuleInfo and pom.xml's <plugin.version> have to say the same thing")
                .isEqualTo(declaredVersion());
    }

    private static String moduleVersion() {
        Path source = Path.of(
                "../essentials-module/src/main/java/de/raindancer/modules/essentials/EssentialsModule.java");
        try {
            java.util.regex.Matcher found = java.util.regex.Pattern
                    .compile("ModuleInfo\\.of\\([^)]*?\"([\\d.]+)\"\\)")
                    .matcher(Files.readString(source));
            assertThat(found.find()).as("no ModuleInfo.of(..) in %s", source).isTrue();
            return found.group(1);
        } catch (IOException unreadable) {
            throw new AssertionError("could not read " + source, unreadable);
        }
    }
}
