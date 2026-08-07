package de.raindancer.modules.hungergames.service;

import de.raindancer.core.content.items.ItemAbilities;
import de.raindancer.core.content.items.ItemTrigger;
import de.raindancer.core.content.items.ItemUse;
import de.raindancer.core.content.items.CustomItems;
import de.raindancer.modules.hungergames.HungerGamesSettings;
import de.raindancer.modules.hungergames.model.GamePhase;
import org.bukkit.Material;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives {@link SurvivalItemService} entirely through its own seams — no Bukkit type is ever mocked here,
 * which is the point of the seam pattern {@link ArenaItemService} already established for this module.
 */
class SurvivalItemServiceTest {

    /** What the two spoken lines said, so a test can assert them without a server. */
    private static final class RecordingVoice implements SurvivalItemService.Voice {
        final java.util.List<String> said = new java.util.ArrayList<>();

        @Override
        public void unleashed(UUID holder, java.time.Duration forHowLong) {
            said.add("unleashed:" + forHowLong.toSeconds());
        }

        @Override
        public void protectorIsPassive(UUID holder) {
            said.add("passive");
        }
    }

    /** For the registration test, which is about which items exist rather than what they say. */
    private static final class SilentVoice implements SurvivalItemService.Voice {
        @Override
        public void unleashed(UUID holder, java.time.Duration forHowLong) {
        }

        @Override
        public void protectorIsPassive(UUID holder) {
        }
    }

    private RecordingVoice voice;

    private GamePhase phase;
    private final AtomicLong clockMillis = new AtomicLong(0L);

    private boolean feastRefuses;
    private ItemUse lastFeastUse;
    private Duration lastFeastRegeneration;
    private int lastFeastRegenerationLevel;
    private int lastFeastGoldenApples;

    private boolean warKitRefuses;
    private ItemUse lastWarKitUse;
    private List<Material> lastWarKitPieces;

    private boolean rescueRefuses;
    private UUID lastRescueHolder;
    private Duration lastRescueRegeneration;
    private Duration lastRescueFireResistance;
    private double lastRescueShoveRadius;
    private double lastRescueShoveStrength;

    private final List<UUID> nextVolleyHits = new ArrayList<>();
    private UUID lastVolleyHolder;
    private double lastVolleyRadius;
    private int lastVolleyMaxTargets;
    private double lastVolleyDamage;
    private Duration lastVolleyFireDuration;

    private SurvivalItemService service;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        phase = GamePhase.LOBBY;
        ItemAbilities abilities = new ItemAbilities(clockMillis::get);
        CustomItems items = new CustomItems(dir.resolve("items.yml"));

        SurvivalItemService.Feasting feasting = (use, regeneration, level, apples) -> {
            lastFeastUse = use;
            lastFeastRegeneration = regeneration;
            lastFeastRegenerationLevel = level;
            lastFeastGoldenApples = apples;
            return !feastRefuses;
        };
        SurvivalItemService.Armoury armoury = (use, pieces) -> {
            lastWarKitUse = use;
            lastWarKitPieces = pieces;
            return !warKitRefuses;
        };
        SurvivalItemService.Rescue rescue = (holder, regeneration, fireResistance, shoveRadius, shoveStrength) -> {
            lastRescueHolder = holder;
            lastRescueRegeneration = regeneration;
            lastRescueFireResistance = fireResistance;
            lastRescueShoveRadius = shoveRadius;
            lastRescueShoveStrength = shoveStrength;
            return !rescueRefuses;
        };
        voice = new RecordingVoice();
        SurvivalItemService.Volley volley = (holder, radius, maxTargets, damage, fireDuration) -> {
            lastVolleyHolder = holder;
            lastVolleyRadius = radius;
            lastVolleyMaxTargets = maxTargets;
            lastVolleyDamage = damage;
            lastVolleyFireDuration = fireDuration;
            return List.copyOf(nextVolleyHits);
        };

        service = new SurvivalItemService(abilities, items, () -> phase, feasting, armoury, rescue, volley,
                voice, clockMillis::get, new Random(42), HungerGamesSettings.DEFAULTS);
        service.register();
    }

    private ItemUse rightClick(UUID player) {
        return new ItemUse(player, SurvivalItemService.FEAST, ItemTrigger.RIGHT_CLICK, null);
    }

    @Nested
    @DisplayName("register()")
    class Registration {

        @Test
        @DisplayName("defines all four items, and abilities for the three active ones")
        void definesEverything(@TempDir Path dir) {
            CustomItems items = new CustomItems(dir.resolve("items.yml"));
            ItemAbilities abilities = new ItemAbilities(clockMillis::get);
            SurvivalItemService another = new SurvivalItemService(abilities, items, () -> phase,
                    (u, r, l, a) -> true, (u, p) -> true, (h, r, f, sr, ss) -> true, (h, r, m, d, f) -> List.of(),
                    new SilentVoice(), clockMillis::get, new Random(1), HungerGamesSettings.DEFAULTS);

            another.register();

            assertThat(items.all()).extracting("id")
                    .containsExactlyInAnyOrder(SurvivalItemService.FEAST, SurvivalItemService.WAR_KIT,
                            SurvivalItemService.STUPIDNESS_PROTECTOR, SurvivalItemService.EXMATRIKULATOR);
            assertThat(abilities.byKey(SurvivalItemService.PLUGIN + ":" + SurvivalItemService.FEAST)).isPresent();
            assertThat(abilities.byKey(SurvivalItemService.PLUGIN + ":" + SurvivalItemService.WAR_KIT)).isPresent();
            assertThat(abilities.byKey(SurvivalItemService.PLUGIN + ":" + SurvivalItemService.EXMATRIKULATOR))
                    .isPresent();
            // The stupidness protector is deliberately passive — see the class javadoc — and its ability
            // exists only to say so. Without it, clicking the item is silent, and a passive thing that
            // says nothing when clicked is one somebody clicks until they decide it is broken.
            assertThat(abilities.byKey(SurvivalItemService.PLUGIN + ":" + SurvivalItemService.STUPIDNESS_PROTECTOR))
                    .isPresent();
        }

        @Test
        @DisplayName("right-clicking the protector explains itself and never spends the item")
        void theProtectorSaysItIsPassive() {
            assertThat(service.explainTheProtector(rightClick(UUID.randomUUID())))
                    .as("true would take the protector out of somebody's inventory for clicking it")
                    .isFalse();
            assertThat(voice.said).containsExactly("passive");
        }

        @Test
        @DisplayName("the exmatrikulator announces itself with the duration it is actually up for")
        void theExmatrikulatorSaysSo() {
            phase = GamePhase.RUNNING;

            service.useExmatrikulator(rightClick(UUID.randomUUID()));

            assertThat(voice.said)
                    .containsExactly("unleashed:" + SurvivalItemService.EXMATRIKULATOR_DURATION.toSeconds());
        }
    }

    @Nested
    @DisplayName("feast")
    class Feast {

        @Test
        @DisplayName("does nothing outside RUNNING")
        void refusesOutsideRunning() {
            phase = GamePhase.LOBBY;

            boolean result = service.useFeast(rightClick(UUID.randomUUID()));

            assertThat(result).isFalse();
            assertThat(lastFeastUse).isNull();
        }

        @Test
        @DisplayName("feeds the holder during RUNNING, with the tuned constants")
        void feedsDuringRunning() {
            phase = GamePhase.RUNNING;
            UUID player = UUID.randomUUID();

            boolean result = service.useFeast(rightClick(player));

            assertThat(result).isTrue();
            assertThat(lastFeastUse.player()).isEqualTo(player);
            assertThat(lastFeastRegeneration).isEqualTo(SurvivalItemService.FEAST_REGENERATION);
            assertThat(lastFeastRegenerationLevel).isEqualTo(SurvivalItemService.FEAST_REGENERATION_LEVEL);
            assertThat(lastFeastGoldenApples).isEqualTo(SurvivalItemService.FEAST_GOLDEN_APPLES);
        }

        @Test
        @DisplayName("a refused seam means the ability reports failure too")
        void refusedSeamPropagates() {
            phase = GamePhase.RUNNING;
            feastRefuses = true;

            boolean result = service.useFeast(rightClick(UUID.randomUUID()));

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("war kit")
    class WarKit {

        @Test
        @DisplayName("does nothing outside RUNNING")
        void refusesOutsideRunning() {
            phase = GamePhase.STARTUP;

            boolean result = service.useWarKit(rightClick(UUID.randomUUID()));

            assertThat(result).isFalse();
            assertThat(lastWarKitUse).isNull();
        }

        @Test
        @DisplayName("equips the full iron set during RUNNING")
        void equipsDuringRunning() {
            phase = GamePhase.RUNNING;

            boolean result = service.useWarKit(rightClick(UUID.randomUUID()));

            assertThat(result).isTrue();
            assertThat(lastWarKitPieces).isEqualTo(SurvivalItemService.WAR_KIT_ARMOUR);
            assertThat(lastWarKitPieces).containsExactly(Material.IRON_HELMET, Material.IRON_CHESTPLATE,
                    Material.IRON_LEGGINGS, Material.IRON_BOOTS);
        }

        @Test
        @DisplayName("a refused seam means the ability reports failure too")
        void refusedSeamPropagates() {
            phase = GamePhase.RUNNING;
            warKitRefuses = true;

            boolean result = service.useWarKit(rightClick(UUID.randomUUID()));

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("stupidness protector")
    class StupidnessProtector {

        @Test
        @DisplayName("does nothing outside RUNNING")
        void refusesOutsideRunning() {
            phase = GamePhase.LOBBY;

            boolean result = service.wouldSaveFrom(UUID.randomUUID(), "LAVA");

            assertThat(result).isFalse();
            assertThat(lastRescueHolder).isNull();
        }

        @Test
        @DisplayName("saves from lava, a fall, fire and an ordinary mob")
        void savesFromEnvironmentalCauses() {
            phase = GamePhase.RUNNING;
            UUID player = UUID.randomUUID();

            for (String cause : List.of("LAVA", "FALL", "FIRE", "ENTITY_ATTACK", "DROWNING")) {
                boolean result = service.wouldSaveFrom(player, cause);
                assertThat(result).as("cause " + cause).isTrue();
            }
            assertThat(lastRescueRegeneration).isEqualTo(SurvivalItemService.STUPIDNESS_REGENERATION);
            assertThat(lastRescueFireResistance).isEqualTo(SurvivalItemService.STUPIDNESS_FIRE_RESISTANCE);
            assertThat(lastRescueShoveRadius).isEqualTo(SurvivalItemService.STUPIDNESS_SHOVE_RADIUS);
            assertThat(lastRescueShoveStrength).isEqualTo(SurvivalItemService.STUPIDNESS_SHOVE_STRENGTH);
        }

        @Test
        @DisplayName("never saves from another tribute's kill")
        void neverSavesFromAPlayer() {
            phase = GamePhase.RUNNING;

            boolean result = service.wouldSaveFrom(UUID.randomUUID(), "player");

            assertThat(result).isFalse();
            assertThat(lastRescueHolder).as("the rescue seam must not even be consulted").isNull();
        }

        @Test
        @DisplayName("never saves from a custom item's damage, e.g. the exmatrikulator's lightning")
        void neverSavesFromACustomItem() {
            phase = GamePhase.RUNNING;

            boolean result = service.wouldSaveFrom(UUID.randomUUID(), "custom_item");

            assertThat(result).isFalse();
            assertThat(lastRescueHolder).isNull();
        }

        @Test
        @DisplayName("a refused rescue (no protector in inventory) reports failure")
        void refusedRescuePropagates() {
            phase = GamePhase.RUNNING;
            rescueRefuses = true;

            boolean result = service.wouldSaveFrom(UUID.randomUUID(), "lava");

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("exmatrikulator")
    class Exmatrikulator {

        @Test
        @DisplayName("does not raise an aura outside RUNNING")
        void refusesOutsideRunning() {
            phase = GamePhase.LOBBY;
            UUID player = UUID.randomUUID();

            boolean result = service.useExmatrikulator(rightClick(player));
            service.pulse();

            assertThat(result).isFalse();
            assertThat(lastVolleyHolder).isNull();
        }

        @Test
        @DisplayName("the first volley fires immediately, with the tuned constants")
        void firstVolleyFiresImmediately() {
            phase = GamePhase.RUNNING;
            UUID player = UUID.randomUUID();

            boolean result = service.useExmatrikulator(rightClick(player));
            service.pulse();

            assertThat(result).isTrue();
            assertThat(lastVolleyHolder).isEqualTo(player);
            assertThat(lastVolleyRadius).isEqualTo(SurvivalItemService.EXMATRIKULATOR_RADIUS);
            assertThat(lastVolleyMaxTargets).isEqualTo(SurvivalItemService.EXMATRIKULATOR_MAX_TARGETS);
            assertThat(lastVolleyDamage).isEqualTo(SurvivalItemService.EXMATRIKULATOR_DAMAGE);
            assertThat(lastVolleyFireDuration).isEqualTo(SurvivalItemService.EXMATRIKULATOR_FIRE_DURATION);
        }

        @Test
        @DisplayName("a second pulse before the interval has elapsed fires no further volley")
        void noVolleyBeforeItsTime() {
            phase = GamePhase.RUNNING;
            service.useExmatrikulator(rightClick(UUID.randomUUID()));
            service.pulse();
            lastVolleyHolder = null;

            clockMillis.addAndGet(SurvivalItemService.EXMATRIKULATOR_INTERVAL.toMillis() - 1);
            service.pulse();

            assertThat(lastVolleyHolder).as("the interval has not elapsed yet").isNull();
        }

        @Test
        @DisplayName("a pulse once the interval has elapsed fires another volley")
        void volleyFiresOnceIntervalElapses() {
            phase = GamePhase.RUNNING;
            service.useExmatrikulator(rightClick(UUID.randomUUID()));
            service.pulse();
            lastVolleyHolder = null;

            clockMillis.addAndGet(SurvivalItemService.EXMATRIKULATOR_INTERVAL.toMillis());
            service.pulse();

            assertThat(lastVolleyHolder).isNotNull();
        }

        @Test
        @DisplayName("the aura fires no more volleys once its duration has run out")
        void auraEndsAfterItsDuration() {
            phase = GamePhase.RUNNING;
            service.useExmatrikulator(rightClick(UUID.randomUUID()));
            service.pulse();
            lastVolleyHolder = null;

            clockMillis.addAndGet(SurvivalItemService.EXMATRIKULATOR_DURATION.toMillis() + 1);
            service.pulse();

            assertThat(lastVolleyHolder).as("the aura should have expired").isNull();
        }

        @Test
        @DisplayName("a struck victim carries an exmatrikulation phrase within the kill window")
        void struckVictimCarriesAPhrase() {
            phase = GamePhase.RUNNING;
            UUID victim = UUID.randomUUID();
            nextVolleyHits.add(victim);

            service.useExmatrikulator(rightClick(UUID.randomUUID()));
            service.pulse();

            Optional<String> phrase = service.exmatrikulationPhrase(victim, "Katniss");
            assertThat(phrase).isPresent();
            assertThat(phrase.get()).contains("Katniss")
                    .doesNotContain(SurvivalItemService.KILLER_PLACEHOLDER)
                    .doesNotContain(SurvivalItemService.MODULE_PLACEHOLDER);
        }

        @Test
        @DisplayName("the lines are the server's own, not a set written into the code")
        void theOwnersWordingIsUsed() {
            // The regression: the port wrote three fixed English templates and three made-up module names
            // into the class, so a server's own items.exmatrikulator.death-messages and .modules — nine
            // modules and five lines on the live one — could never appear again, and nothing said so.
            service.settings(de.raindancer.modules.hungergames.Tweak.of(HungerGamesSettings.DEFAULTS,
                    "exmatrikulatorDeathMessages", List.of("was sent down by %killer% over %modul%."),
                    "exmatrikulatorModules", List.of("Advanced Basket Weaving")));
            phase = GamePhase.RUNNING;
            UUID victim = UUID.randomUUID();
            nextVolleyHits.add(victim);
            service.useExmatrikulator(rightClick(UUID.randomUUID()));
            service.pulse();

            assertThat(service.exmatrikulationPhrase(victim, "Katniss"))
                    .contains("was sent down by Katniss over Advanced Basket Weaving.");
        }

        @Test
        @DisplayName("an owner who emptied the list gets the vanilla death message back")
        void switchedOff() {
            // An empty list is a decision, not a hole to fill with something invented.
            service.settings(de.raindancer.modules.hungergames.Tweak.of(HungerGamesSettings.DEFAULTS,
                    "exmatrikulatorDeathMessages", List.<String>of()));
            phase = GamePhase.RUNNING;
            UUID victim = UUID.randomUUID();
            nextVolleyHits.add(victim);
            service.useExmatrikulator(rightClick(UUID.randomUUID()));
            service.pulse();

            assertThat(service.exmatrikulationPhrase(victim, "Katniss")).isEmpty();
        }

        @Test
        @DisplayName("no modules configured still produces a death message")
        void noModulesNamed() {
            service.settings(de.raindancer.modules.hungergames.Tweak.of(HungerGamesSettings.DEFAULTS,
                    "exmatrikulatorModules", List.<String>of()));
            phase = GamePhase.RUNNING;
            UUID victim = UUID.randomUUID();
            nextVolleyHits.add(victim);
            service.useExmatrikulator(rightClick(UUID.randomUUID()));
            service.pulse();

            assertThat(service.exmatrikulationPhrase(victim, "Katniss"))
                    .isPresent()
                    .get(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                    .doesNotContain(SurvivalItemService.MODULE_PLACEHOLDER);
        }

        @Test
        @DisplayName("the phrase expires once the kill window has passed")
        void phraseExpiresAfterTheKillWindow() {
            phase = GamePhase.RUNNING;
            UUID victim = UUID.randomUUID();
            nextVolleyHits.add(victim);
            service.useExmatrikulator(rightClick(UUID.randomUUID()));
            service.pulse();

            clockMillis.addAndGet(SurvivalItemService.EXMATRIKULATOR_KILL_WINDOW.toMillis() + 1);

            assertThat(service.exmatrikulationPhrase(victim, "Katniss")).isEmpty();
        }

        @Test
        @DisplayName("a victim never struck carries no phrase at all")
        void unstruckVictimCarriesNoPhrase() {
            assertThat(service.exmatrikulationPhrase(UUID.randomUUID(), "Katniss")).isEmpty();
        }
    }
}
