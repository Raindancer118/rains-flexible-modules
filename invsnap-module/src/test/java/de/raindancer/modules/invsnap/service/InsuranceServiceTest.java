package de.raindancer.modules.invsnap.service;

import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.invsnap.InvSnapSettings;
import de.raindancer.modules.invsnap.rules.InsurancePremiumRule;
import de.raindancer.modules.invsnap.store.InsuredStore;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InsuranceServiceTest {

    @TempDir
    Path folder;

    private final UUID id = UUID.randomUUID();
    private final Player player = mock(Player.class);
    private final PlayerInventory inventory = mock(PlayerInventory.class);
    private final World world = mock(World.class);
    private final PlayerDeathEvent event = mock(PlayerDeathEvent.class);
    private final List<ItemStack> drops = new ArrayList<>(List.of(mock(ItemStack.class)));
    private final List<Money> charged = new ArrayList<>();

    private InsuredStore store;
    private EconomyResult outcome = EconomyResult.done(Money.of(2_000), Money.ZERO);

    @BeforeEach
    void setUp() {
        store = new InsuredStore(folder);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getWorld()).thenReturn(world);
        when(world.getName()).thenReturn("world");
        when(player.getInventory()).thenReturn(inventory);
        ItemStack diamonds = mock(ItemStack.class);
        when(diamonds.getAmount()).thenReturn(10);
        when(inventory.getContents()).thenReturn(new ItemStack[]{diamonds, null});
        when(event.getPlayer()).thenReturn(player);
        when(event.getDrops()).thenReturn(drops);
        when(event.isCancelled()).thenReturn(false);
        when(event.getKeepInventory()).thenReturn(false);
    }

    private InsuranceService service(InvSnapSettings settings) {
        return new InsuranceService(store, new InsurancePremiumRule(),
                (payer, amount, reason) -> {
                    charged.add(amount);
                    return outcome;
                },
                stack -> Optional.of(Money.of(1_000)), settings);
    }

    private InvSnapSettings on() {
        return InvSnapSettings.DEFAULTS.withInsuranceEnabled(true).withInsurancePrice(10, "0", "0");
    }

    @Test
    @DisplayName("an insured player who pays keeps the inventory and nothing is dropped")
    void paidKeepsAndClearsDrops() {
        store.set(id, true);

        InsuranceService.Verdict verdict = service(on()).onDeath(event);

        assertThat(verdict.kind()).isEqualTo(InsuranceService.Kind.KEPT);
        assertThat(charged).containsExactly(Money.of(1_000));
        org.mockito.Mockito.verify(event).setKeepInventory(true);
        assertThat(drops).isEmpty();
        org.mockito.Mockito.verify(event, org.mockito.Mockito.never()).setKeepLevel(true);
    }

    @Test
    @DisplayName("keep-xp keeps the level too and drops no experience")
    void keepXp() {
        store.set(id, true);

        service(on().withInsuranceKeepXp(true)).onDeath(event);

        org.mockito.Mockito.verify(event).setKeepLevel(true);
        org.mockito.Mockito.verify(event).setDroppedExp(0);
    }

    @Test
    @DisplayName("a player who cannot pay dies a normal death")
    void unpaidIsNormal() {
        store.set(id, true);
        outcome = EconomyResult.failed(EconomyResult.Outcome.NOT_ENOUGH, Money.of(1_000), Money.ZERO);

        InsuranceService.Verdict verdict = service(on()).onDeath(event);

        assertThat(verdict.kind()).isEqualTo(InsuranceService.Kind.UNPAID);
        assertThat(drops).hasSize(1);
        org.mockito.Mockito.verify(event, org.mockito.Mockito.never()).setKeepInventory(true);
    }

    @Test
    @DisplayName("nothing happens when insurance is off, the player is not insured, or keepInventory is on")
    void notApplicable() {
        store.set(id, true);
        assertThat(service(InvSnapSettings.DEFAULTS).onDeath(event).kind())
                .isEqualTo(InsuranceService.Kind.NOT_APPLICABLE);

        store.set(id, false);
        assertThat(service(on()).onDeath(event).kind()).isEqualTo(InsuranceService.Kind.NOT_APPLICABLE);

        store.set(id, true);
        when(event.getKeepInventory()).thenReturn(true);
        assertThat(service(on()).onDeath(event).kind()).isEqualTo(InsuranceService.Kind.NOT_APPLICABLE);

        assertThat(charged).isEmpty();
        assertThat(drops).hasSize(1);
    }

    @Test
    @DisplayName("a world outside insurance.worlds and a cancelled event are left alone")
    void worldsAndCancelled() {
        store.set(id, true);
        assertThat(service(on().withInsuranceWorlds(List.of("survival"))).onDeath(event).kind())
                .isEqualTo(InsuranceService.Kind.NOT_APPLICABLE);

        when(event.isCancelled()).thenReturn(true);
        assertThat(service(on()).onDeath(event).kind()).isEqualTo(InsuranceService.Kind.NOT_APPLICABLE);
        assertThat(charged).isEmpty();
    }

    @Test
    @DisplayName("the premium is the percent of every stack's value times its amount")
    void valuesEveryStack() {
        store.set(id, true);
        // 10 diamonds at 1000 = 10_000; 10% = 1_000, asserted by paidKeepsAndClearsDrops; flat adds on top
        service(on().withInsurancePrice(10, "5", "0")).onDeath(event);

        assertThat(charged).hasSize(1);
        assertThat(charged.getFirst().minor()).isGreaterThan(1_000);
    }
}
