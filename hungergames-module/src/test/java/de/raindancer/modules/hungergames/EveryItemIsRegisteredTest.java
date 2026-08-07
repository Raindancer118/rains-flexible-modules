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
 * That every item this module implements is actually handed to Core.
 *
 * <h2>The bug this was written for, reported by somebody looking at an empty page</h2>
 * "Why are none of the custom items there?" Because none of them was registered. Fourteen items across four
 * services, every one implemented, every one tested — and {@code HungerGamesWiring} never constructed a single
 * one of those services, so {@code register()} was never called and Core's registry stayed empty.
 *
 * <p>Nothing failed. The services compile, their tests pass in isolation, and the shop happily sold items that
 * did not exist because the shop validates against the registry and the registry had nothing to disagree with.
 * The only symptom was an item page with nothing on it.
 *
 * <p>This is the third time in this port that finished, tested code was simply not called — the session's own
 * store, the whole wiring class, and now the items. The lesson each time is the same: work that is only reached
 * by somebody remembering to reach it is work that eventually is not.
 */
class EveryItemIsRegisteredTest {

    private static final Path SOURCE = Path.of("src/main/java/de/raindancer/modules/hungergames");
    private static final Path WIRING = SOURCE.resolve("HungerGamesWiring.java");

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException unreadable) {
            throw new AssertionError("could not read " + file, unreadable);
        }
    }

    /** Every service in {@code service/} that defines items — the ones with a {@code register()} to call. */
    private static List<String> itemServices() {
        try (Stream<Path> files = Files.list(SOURCE.resolve("service"))) {
            List<String> found = new ArrayList<>();
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString().replace(".java", "");
                String body = read(file);
                // Told apart by what it does: a class that calls items.defineIfAbsent is a class that has
                // items to register. Not by name, because the next one will be called something else.
                if (body.contains("items.defineIfAbsent(") || body.contains("items.define(")) {
                    found.add(name);
                }
            }
            return found;
        } catch (IOException unreadable) {
            throw new AssertionError("could not read the services", unreadable);
        }
    }

    @Test
    @DisplayName("the scan found the item services, so it cannot pass by finding none")
    void theScanIsNotVacuous() {
        assertThat(itemServices())
                .as("four services define this module's fourteen items")
                .hasSizeGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("every item service is built by the wiring")
    void nothingIsLeftUnbuilt() {
        String wiring = read(WIRING);
        List<String> unbuilt = itemServices().stream()
                .filter(service -> !wiring.contains("new " + service + "("))
                .toList();

        assertThat(unbuilt)
                .as("a service nobody constructs registers nothing, and the only symptom is an empty item "
                        + "page — the shop will even sell what does not exist, because it validates against a "
                        + "registry that has nothing to disagree with")
                .isEmpty();
    }

    @Test
    @DisplayName("every item service is told to register")
    void nothingIsBuiltAndForgotten() {
        String wiring = read(WIRING);
        int registrations = 0;
        int at = wiring.indexOf(".register()");
        while (at >= 0) {
            registrations++;
            at = wiring.indexOf(".register()", at + 1);
        }
        assertThat(registrations)
                .as("constructing one of these does nothing on its own: register() is what puts the items "
                        + "and their abilities into Core")
                .isGreaterThanOrEqualTo(itemServices().size());
    }

    @Test
    @DisplayName("every item the shop can sell is one a service defines")
    void theShopSellsNothingImaginary() {
        // The shop's own defaults name nine custom items. Each has to be defined somewhere, or a tribute
        // spends twelve tokens on a lightning strike and receives nothing.
        String services = String.join("\n", itemServices().stream()
                .map(name -> read(SOURCE.resolve("service").resolve(name + ".java")))
                .toList());

        List<String> missing = new ArrayList<>();
        for (String line : HungerGamesSettings.DEFAULTS.sponsorShopItems()) {
            String[] parts = line.split("\\|");
            if (parts.length < 2 || !parts[1].startsWith("ITEM:")) {
                continue;
            }
            String id = parts[1].split(":")[1].toLowerCase(java.util.Locale.ROOT).replace('_', '-');
            if (!services.contains("\"" + id + "\"")) {
                missing.add(id);
            }
        }
        assertThat(missing)
                .as("the shop's shipped list sells these and no service defines them")
                .isEmpty();
    }
}
