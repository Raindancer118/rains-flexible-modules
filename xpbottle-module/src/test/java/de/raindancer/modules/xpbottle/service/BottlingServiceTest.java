package de.raindancer.modules.xpbottle.service;

import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.xpbottle.XpBottleSettings;
import de.raindancer.modules.xpbottle.model.Bottle;
import de.raindancer.modules.xpbottle.model.Bottling;
import de.raindancer.modules.xpbottle.rules.FillAmountRule;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The invariant this whole module exists to keep: <em>points in equal points out</em>.
 *
 * <p>{@code BottleForge} is mocked throughout — it is the class that makes a real
 * {@code ItemStack}, which lazily reaches for a running Paper server. What is tested here is the
 * bookkeeping around it, which is where an experience duplication bug would live: this test was
 * written before the code it tests, and the first version it was pointed at made the bottle and
 * never took the points out of the player.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BottlingServiceTest {

    @Mock
    private Messages messages;
    @Mock
    private Effects effects;
    @Mock
    private BottleForge forge;
    @Mock
    private Player player;
    @Mock
    private PlayerInventory inventory;
    @Mock
    private ItemStack held;
    @Mock
    private ItemStack made;

    private final UUID playerId = UUID.randomUUID();
    private BottlingService service;

    @BeforeEach
    void setUp() {
        service = new BottlingService(messages, effects, new FillAmountRule(), forge,
                XpBottleSettings.DEFAULTS);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.addItem(any(ItemStack.class)))
                .thenReturn(new java.util.HashMap<Integer, ItemStack>());
        when(forge.stackFor(any())).thenReturn(made);
        when(held.getAmount()).thenReturn(1);
    }

    @Test
    @DisplayName("what goes into the bottle comes out of the player, exactly")
    void fillingTakesWhatItGives() {
        when(player.calculateTotalExperiencePoints()).thenReturn(70);

        Bottling filling = service.fillPlain(player, held);

        assertThat(filling.moved()).isEqualTo(70);
        verify(player).setExperienceLevelAndProgress(0);
        verify(inventory).addItem(made);
    }

    @Test
    @DisplayName("a player with more than a bottle holds keeps the rest")
    void onlyTheCapacityIsTaken() {
        when(player.calculateTotalExperiencePoints()).thenReturn(1000);

        Bottling filling = service.fillPlain(player, held);

        assertThat(filling.moved()).isEqualTo(XpBottleSettings.DEFAULTS.plainCapacity());
        verify(player).setExperienceLevelAndProgress(
                1000 - XpBottleSettings.DEFAULTS.plainCapacity());
    }

    @Test
    @DisplayName("a player with nothing is told so, and no bottle is made")
    void nothingToTakeMakesNothing() {
        when(player.calculateTotalExperiencePoints()).thenReturn(0);

        Bottling filling = service.fillPlain(player, held);

        assertThat(filling.reason()).isEqualTo(Bottling.Reason.NOTHING_TO_TAKE);
        verify(inventory, never()).addItem(any(ItemStack.class));
        verify(player, never()).setExperienceLevelAndProgress(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("one bottle leaves the stack, not the whole stack")
    void oneBottleIsSpent() {
        when(player.calculateTotalExperiencePoints()).thenReturn(70);
        when(held.getAmount()).thenReturn(16);

        service.fillPlain(player, held);

        verify(held).setAmount(15);
    }

    @Test
    @DisplayName("the wait between bottlings refuses the second go and takes nothing for it")
    void theCooldownRefusesWithoutCharging() {
        service.settings(XpBottleSettings.DEFAULTS.withFillCooldownSeconds(60));
        when(player.calculateTotalExperiencePoints()).thenReturn(500);

        service.fillPlain(player, held);
        Bottling second = service.fillPlain(player, held);

        assertThat(second.happened()).isFalse();
        verify(player, org.mockito.Mockito.times(1))
                .setExperienceLevelAndProgress(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("a click that found nothing does not start the wait")
    void anEmptyClickCostsNothing() {
        service.settings(XpBottleSettings.DEFAULTS.withFillCooldownSeconds(60));
        when(player.calculateTotalExperiencePoints()).thenReturn(0);

        service.fillPlain(player, held);
        when(player.calculateTotalExperiencePoints()).thenReturn(90);
        Bottling second = service.fillPlain(player, held);

        assertThat(second.moved()).isEqualTo(90);
    }

    @Test
    @DisplayName("taking never takes more than the player actually has")
    void takingIsBoundedByWhatIsThere() {
        when(player.calculateTotalExperiencePoints()).thenReturn(30);

        assertThat(service.takeFrom(player, 500)).isEqualTo(30);
        verify(player).setExperienceLevelAndProgress(0);
    }

    @Test
    @DisplayName("pouring a plain bottle gives back exactly what was in it and leaves the glass")
    void pouringGivesBackExactly() {
        Bottle bottle = new Bottle(0, 137, 200);
        when(forge.emptyGlass()).thenReturn(made);

        int poured = service.pour(player, held, bottle);

        assertThat(poured).isEqualTo(137);
        verify(player).giveExp(137);
        verify(held).setAmount(0);
        verify(inventory).addItem(made);
    }

    @Test
    @DisplayName("pouring a siphon empties it in place rather than spending it")
    void pouringASiphonKeepsTheBottle() {
        Bottle siphon = new Bottle(2, 900, 1000);

        int poured = service.pour(player, held, siphon);

        assertThat(poured).isEqualTo(900);
        verify(player).giveExp(900);
        verify(forge).dress(held, siphon.poured());
        verify(held, never()).setAmount(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("pouring an empty bottle gives nothing and says so")
    void pouringNothingGivesNothing() {
        assertThat(service.pour(player, held, new Bottle(1, 0, 500))).isZero();
        verify(player, never()).giveExp(org.mockito.ArgumentMatchers.anyInt());
    }

    private final java.util.List<String> bank = new java.util.ArrayList<>();

    private BottlingService feeCharging(de.raindancer.core.social.economy.EconomyResult outcome) {
        return new BottlingService(messages, effects, new FillAmountRule(), forge,
                XpBottleSettings.DEFAULTS.withFee("2"),
                new BottlingService.FeeBank() {
                    @Override
                    public de.raindancer.core.social.economy.EconomyResult charge(UUID payer,
                            de.raindancer.core.social.economy.Money fee, String reason) {
                        bank.add("charge " + fee.minor());
                        return outcome;
                    }

                    @Override
                    public void refund(UUID payer, de.raindancer.core.social.economy.Money taken, String reason) {
                        bank.add("refund " + taken.minor());
                    }
                });
    }

    @Test
    @DisplayName("with a fee set, the fee is charged and the bottle is made")
    void feeIsCharged() {
        when(player.calculateTotalExperiencePoints()).thenReturn(70);

        Bottling filling = feeCharging(de.raindancer.core.social.economy.EconomyResult.done(
                de.raindancer.core.social.economy.Money.of(200), de.raindancer.core.social.economy.Money.ZERO))
                .fillPlain(player, held);

        assertThat(filling.moved()).isEqualTo(70);
        assertThat(bank).containsExactly("charge 200");
    }

    @Test
    @DisplayName("a player who cannot pay loses no experience and gets no bottle")
    void unpaidFeeTakesNothing() {
        when(player.calculateTotalExperiencePoints()).thenReturn(70);

        Bottling filling = feeCharging(de.raindancer.core.social.economy.EconomyResult.failed(
                de.raindancer.core.social.economy.EconomyResult.Outcome.NOT_ENOUGH,
                de.raindancer.core.social.economy.Money.of(200), de.raindancer.core.social.economy.Money.ZERO))
                .fillPlain(player, held);

        assertThat(filling.happened()).isFalse();
        verify(player, never()).setExperienceLevelAndProgress(org.mockito.ArgumentMatchers.anyInt());
        verify(inventory, never()).addItem(any(ItemStack.class));
    }

    @Test
    @DisplayName("no fee is asked when there is nothing to bottle")
    void noFeeForNothing() {
        when(player.calculateTotalExperiencePoints()).thenReturn(0);

        feeCharging(de.raindancer.core.social.economy.EconomyResult.done(
                de.raindancer.core.social.economy.Money.of(200), de.raindancer.core.social.economy.Money.ZERO))
                .fillPlain(player, held);

        assertThat(bank).isEmpty();
    }

    @Test
    @DisplayName("a fee taken for a bottle that could not be filled is given back")
    void feeRefundedWhenNothingCameOut() {
        // experience reads 70 when judged and 0 when taken: somebody spent it in between
        when(player.calculateTotalExperiencePoints()).thenReturn(70, 0);

        Bottling filling = feeCharging(de.raindancer.core.social.economy.EconomyResult.done(
                de.raindancer.core.social.economy.Money.of(200), de.raindancer.core.social.economy.Money.ZERO))
                .fillPlain(player, held);

        assertThat(filling.happened()).isFalse();
        assertThat(bank).containsExactly("charge 200", "refund 200");
    }
}
