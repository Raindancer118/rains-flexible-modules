package de.raindancer.modules.hungergames.bundle;

import de.raindancer.modules.hungergames.util.PermissionNodes;
import de.raindancer.modules.moderation.model.StaffRank;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
 * What the tournament jar actually contains, and whether its two modules agree with each other.
 *
 * <p>Everything {@code YeukSMP}'s equivalent checks applies here too — a second RainsCore, a dropped
 * service file, wording at the wrong path, a stale shade, two modules wanting one command. What is
 * different, and what makes this file worth having rather than copying, is the second half: this is the
 * only project in the reactor that compiles against <em>both</em> modules at once, and therefore the only
 * place where "moderation grants the node the hunger games module asks about" can be checked by the
 * compiler and a test rather than by two people reading two files.
 *
 * <p>That check matters because the coupling is a string. Moderation's {@link StaffRank} hands a mod
 * {@code hungergames.gamemaster}, and the hunger games module asks {@code hasPermission} for a string it
 * declares itself. Nothing links the two: rename either one and both modules still compile, both still
 * start, the boot log stays clean, and every gamemaster silently stops being one. That failure is
 * invisible until somebody tries to call a deathmatch in front of forty people.
 *
 * <p>Reads the built jar, so it only runs after {@code package}. When there is no jar it says so and
 * stops rather than passing quietly — a test that silently checks nothing is worse than no test.
 */
class BundleJarTest {

    private static final Path TARGET = Path.of("target");

    /**
     * The two, as: module id · source directory · package · module class · a settings class that proves
     * the shade took this build's output.
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
            new Bundled("hungergames", "hungergames-module", "hungergames",
                    "HungerGamesModule", "HungerGamesSettings"),
            new Bundled("moderation", "moderation-module", "moderation",
                    "ModerationModule", "ModerationSettings"));

    // ==================== the jar ====================

    /**
     * The jar this build produced, named rather than guessed at.
     *
     * <p>Taking the first jar in {@code target/} is right until the plugin name or the version changes:
     * {@code finalName} changes with it, the previous build's jar is still lying there, and the first one
     * found is whichever the filesystem lists first. Every assertion below then fails against a jar built
     * before the change, which reads exactly like a broken build rather than a stale file.
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

    /** What this project's own pom says its plugin is called. The single place it is written. */
    private static String declaredName() {
        Matcher found = Pattern.compile("<plugin\\.name>([^<]+)</plugin\\.name>")
                .matcher(readFile(Path.of("pom.xml")));
        assertThat(found.find()).as("pom.xml has no <plugin.name>").isTrue();
        return found.group(1);
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

    @Nested
    @DisplayName("what is in the jar")
    class Contents {

        @Test
        @DisplayName("both modules and the wrapper are in it")
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
        @DisplayName("both modules declare themselves — the shade merged the service files")
        void bothServiceFilesSurvivedTheShade() {
            String services = read("META-INF/services/de.raindancer.modules.api.FlexModule");

            for (Bundled module : BUNDLE) {
                assertThat(services)
                        .as("without ServicesResourceTransformer one module's service file wins and the "
                                + "other is absent: the plugin enables, the boot log is clean, and %s is "
                                + "simply not there", module.id())
                        .contains(module.servicePath());
            }
        }

        @Test
        @DisplayName("only the two — the bundle has not quietly grown")
        void nothingElseCameAlong() {
            List<String> entries = entries();

            for (String notBundled : List.of("de/raindancer/modules/claims/", "de/raindancer/modules/warp/",
                    "de/raindancer/modules/homes/", "de/raindancer/modules/tpa/",
                    "de/raindancer/modules/farmworld/", "de/raindancer/modules/pack/",
                    "de/raindancer/modules/names/")) {
                assertThat(entries)
                        .as("%s is not what this bundle is for. A tournament server's arena is not "
                                + "claimed and its map is not travelled, and shipping it means a server "
                                + "running a feature nobody chose, with its own commands and its own "
                                + "data folder", notBundled)
                        .noneMatch(name -> name.startsWith(notBundled));
            }
        }

        @Test
        @DisplayName("it holds no second copy of RainsCore")
        void coreIsNotShadedIn() {
            assertThat(entries())
                    .as("a second RainsCore is a second settings registry, a second message table and a "
                            + "second punishment store on one server, neither of which knows about the "
                            + "other's — and the punishments are the half that is somebody's ban record")
                    .noneMatch(name -> name.startsWith("de/raindancer/core/"));
        }

        @Test
        @DisplayName("it holds no copy of the server API or of WorldEdit")
        void nothingProvidedIsShadedIn() {
            assertThat(entries())
                    .as("shading paper-api produces a plugin that runs against its own idea of the server")
                    .noneMatch(name -> name.startsWith("org/bukkit/") || name.startsWith("io/papermc/"));
            assertThat(entries())
                    .as("WorldEdit is a plugin on the server, declared in the descriptor. A copy in here "
                            + "is a second WorldEdit whose schematic reader is not the one the server's "
                            + "own commands use")
                    .noneMatch(name -> name.startsWith("com/sk89q/"));
        }

        @Test
        @DisplayName("each module's wording travels with its classes")
        void theMessagesAreThere() {
            List<String> entries = entries();

            for (Bundled module : BUNDLE) {
                assertThat(entries)
                        .as("without messages.yml every line %s sends falls back to its key, which reads "
                                + "as a broken plugin rather than a missing file", module.id())
                        .contains(module.messagesPath());
            }
            assertThat(entries)
                    .as("nothing may sit at the jar root: RainsCore ships a messages.yml at its own root "
                            + "and join-classpath puts it on this plugin's classpath, so a root lookup "
                            + "is a race between files with one name")
                    .doesNotContain("messages.yml");
        }

        @Test
        @DisplayName("the schematics the arena is pasted from are in it")
        void theArenaCameAlong() {
            // Without them /init pastes nothing: no cornucopia, no platforms, no starting tubes. The
            // module would come up perfectly healthy and the arena would be an empty field.
            assertThat(entries())
                    .as("the schematics are resources rather than classes, so nothing about compiling "
                            + "notices when they stop being packaged")
                    .anyMatch(name -> name.startsWith("de/raindancer/modules/hungergames/schem/")
                            && name.endsWith(".schem"));
        }

        @Test
        @DisplayName("the shaded modules are the modules that were just built")
        void theJarIsNotStale() throws IOException {
            // A stale shade is the worst kind of build mistake: the jar is newer than the source, it
            // loads, it enables, and it runs last week's code. Compared by class size rather than by
            // timestamp, because the timestamp is the thing that lies.
            //
            // EVERY class, not one per module. This checked only each module's settings record and passed
            // while the jar was a build behind — the change that mattered was in HungerGamesModule, which
            // rewired the whole plugin, and the settings record had not moved a byte. One canary class is
            // one class's worth of confidence.
            List<String> stale = new ArrayList<>();
            for (Bundled module : BUNDLE) {
                Path classes = Path.of("..", module.directory(), "target", "classes");
                if (!Files.isDirectory(classes)) {
                    throw new AssertionError(module.id() + " has no target/classes — build it first");
                }
                try (var walk = Files.walk(classes)) {
                    for (Path built : walk.filter(path -> path.toString().endsWith(".class")).toList()) {
                        String entry = classes.relativize(built).toString().replace('\\', '/');
                        long inJar = sizeInJar(entry);
                        long onDisk = Files.size(built);
                        if (inJar != onDisk) {
                            stale.add(entry + " (jar " + inJar + " bytes, build " + onDisk + ")");
                        }
                    }
                }
            }
            assertThat(stale)
                    .as("the shade picked up an older artifact than the build just produced, so everything "
                            + "shipped from that module is that old — and the jar's own timestamp says "
                            + "otherwise. Always 'mvn clean install'")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("the two modules getting on")
    class Together {

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

        @Test
        @DisplayName("a mod's rank grants the node the game asks about, spelled identically")
        void aModIsAGamemaster() {
            // The whole reason the two are bundled. /promote somebody to Mod and they can run a round —
            // no second list of gamemasters kept anywhere, and nobody left off it.
            assertThat(StaffRank.MOD.nodes())
                    .as("rename either side of this string and both modules still compile, both still "
                            + "start, and every gamemaster silently stops being one")
                    .contains(PermissionNodes.GAMEMASTER);
            assertThat(StaffRank.ADMIN.nodes()).contains(PermissionNodes.ADMIN);
        }

        @Test
        @DisplayName("no rank grants the arena protection bypass")
        void theBypassIsNeverARankPerk() {
            // The hunger games module's own note: for the ten minutes somebody is fixing something, not
            // for a staff group. Checked from this side too, because this is where the grant is written.
            for (StaffRank rank : StaffRank.values()) {
                assertThat(rank.nodes())
                        .as("%s must not carry %s", rank, PermissionNodes.PROTECTION_BYPASS)
                        .doesNotContain(PermissionNodes.PROTECTION_BYPASS);
            }
        }

        @Test
        @DisplayName("every hunger games node a rank grants is one the game actually declares")
        void nothingIsGrantedThatNobodyReads() {
            List<String> declared = PermissionNodes.declared().stream()
                    .map(permission -> permission.getName())
                    .toList();

            List<String> granted = StaffRank.everyGrantableNode().stream()
                    .filter(node -> node.startsWith("hungergames."))
                    .toList();

            assertThat(granted)
                    .as("a rank granting a hungergames.* node the module has never heard of is a "
                            + "permission that does nothing, on every staff account, for ever — and the "
                            + "only symptom is somebody saying a button did not work")
                    .isNotEmpty()
                    .allSatisfy(node -> assertThat(declared).contains(node));
        }
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

    @Nested
    @DisplayName("the descriptor")
    class Descriptor {

        @Test
        @DisplayName("it says what Paper needs and nothing it ignores")
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
        @DisplayName("WorldEdit is required, because without it the arena is an empty field")
        void worldEditIsRequired() {
            String yaml = withoutComments(read("paper-plugin.yml"));

            int server = yaml.indexOf("server:\n");
            assertThat(yaml.substring(server))
                    .as("the schematics are read through WorldEdit. Optional, the plugin comes up "
                            + "healthy and /init pastes nothing, with forty people already waiting")
                    .contains("WorldEdit:");
        }

        @Test
        @DisplayName("Folia support is claimed only if both modules claim it")
        void foliaIsNotClaimedOnAnybodyElsesBehalf() {
            boolean bothSaySo = BUNDLE.stream().allMatch(BundleJarTest::declaresFoliaSupport);
            boolean thisJarSaysSo = withoutComments(read("paper-plugin.yml")).contains("folia-supported: true");

            assertThat(thisJarSaysSo)
                    .as("a jar is as Folia-safe as the least safe thing in it, and the modules that do "
                            + "not claim it are: %s",
                            BUNDLE.stream().filter(module -> !declaresFoliaSupport(module))
                                    .map(Bundled::id).toList())
                    .isEqualTo(bothSaySo);
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
                    .contains("name: " + declaredName());
            assertThat(declaredName())
                    .as("this jar is the product, and it takes the old plugin's name")
                    .isEqualTo("TheHungerGames");
        }

        @Test
        @DisplayName("the standalone does not want the same plugin name")
        void thereIsOnlyOneTheHungerGames() {
            // Two jars claiming one name is two plugins claiming one data folder, and a server with both
            // installed loads whichever Paper reaches first — with the other one's files in it.
            String standalone = readFile(Path.of("..", "hungergames-standalone", "pom.xml"));
            Matcher name = Pattern.compile("<plugin\\.name>([^<]+)</plugin\\.name>").matcher(standalone);
            assertThat(name.find()).as("the standalone pom has no <plugin.name>").isTrue();
            assertThat(name.group(1))
                    .as("the bundle is the product and takes the name; the standalone is the game on "
                            + "its own, for a server that already has its own moderation")
                    .isNotEqualTo("TheHungerGames");
        }

        @Test
        @DisplayName("the bundle's own pom asks for exactly the two")
        void thePomAndThisTestAgree() {
            String pom = readFile(Path.of("pom.xml"));

            // Otherwise a third module added to the pom ships entirely unverified: every check above
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

    /** What that module's own standalone plugin claims — the module's statement, not this jar's. */
    private static boolean declaresFoliaSupport(Bundled module) {
        Path descriptor = Path.of("..", module.directory().replace("-module", "-standalone"),
                "src", "main", "resources", "paper-plugin.yml");
        return withoutComments(readFile(descriptor)).contains("folia-supported: true");
    }

    /**
     * The settings only.
     *
     * <p>Every descriptor here explains itself, and this one's comment says in words that it does
     * <em>not</em> claim Folia support — which a plain {@code contains} reads as a claim.
     */
    private static String withoutComments(String yaml) {
        return yaml.lines()
                .filter(line -> !line.stripLeading().startsWith("#"))
                .reduce("", (all, line) -> all + line + "\n");
    }

    private static String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException unreadable) {
            throw new AssertionError("could not read " + path, unreadable);
        }
    }

    /** What this project's own pom says its plugin version is. The single place it is written. */
    private static String declaredVersion() {
        Matcher found = Pattern.compile("<plugin\\.version>([^<]+)</plugin\\.version>")
                .matcher(readFile(Path.of("pom.xml")));
        assertThat(found.find()).as("pom.xml has no <plugin.version>").isTrue();
        return found.group(1);
    }
}
