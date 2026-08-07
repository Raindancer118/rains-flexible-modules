package de.raindancer.modules.hungergames.service;

import de.raindancer.core.content.items.CustomItems;
import de.raindancer.core.content.items.ItemAbilities;
import de.raindancer.core.content.items.ItemAbility;
import de.raindancer.core.content.items.ItemTrigger;
import de.raindancer.core.content.items.ItemUse;
import de.raindancer.modules.hungergames.HungerGamesSettings;
import de.raindancer.modules.hungergames.model.GamePhase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Fiendfinder is single use, exactly as the source had it: {@code Fiendfinder.activate} returns
 * whether to consume the item and knows nothing about a wait before the next one.
 *
 * <p>The regression this guards: an earlier pass of this port gave it a fifteen-second cooldown on top of
 * that, which is not a smaller version of the source's item, it is a different one — the source's
 * Fiendfinder is spent finding one enemy, once; a cooled-down version can be right-clicked again a few
 * seconds later without spending a second one, which is not what "single use" means.
 */
class ArenaItemServiceTest {

    private CustomItems items;
    private ItemAbilities abilities;
    private ArenaItemService service;
    private GamePhase phase;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        items = new CustomItems(dir.resolve("items.yml"));
        abilities = new ItemAbilities(() -> 0L);
        phase = GamePhase.RUNNING;
        service = new ArenaItemService(abilities, items, () -> phase, use -> true,
                HungerGamesSettings.DEFAULTS);
    }

    @Test
    @DisplayName("registers with no cooldown at all — not a short one, none")
    void noCooldownWhatsoever() {
        service.register();

        ItemAbility ability = abilities.byKey(ArenaItemService.PLUGIN + ":" + ArenaItemService.FIENDFINDER)
                .orElseThrow();
        assertThat(ability.cooldownMillis())
                .as("null is Core's own way of saying 'no cooldown' — see ItemAbility.Builder.cooldown()")
                .isNull();
    }

    @Test
    @DisplayName("consumes the item on a successful reading")
    void consumesItself() {
        service.register();

        ItemAbility ability = abilities.byKey(ArenaItemService.PLUGIN + ":" + ArenaItemService.FIENDFINDER)
                .orElseThrow();
        assertThat(ability.consumesItem()).isTrue();
    }

    @Test
    @DisplayName("the reading itself still works")
    void readingStillWorks() {
        boolean happened = service.readTheFiendfinder(
                new ItemUse(UUID.randomUUID(), ArenaItemService.FIENDFINDER, ItemTrigger.RIGHT_CLICK, null));

        assertThat(happened).isTrue();
    }

    @Test
    @DisplayName("does nothing outside a running round")
    void doesNothingOutsideARunningRound() {
        phase = GamePhase.LOBBY;

        boolean happened = service.readTheFiendfinder(
                new ItemUse(UUID.randomUUID(), ArenaItemService.FIENDFINDER, ItemTrigger.RIGHT_CLICK, null));

        assertThat(happened).isFalse();
    }
}
