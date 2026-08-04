package de.raindancer.modules.pack;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That this module goes through RainsCore for the one thing it does.
 *
 * <h2>Why this is the most important test in the module</h2>
 * Because the shortcut is obvious, shorter, and works. {@code player.setResourcePack(url, hash, …)} is
 * one line and does exactly what this module is for — until anything contributes assets to Core's pack.
 * Then two things are sending to a player's <b>single</b> resource pack slot, whoever sends last wins,
 * and the loser's pack is silently gone. That is the precise collision {@code core.content.pack} was
 * written to arbitrate, and this module is the one most likely to reintroduce it, because for a long
 * while nothing would look wrong.
 *
 * <p>So the rule is not "prefer Core here". It is that the shortcut fails the build.
 */
class ReuseTest {

    private static final Path ROOT = Path.of("src/main/java/de/raindancer/modules/pack");

    private record Source(String name, String body) {
    }

    /**
     * The file with its comments taken out.
     *
     * <p>This module documents at length exactly what it must not do — the class note on
     * {@code PackModule} names {@code setResourcePack} in order to forbid it. Scanned raw, the
     * explanation reads as the violation.
     */
    private static String code(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }

    private static List<Source> module() {
        try (Stream<Path> files = Files.walk(ROOT)) {
            List<Source> found = new ArrayList<>();
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                found.add(new Source(ROOT.relativize(file).toString(), code(Files.readString(file))));
            }
            return found;
        } catch (IOException unreadable) {
            throw new AssertionError("could not read the module", unreadable);
        }
    }

    private static Map<String, String> wheelsAlreadyRound() {
        Map<String, String> forbidden = new LinkedHashMap<>();

        // The whole reason this module exists in this shape.
        forbidden.put("setResourcePack", "core.resourcePacks().host(HostedPack.at(...))");
        forbidden.put("removeResourcePacks", "ResourcePacks.clearFor, which knows who has what");
        forbidden.put("addResourcePack", "ResourcePacks.host");

        // Sending on join is Core's, and it already tracks who has the pack, retries a failed download
        // and leaves a refusal alone. A listener here would send it a second time to somebody who just
        // got it, which is a second prompt for one pack.
        forbidden.put("PlayerJoinEvent", "Core's PackListener, which it registers itself");
        forbidden.put("PlayerResourcePackStatusEvent", "Core's PackListener, which records the status");

        // Scheduling. The hash lookup is a network call and must never be on a server thread; on Folia a
        // hand-rolled BukkitRunnable is a crash rather than a stall.
        forbidden.put("getScheduler()", "de.raindancer.core.platform.util.Scheduling");
        forbidden.put("BukkitRunnable", "Scheduling.async, which handles Folia");
        forbidden.put("new Thread(", "Scheduling.async");

        // The settings. The record is the schema, so a hand-rolled config is a second, worse one.
        forbidden.put("saveDefaultConfig", "context.settings(PackSettings.class, ...)");
        forbidden.put("getConfig()", "the PackSettings snapshot");

        // Logging goes to this module's channel in the shared file.
        forbidden.put("getLogger()", "context.log()");
        forbidden.put("System.out.print", "context.log()");

        return forbidden;
    }

    @Test
    @DisplayName("the scan reads the module, so it cannot pass by looking at nothing")
    void theScanIsNotVacuous() {
        assertThat(module()).hasSizeGreaterThan(3);
        assertThat(module()).anyMatch(source -> source.name().endsWith("PackModule.java"));
    }

    @Test
    @DisplayName("nothing here sends a resource pack itself")
    void nothingGoesRoundCore() {
        List<String> reinvented = new ArrayList<>();
        for (Source source : module()) {
            for (Map.Entry<String, String> wheel : wheelsAlreadyRound().entrySet()) {
                if (source.body().contains(wheel.getKey())) {
                    reinvented.add(source.name() + " uses '" + wheel.getKey()
                            + "' — use " + wheel.getValue());
                }
            }
        }
        assertThat(reinvented)
                .as("a player has one resource pack slot, and two things writing to it means one of "
                        + "them is silently thrown away")
                .isEmpty();
    }

    @Test
    @DisplayName("it registers the pack with Core, which is the thing it is for")
    void itActuallyRegisters() {
        // The inverse of the scan above: it is not enough that the shortcut is absent. A module that
        // registered nothing at all would pass every rule here and send no pack, which is the same
        // outcome as not being installed.
        assertThat(module())
                .as("nothing hands a HostedPack to Core, so this module does nothing at all")
                .anyMatch(source -> source.body().contains("host(")
                        && source.body().contains("HostedPack"));
    }

    @Test
    @DisplayName("the hash lookup happens off the server's threads")
    void theLookupNeverBlocksTheBoot() {
        // A boot that waits on somebody else's web server is a boot that hangs when the pack host is
        // down — and a texture pack is not worth a server that does not start.
        String service = module().stream()
                .filter(source -> source.name().endsWith("PackRegistrationService.java"))
                .map(Source::body)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the registration service is gone"));

        assertThat(service).contains("Scheduling.async");
    }
}
