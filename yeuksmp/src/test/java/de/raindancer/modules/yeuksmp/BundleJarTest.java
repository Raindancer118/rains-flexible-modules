package de.raindancer.modules.yeuksmp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the bundled jar actually contains.
 *
 * <p>The single-module standalones each have a test of this shape, and everything they check applies
 * here too — a second RainsCore, a dropped service file, wording at the wrong path, a stale shade.
 * What is different, and what this file exists for, is that twelve modules are in one jar:
 *
 * <ul>
 *   <li><b>The service files have to be merged, not chosen.</b> Twelve modules mean twelve copies of
 *       {@code META-INF/services/de.raindancer.modules.api.FlexModule}. Without the shade plugin's
 *       {@code ServicesResourceTransformer} one of them wins outright and the other eleven are
 *       simply not in the plugin: no error, no log line, eleven features missing from a server that
 *       thinks it installed them.</li>
 *   <li><b>Two modules must not want the same command.</b> Separately installed, a clash is Paper
 *       namespacing the loser and an operator noticing. In one plugin the second registration of a
 *       name replaces the first inside the same namespace, and the module that lost the race answers
 *       nothing while looking perfectly healthy in the boot log.</li>
 *   <li><b>Only the twelve.</b> A bundle is a statement about what this server runs. Farm worlds are
 *       in the reactor and deliberately not in here, and a dependency added by reflex would ship it
 *       without anybody deciding to.</li>
 * </ul>
 *
 * <p>Reads the built jar, so it only runs after {@code package}. When there is no jar it says so and
 * stops rather than passing quietly — a test that silently checks nothing is worse than no test.
 */
class BundleJarTest {

    private static final Path TARGET = Path.of("target");

    /**
     * The twelve, as: module id · source directory · package · service class · a settings class that
     * proves the shade took this build's output.
     *
     * <p>One list, used by every test below, so adding a seventh module to the bundle is one line
     * here and not seven edits — and forgetting the line fails the count check rather than quietly
     * leaving the new module unverified.
     */
    private record Bundled(String id, String directory, String pack, String moduleClass, String settings) {

        String servicePath() {
            return "de.raindancer.modules." + pack + "." + moduleClass;
        }

        String classPrefix() {
            return "de/raindancer/modules/" + pack + "/";
        }

        String messagesPath() {
            return classPrefix() + "messages.yml";
        }

        String settingsEntry() {
            return classPrefix() + settings + ".class";
        }
    }

    private static final List<Bundled> BUNDLE = List.of(
            new Bundled("claims", "claims-module", "claims", "ClaimsModule", "ClaimSettings"),
            new Bundled("tpa", "tpa-module", "tpa", "TpaModule", "TpaSettings"),
            new Bundled("warps", "warp-module", "warp", "WarpModule", "WarpSettings"),
            new Bundled("moderation", "moderation-module", "moderation", "ModerationModule", "ModerationSettings"),
            new Bundled("serverpack", "pack-module", "pack", "PackModule", "PackSettings"),
            new Bundled("names", "names-module", "names", "NamesModule", "NamesSettings"),
            new Bundled("homes", "homes-module", "homes", "HomeModule", "HomeSettings"),
            new Bundled("rtp", "rtp-module", "rtp", "RtpModule", "RtpSettings"),
            new Bundled("essentials", "essentials-module", "essentials", "EssentialsModule", "EssentialsSettings"),
            new Bundled("chat", "chat-module", "chat", "ChatModule", "ChatSettings"),
            new Bundled("mannequin", "mannequin-module", "mannequin", "MannequinModule", "MannequinSettings"),
            new Bundled("invsnap", "invsnap-module", "invsnap", "InvSnapModule", "InvSnapSettings"));

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

    private static int sizeInJar(String entryName) {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(theJar()))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals(entryName)) {
                    return zip.readAllBytes().length;
                }
            }
        } catch (IOException unreadable) {
            throw new AssertionError("could not read " + entryName, unreadable);
        }
        throw new AssertionError(entryName + " is not in the jar");
    }

    @Test
    @DisplayName("all twelve modules and the wrapper are in the jar")
    void itContainsWhatItShould() {
        List<String> entries = entries();

        for (Bundled module : BUNDLE) {
            assertThat(entries)
                    .as("%s is in the bundle's pom, so its classes have to be in the jar", module.id())
                    .anyMatch(name -> name.startsWith(module.classPrefix()));
        }
        assertThat(entries).contains("de/raindancer/modules/wrapper/ModulePlugin.class");
        assertThat(entries).contains("de/raindancer/modules/wrapper/ModuleBootstrap.class");
        assertThat(entries).anyMatch(name -> name.startsWith("de/raindancer/modules/api/"));
    }

    @Test
    @DisplayName("every module declares itself — the shade merged the service files rather than picking one")
    void allServiceFilesSurvivedTheShade() {
        String services = read("META-INF/services/de.raindancer.modules.api.FlexModule");

        for (Bundled module : BUNDLE) {
            assertThat(services)
                    .as("without ServicesResourceTransformer one module's service file wins and the "
                            + "rest are absent: the plugin enables, the boot log is clean, and %s is "
                            + "simply not there", module.id())
                    .contains(module.servicePath());
        }
    }

    @Test
    @DisplayName("only the twelve — the bundle has not quietly grown")
    void nothingElseCameAlong() {
        List<String> entries = entries();

        for (String notBundled : List.of("de/raindancer/modules/farmworld/")) {
            assertThat(entries)
                    .as("%s is not one of the twelve this bundle is for. Shipping it means a server "
                            + "running a feature nobody chose, with its own commands and its own "
                            + "data folder", notBundled)
                    .noneMatch(name -> name.startsWith(notBundled));
        }
    }

    @Test
    @DisplayName("it holds no second copy of RainsCore")
    void coreIsNotShadedIn() {
        assertThat(entries())
                .as("a second RainsCore is a second settings registry, a second message table, a "
                        + "second place store and a second punishment store on one server, none of "
                        + "which know about each other's")
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
    @DisplayName("each module's wording travels with its classes")
    void theMessagesAreThere() {
        List<String> entries = entries();

        for (Bundled module : BUNDLE) {
            if (!hasBundledWording(module)) {
                // The server pack ships none deliberately: what a player sees is the client's own
                // download prompt, and everything else there is for the operator and goes to the log.
                continue;
            }
            assertThat(entries)
                    .as("without messages.yml every line %s sends falls back to its key, which reads "
                            + "as a broken plugin rather than a missing file", module.id())
                    .contains(module.messagesPath());
        }
        assertThat(entries)
                .as("nothing may sit at the jar root: RainsCore ships a messages.yml at its own root "
                        + "and join-classpath puts it on this plugin's classpath, so a root lookup "
                        + "is a race between files with one name — and here it would be a race "
                        + "between twelve of them")
                .doesNotContain("messages.yml");
    }

    /** Whether that module ships wording at all — read from its own resources, not assumed. */
    private static boolean hasBundledWording(Bundled module) {
        return Files.exists(Path.of("..", module.directory(), "src", "main", "resources")
                .resolve(module.classPrefix()).resolve("messages.yml"));
    }

    @Test
    @DisplayName("the shaded modules are the modules that were just built")
    void theJarIsNotStale() throws IOException {
        // A stale shade is the worst kind of build mistake: the jar is newer than the source, it
        // loads, it enables, and it runs last week's code. Compared by class size rather than by
        // timestamp, because the timestamp is the thing that lies. Twelve modules mean twelve chances of
        // it, and one stale artifact among five fresh ones is the version nobody would suspect.
        for (Bundled module : BUNDLE) {
            Path justBuilt = Path.of("..", module.directory(), "target", "classes")
                    .resolve(module.settingsEntry());

            assertThat(sizeInJar(module.settingsEntry()))
                    .as("the %s in the jar is not the one %s's build just produced — the shade "
                            + "picked up an older artifact, and everything shipped from that module "
                            + "is that old", module.settings(), module.id())
                    .isEqualTo(Files.readAllBytes(justBuilt).length);
        }
    }

    @Test
    @DisplayName("no two modules want the same command")
    void thereIsNoCommandClash() {
        Map<String, String> claimedBy = new LinkedHashMap<>();
        List<String> clashes = new ArrayList<>();

        for (Bundled module : BUNDLE) {
            for (String name : commandNamesOf(module)) {
                String first = claimedBy.putIfAbsent(name, module.id());
                if (first != null) {
                    clashes.add("/" + name + " is wanted by both " + first + " and " + module.id());
                }
            }
        }

        assertThat(clashes)
                .as("installed separately a clash is Paper namespacing the loser, which somebody "
                        + "notices. In one plugin the second registration replaces the first in the "
                        + "same namespace: one module's command silently answers the other's code")
                .isEmpty();
        assertThat(claimedBy)
                .as("no command names were found at all, which means the scan below stopped matching "
                        + "the source rather than that the bundle is clean")
                .isNotEmpty();
    }

    private static final Pattern DECLARED = Pattern.compile("ModuleCommand\\.of\\(\\s*\"([^\"]+)\"");
    private static final Pattern ALIASED = Pattern.compile("\\.aliased\\(([^)]*)\\)");
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");

    /**
     * Every name and alias that module registers, read from its {@code *Commands} source.
     *
     * <p>Read rather than called: {@code commands()} needs the module's services, and those need a
     * running server. The literals are what would clash and the literals are what is checked.
     */
    private static List<String> commandNamesOf(Bundled module) {
        Path sources = Path.of("..", module.directory(), "src", "main", "java",
                "de", "raindancer", "modules", module.pack());
        List<String> names = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sources)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith("Commands.java"))
                    .toList()) {
                String source = Files.readString(file);
                Matcher declared = DECLARED.matcher(source);
                while (declared.find()) {
                    names.add(declared.group(1));
                }
                Matcher aliased = ALIASED.matcher(source);
                while (aliased.find()) {
                    Matcher quoted = QUOTED.matcher(aliased.group(1));
                    while (quoted.find()) {
                        names.add(quoted.group(1));
                    }
                }
            }
        } catch (IOException unreadable) {
            throw new AssertionError("could not read " + sources, unreadable);
        }
        return names;
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
    @DisplayName("Folia support is claimed only if every module in the jar claims it")
    void foliaIsNotClaimedOnAnybodyElsesBehalf() {
        boolean everyModuleSaysSo = BUNDLE.stream().allMatch(BundleJarTest::declaresFoliaSupport);
        boolean thisJarSaysSo = withoutComments(read("paper-plugin.yml")).contains("folia-supported: true");

        assertThat(thisJarSaysSo)
                .as("a jar is as Folia-safe as the least safe thing in it, and the modules that do "
                        + "not claim it are: %s",
                        BUNDLE.stream().filter(module -> !declaresFoliaSupport(module))
                                .map(Bundled::id).toList())
                .isEqualTo(everyModuleSaysSo);
    }

    /** What that module's own standalone plugin claims — the module's statement, not this jar's. */
    private static boolean declaresFoliaSupport(Bundled module) {
        Path descriptor = Path.of("..", module.directory().replace("-module", "-standalone"),
                "src", "main", "resources", "paper-plugin.yml");
        try {
            return withoutComments(Files.readString(descriptor)).contains("folia-supported: true");
        } catch (IOException unreadable) {
            throw new AssertionError("could not read " + descriptor, unreadable);
        }
    }

    /**
     * The settings only.
     *
     * <p>Every descriptor here explains itself, and this one's comment says in words that it does
     * <em>not</em> claim Folia support — which a plain {@code contains} read as a claim, and the
     * test failed on the sentence stating the thing it was checking for.
     */
    private static String withoutComments(String yaml) {
        return yaml.lines()
                .filter(line -> !line.stripLeading().startsWith("#"))
                .reduce("", (all, line) -> all + line + "\n");
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
                .as("the data folder is named after the plugin, and every module's folder is under it")
                .contains("name: YeukSMP");
    }

    /** What this project's own pom says its plugin version is. The single place it is written. */
    private static String declaredVersion() {
        try {
            String pom = Files.readString(Path.of("pom.xml"));
            Matcher found = Pattern.compile("<plugin\\.version>([^<]+)</plugin\\.version>").matcher(pom);
            assertThat(found.find()).as("pom.xml has no <plugin.version>").isTrue();
            return found.group(1);
        } catch (IOException unreadable) {
            throw new AssertionError("could not read pom.xml", unreadable);
        }
    }

    @Test
    @DisplayName("the bundle's own pom asks for exactly the twelve")
    void thePomAndThisTestAgree() {
        String pom;
        try {
            pom = Files.readString(Path.of("pom.xml"));
        } catch (IOException unreadable) {
            throw new AssertionError("could not read pom.xml", unreadable);
        }

        // Otherwise a seventh module added to the pom ships entirely unverified: every check above
        // walks BUNDLE, so a module missing from that list is a module nothing here looks at.
        List<String> inThePom = new ArrayList<>();
        Matcher found = Pattern.compile("<artifactId>([a-z]+)-module</artifactId>").matcher(pom);
        while (found.find()) {
            inThePom.add(found.group(1) + "-module");
        }

        assertThat(inThePom)
                .containsExactlyInAnyOrderElementsOf(BUNDLE.stream().map(Bundled::directory).toList());
    }
}
