package de.raindancer.modules.hungergames.standalone;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 *       listeners, two scoreboard arbiters and two boss bar owners — on a plugin whose whole run-up is a
 *       countdown on a boss bar and a scoreboard of who is left.</li>
 *   <li><b>A missing service file.</b> The jar loads, the plugin enables, and it contains no modules —
 *       the tournament is simply absent, with a clean boot log.</li>
 *   <li><b>Missing schematics.</b> Not classes, so nothing about compiling notices. {@code /init} pastes
 *       nothing and the arena is an empty field, discovered with people already waiting.</li>
 *   <li><b>A missing messages.yml, or one at the wrong path.</b> Every announcement falls back to its
 *       key, so the bell rings as {@code hungergames.round-begins}. It has to be beside the module's
 *       classes: RainsCore ships one at the root of its own jar and {@code join-classpath} puts it on
 *       this plugin's classpath, so a root lookup is a race between two files with the same name.</li>
 *   <li><b>A descriptor with the legacy {@code depend:} spelling</b>, which is ignored in a
 *       {@code paper-plugin.yml} and fails at runtime naming a class nobody wrote.</li>
 * </ul>
 *
 * <p>Reads the built jar, so it only runs after {@code package}. When there is no jar it says so and
 * stops rather than passing quietly — a test that silently checks nothing is worse than no test.
 */
class StandaloneJarTest {

    private static final Path TARGET = Path.of("target");

    private static final String MESSAGES = "de/raindancer/modules/hungergames/messages.yml";

    /**
     * The jar this build produced, named rather than guessed at.
     *
     * <p>The other standalones take the first jar in {@code target/}, which is right until the plugin
     * name or the version changes: {@code finalName} changes with it, the previous build's jar is still
     * lying there, and the first one found is whichever the filesystem lists first. That happened on the
     * rename to {@code RainsHungerGames} — every assertion here failed against a jar built before the
     * change, which reads exactly like a broken build rather than a stale file.
     */
    private static Path theJar() {
        String expected = declaredName() + "-" + declaredVersion() + ".jar";
        Path jar = TARGET.resolve(expected);
        assertThat(Files.exists(jar))
                .as("%s is not in target/ — this test reads the built plugin, so it needs `mvn package`. "
                        + "If a differently named jar is in there, it is a previous build's and should "
                        + "go: `mvn clean`", expected)
                .isTrue();
        return jar;
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

        assertThat(entries).anyMatch(name -> name.startsWith("de/raindancer/modules/hungergames/"));
        assertThat(entries).contains("de/raindancer/modules/wrapper/ModulePlugin.class");
        assertThat(entries).contains("de/raindancer/modules/wrapper/ModuleBootstrap.class");
        assertThat(entries).anyMatch(name -> name.startsWith("de/raindancer/modules/api/"));
    }

    @Test
    @DisplayName("it holds nothing but this module")
    void itIsTheGameAlone() {
        // The point of this jar as against the bundle. A server installing it has chosen the game and
        // its own moderation, and a moderation module arriving by accident would bring /ban, /vanish and
        // a staff roster nobody asked for — quietly overriding whatever already handles those.
        assertThat(entries())
                .as("this is the game on its own; the bundle is where moderation belongs")
                .noneMatch(name -> name.startsWith("de/raindancer/modules/moderation/"));
    }

    @Test
    @DisplayName("it holds no second copy of RainsCore")
    void coreIsNotShadedIn() {
        assertThat(entries())
                .as("a second RainsCore is a second boss bar owner and a second scoreboard arbiter on a "
                        + "plugin whose entire run-up is a countdown on one and a list of the living on "
                        + "the other, neither knowing about the other's")
                .noneMatch(name -> name.startsWith("de/raindancer/core/"));
    }

    @Test
    @DisplayName("it holds no copy of the server API or of WorldEdit")
    void nothingProvidedIsShadedIn() {
        assertThat(entries())
                .as("shading paper-api produces a plugin that runs against its own idea of the server")
                .noneMatch(name -> name.startsWith("org/bukkit/") || name.startsWith("io/papermc/"));
        assertThat(entries())
                .as("WorldEdit is a plugin on the server, declared in the descriptor. A copy in here is a "
                        + "second WorldEdit whose schematic reader is not the one the server's own "
                        + "commands use")
                .noneMatch(name -> name.startsWith("com/sk89q/"));
    }

    @Test
    @DisplayName("the module declares itself, or the plugin loads with nothing in it")
    void theServiceFileSurvivedTheShade() {
        String services = read("META-INF/services/de.raindancer.modules.api.FlexModule");

        assertThat(services)
                .as("without this the jar loads, the plugin enables, and it contains no modules at all")
                .contains("de.raindancer.modules.hungergames.HungerGamesModule");
    }

    @Test
    @DisplayName("the module's own wording is in the jar, beside its classes")
    void theMessagesAreThere() {
        assertThat(entries())
                .as("without messages.yml every announcement falls back to its key, which reads as a "
                        + "broken plugin rather than a missing file")
                .contains(MESSAGES);
        assertThat(entries())
                .as("at the jar root it would race RainsCore's own messages.yml, which join-classpath "
                        + "puts on the same classpath")
                .doesNotContain("messages.yml");
        assertThat(read(MESSAGES)).contains("hungergames:");
    }

    @Test
    @DisplayName("the schematics the arena is pasted from are in the jar")
    void theArenaCameAlong() {
        assertThat(entries())
                .as("resources rather than classes, so nothing about compiling notices when they stop "
                        + "being packaged — and the symptom is /init pasting an empty field")
                .anyMatch(name -> name.startsWith("de/raindancer/modules/hungergames/schem/")
                        && name.endsWith(".schem"));
    }

    @Test
    @DisplayName("the shaded module is the module that was just built")
    void theJarIsNotStale() throws IOException {
        // A stale shade is the worst kind of build mistake: the jar is newer than the source, it loads,
        // it enables, and it runs last week's code. Compared by class size rather than by timestamp,
        // because the timestamp is the thing that lies.
        int inTheJar = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(theJar()))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().endsWith("hungergames/HungerGamesSettings.class")) {
                    inTheJar = zip.readAllBytes().length;
                }
            }
        }
        int justBuilt = Files.readAllBytes(Path.of("..", "hungergames-module", "target", "classes",
                "de", "raindancer", "modules", "hungergames", "HungerGamesSettings.class")).length;

        assertThat(inTheJar)
                .as("the HungerGamesSettings in the jar is not the one the module build just produced — "
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
                .as("every timer a round runs goes through Core's own scheduling, so this is true")
                .contains("folia-supported: true");
        assertThat(yaml)
                .as("the arena is pasted from schematics and WorldEdit is what reads them. Absent, the "
                        + "plugin comes up healthy and /init pastes nothing")
                .contains("WorldEdit:");

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
                .as("an unfilled ${project.version} is a plugin whose version reads as a placeholder")
                .doesNotContain("${");
        assertThat(yaml).contains("version: '" + declaredVersion() + "'");
        assertThat(yaml)
                .as("the data folder is named after the plugin")
                .contains("name: " + declaredName());
        assertThat(declaredName())
                .as("deliberately not the bundle's name — two jars claiming one name is two plugins "
                        + "claiming one data folder, and a server with both installed loads whichever "
                        + "Paper reaches first, with the other one's files in it")
                .isNotEqualTo("TheHungerGames");
    }

    /** What this module's own pom says its plugin is called. The single place it is written. */
    private static String declaredName() {
        try {
            Matcher found = Pattern.compile("<plugin\\.name>([^<]+)</plugin\\.name>")
                    .matcher(Files.readString(Path.of("pom.xml")));
            assertThat(found.find()).as("pom.xml has no <plugin.name>").isTrue();
            return found.group(1);
        } catch (IOException unreadable) {
            throw new AssertionError("could not read pom.xml", unreadable);
        }
    }

    /** What this module's own pom says its plugin version is. The single place it is written. */
    private static String declaredVersion() {
        // Read from the pom rather than written out here. A literal made every version bump a red build
        // for a reason unrelated to the change that bumped it, which trains people to edit the assertion
        // without reading it — and this assertion exists to catch an unfilled placeholder.
        try {
            Matcher found = Pattern.compile("<plugin\\.version>([^<]+)</plugin\\.version>")
                    .matcher(Files.readString(Path.of("pom.xml")));
            assertThat(found.find()).as("pom.xml has no <plugin.version>").isTrue();
            return found.group(1);
        } catch (IOException unreadable) {
            throw new AssertionError("could not read pom.xml", unreadable);
        }
    }

    @Test
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
        Path source = Path.of(
                "../hungergames-module/src/main/java/de/raindancer/modules/hungergames/HungerGamesModule.java");
        try {
            Matcher found = Pattern.compile("ModuleInfo\\.of\\([^)]*?\"([\\d.]+)\"\\)")
                    .matcher(Files.readString(source));
            assertThat(found.find()).as("no ModuleInfo.of(..) in %s", source).isTrue();
            return found.group(1);
        } catch (IOException unreadable) {
            throw new AssertionError("could not read " + source, unreadable);
        }
    }
}
