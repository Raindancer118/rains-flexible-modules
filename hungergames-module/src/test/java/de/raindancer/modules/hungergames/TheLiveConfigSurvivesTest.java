package de.raindancer.modules.hungergames;

import de.raindancer.core.data.settings.SettingsSchema;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.ui.effect.Effect;
import de.raindancer.modules.hungergames.service.HungerGamesCues;
import de.raindancer.modules.hungergames.store.LegacyConfigImport;
import de.raindancer.modules.hungergames.store.LegacyLootImport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That a real server's real configuration comes across intact.
 *
 * <h2>Why a copy of somebody's actual file is checked into this repository</h2>
 * Because every synthetic migration test passed while the migration was losing things. A fixture written by
 * the same person as the importer tests what they thought about; a file taken off a server that has run
 * tournaments for a season tests what somebody actually did. The two turned out to be very different:
 *
 * <ul>
 *   <li><b>Thirty-nine keys were written off as "not ported in this wave"</b> — {@code teams.*} and
 *       {@code sponsors.*}. This file has tuned nine of them. {@code teams.max-size: 10} would have become 2
 *       on the next restart: duos, in a tournament that plays teams of ten.</li>
 *   <li><b>The sponsor shop's twelve entries</b>, including nine custom items, would have been replaced by
 *       the shipped default's two placeholder potions. Players would have bought things that did not
 *       exist.</li>
 *   <li><b>Thirty sound and twenty-two particle bindings</b> — a fifteen-sound cannon among them — were
 *       reported as "moved to Core, copy them by hand". Nothing was reading them and nothing in Core could
 *       have held them, because a cue could carry one sound.</li>
 * </ul>
 *
 * <p>None of that was visible in a passing build. It is visible here, and it stays visible: if this file
 * stops round-tripping, this test fails, and the failure names the key.
 *
 * <p>The file is a configuration and nothing else — no player names, no UUIDs, no addresses. It is checked in
 * as {@code live-config.yml} beside this test.
 */
class TheLiveConfigSurvivesTest {

    @TempDir
    Path folder;

    private Path oldConfig;
    private Path oldLoot;
    private SettingsStore<HungerGamesSettings> store;

    @BeforeEach
    void copyTheLiveFilesOut() throws IOException {
        oldConfig = write("live-config.yml", LegacyConfigImport.FILE_NAME);
        oldLoot = write("live-loot.yml", "old-loot.yml");
        store = new SettingsStore<>(
                SettingsSchema.of(HungerGamesSettings.class, HungerGamesSettings.DEFAULTS),
                folder.resolve("config.yml"));
        store.load();
    }

    private Path write(String resource, String name) throws IOException {
        Path target = folder.resolve(name);
        try (InputStream in = TheLiveConfigSurvivesTest.class.getResourceAsStream(resource)) {
            assertThat(in).as("%s is missing from the test resources", resource).isNotNull();
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    @Nested
    @DisplayName("the settings")
    class Settings {

        @Test
        @DisplayName("every value this server had set arrives, and arrives unchanged")
        void nothingIsLost() {
            LegacyConfigImport.Report report = LegacyConfigImport.from(oldConfig, store);
            HungerGamesSettings now = store.current();

            // Every one of these was written by hand on a live server. The value on the right is what that
            // file says; anything else here means the import changed somebody's tournament.
            Map<String, Object> expected = new LinkedHashMap<>();
            expected.put("teams.max-size", 10);
            expected.put("border.minimum-size", 50.0D);
            expected.put("deathmatch.target-border-size", 50);
            expected.put("sponsors.tokens.amount-per-interval", 2);
            expected.put("sponsors.beacons.radius-max", 700);
            expected.put("sponsors.beacons.max-active", 4);

            assertThat(now.teamMaxSize()).as("teams.max-size").isEqualTo(10);
            assertThat(now.borderMinimumSize()).as("border.minimum-size").isEqualTo(50.0D);
            assertThat(now.deathmatchTargetBorderSize()).as("deathmatch.target-border-size").isEqualTo(50);
            assertThat(now.sponsorTokenAmountPerInterval())
                    .as("sponsors.tokens.amount-per-interval").isEqualTo(2);
            assertThat(now.sponsorBeaconRadiusMax()).as("sponsors.beacons.radius-max").isEqualTo(700);
            assertThat(now.sponsorBeaconMaxActive()).as("sponsors.beacons.max-active").isEqualTo(4);
            assertThat(now.sponsorBeaconBaseMaterial().name())
                    .as("sponsors.beacons.base-material").isEqualTo("GOLD_BLOCK");

            assertThat(report.applied())
                    .as("these are the keys the import claims it took: %s", report.applied())
                    .containsAll(expected.keySet());
        }

        @Test
        @DisplayName("the shop's twelve entries arrive, custom items and all")
        void theShopIsIntact() {
            LegacyConfigImport.from(oldConfig, store);

            assertThat(store.current().sponsorShopItems())
                    .as("nine of these twelve are custom items a player pays real tokens for. An import "
                            + "that dropped the list would leave them buying things that do not exist")
                    .hasSize(12)
                    .anyMatch(line -> line.startsWith("fiendfinder|"))
                    .anyMatch(line -> line.startsWith("krueckauwasser|"))
                    .anyMatch(line -> line.startsWith("aura_of_protection|"));
        }

        @Test
        @DisplayName("every custom item the shop sells is one this build actually has")
        void theShopSellsNothingImaginary() {
            LegacyConfigImport.from(oldConfig, store);

            var soldButMissing = store.current().sponsorShopItems().stream()
                    .map(line -> line.split("\\|"))
                    .filter(parts -> parts.length > 1 && parts[1].startsWith("ITEM:"))
                    // "ITEM:SMOKE_BOMB:1" names the item in the middle. The registries spell it
                    // hungergames:smoke-bomb, so the two have to be reconciled rather than compared.
                    .map(parts -> parts[1].split(":")[1].toLowerCase(java.util.Locale.ROOT)
                            .replace('_', '-'))
                    .filter(id -> !EVERY_ITEM_THIS_BUILD_HAS.contains(id))
                    .toList();

            assertThat(soldButMissing)
                    .as("this was the worst finding of the whole port: eight of the shop's items had no "
                            + "implementation at all, so a tribute spending twelve tokens on a lightning "
                            + "strike would have received nothing and had no way of knowing why")
                    .isEmpty();
        }

        /**
         * Every custom item this module registers, by its short id.
         *
         * <p>Written out rather than read from the registries, because the registries need a running server
         * and this question does not. It is checked against the services' own constants by
         * {@code CombatItemServiceTest}, {@code MobilityItemServiceTest} and {@code SurvivalItemServiceTest}
         * — this list is what the shop is judged against, and those are what judge the list.
         */
        private static final java.util.Set<String> EVERY_ITEM_THIS_BUILD_HAS = java.util.Set.of(
                "fiendfinder", "invisibility-cloak",
                "smoke-bomb", "medikit", "lightning-strike", "krueckauwasser", "aura-of-protection",
                "hermes-boots", "grappling-hook", "repulse", "leap",
                "feast", "war-kit", "stupidness-protector", "exmatrikulator");

        @Test
        @DisplayName("nothing in the file is silently ignored")
        void everythingIsAccountedFor() {
            LegacyConfigImport.Report report = LegacyConfigImport.from(oldConfig, store);

            assertThat(report.unknown())
                    .as("a key in somebody's file that the import neither takes nor explains is the exact "
                            + "silent loss this whole class exists to catch")
                    .isEmpty();
        }

        @Test
        @DisplayName("nothing was clamped, so no number quietly changed")
        void nothingWasQuietlyAdjusted() {
            LegacyConfigImport.Report report = LegacyConfigImport.from(oldConfig, store);

            assertThat(report.clamped())
                    .as("a clamp is legitimate and is reported, but this file should not trip one — if it "
                            + "does, a value somebody set is not the value they now have")
                    .isEmpty();
        }

        @Test
        @DisplayName("the round's own length is not touched by any of this")
        void theEveningIsStillTheirs() {
            LegacyConfigImport.from(oldConfig, store);

            // The file does not set game.duration, so it must still be the shipped default. Round length is
            // what the border phases, the drops and the deathmatch floor are all derived from, and an import
            // that moved it would reschedule the whole evening.
            assertThat(store.current().gameDurationMinutes()).isEqualTo(180);
        }
    }

    @Nested
    @DisplayName("the sounds and particles")
    class Cues {

        @Test
        @DisplayName("the tuned cues are taken over, not reported as somebody else's problem")
        void theSoundDesignSurvives() {
            Map<String, Effect> bound = new LinkedHashMap<>(HungerGamesCues.defaults());

            var report = LegacyConfigImport.importCues(oldConfig,
                    cue -> HungerGamesCues.names().contains(cue),
                    (cue, sounds, particles) -> {
                        bound.put(cue, rebindByHand(bound.get(cue), sounds, particles));
                        return true;
                    });

            assertThat(report)
                    .as("the import has to say what it did with them")
                    .anyMatch(line -> line.contains("taken over"));

            // The cannon is the one worth checking by hand: fifteen sounds and seven particle layers, with
            // the thunder arriving 1.25 seconds behind the explosion. Every part of that is a thing a
            // single-sound cue could not have held.
            Effect cannon = bound.get(HungerGamesCues.CANNON);
            assertThat(cannon.sounds().steps())
                    .as("a cannon reduced to one sound is a flat bang")
                    .hasSizeGreaterThanOrEqualTo(10);
            assertThat(cannon.sounds().lengthMillis())
                    .as("with the delay lost, the thunder fires with the explosion instead of behind it")
                    .isGreaterThanOrEqualTo(1_250L);
            assertThat(cannon.bursts().bursts())
                    .as("seven particle layers were written for this")
                    .hasSizeGreaterThanOrEqualTo(6);
        }

        @Test
        @DisplayName("a cue with only one half written keeps the other half")
        void halfARebindingIsNotHalfACue() {
            // sounds.startup-launch is set in the live file; effects.startup-launch is not. The particles
            // must survive, or tuning a sound would silently delete the puff of smoke that went with it.
            Map<String, Effect> bound = new LinkedHashMap<>(HungerGamesCues.defaults());
            Effect before = bound.get(HungerGamesCues.STARTUP_LAUNCH);
            assertThat(before.bursts().bursts()).isNotEmpty();

            LegacyConfigImport.importCues(oldConfig,
                    cue -> HungerGamesCues.names().contains(cue),
                    (cue, sounds, particles) -> {
                        bound.put(cue, rebindByHand(bound.get(cue), sounds, particles));
                        return true;
                    });

            assertThat(bound.get(HungerGamesCues.STARTUP_LAUNCH).bursts().bursts())
                    .as("the file rebinds this cue's sound and says nothing about its particles")
                    .isNotEmpty();
        }

        /**
         * The same rule {@code HungerGamesCues.rebind} applies, without a Core registry.
         *
         * <p>Spelled out here rather than calling that method, because it takes an {@code Effects} and this
         * test has no server. The rule is the one thing being checked, so it is written where it can be read
         * next to the assertion.
         */
        private static Effect rebindByHand(Effect existing, String sounds, String particles) {
            Effect was = existing == null ? Effect.silence() : existing;
            return new Effect(
                    sounds == null || sounds.isBlank()
                            ? was.sounds()
                            : de.raindancer.core.ui.effect.SoundSequence.parseAndExpand(sounds),
                    particles == null || particles.isBlank()
                            ? was.bursts()
                            : de.raindancer.core.ui.effect.ParticleSequence.parse(particles));
        }
    }

    @Nested
    @DisplayName("the loot")
    class Loot {

        @Test
        @DisplayName("all six tables come across, with their entries and their fill percentages")
        void everyTableSurvives() {
            LegacyLootImport.Imported imported = LegacyLootImport.from(oldLoot);

            assertThat(imported.tables().keySet())
                    .as("six tables, one per container type. The module shipped two empty ones, which is "
                            + "why every chest in the arena came out empty")
                    .contains("chest", "copper-chest", "trapped-chest", "barrel", "shelf");

            assertThat(imported.report().problems())
                    .as("a material this server does not have is skipped and reported; on this build the "
                            + "file should be clean")
                    .isEmpty();

            int entries = imported.tables().values().stream()
                    .mapToInt(table -> table.entries().size())
                    .sum();
            assertThat(entries)
                    .as("about a hundred entries, weighted by hand over a season")
                    .isGreaterThanOrEqualTo(80);
        }
    }
}
