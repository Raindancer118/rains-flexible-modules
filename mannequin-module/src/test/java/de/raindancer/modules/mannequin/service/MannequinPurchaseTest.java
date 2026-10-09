package de.raindancer.modules.mannequin.service;

import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.mannequin.MannequinSettings;
import de.raindancer.modules.mannequin.model.Mannequin;
import de.raindancer.modules.mannequin.model.MannequinKind;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MannequinPurchaseTest {

    private final UUID owner = UUID.randomUUID();
    private final Player player = mock(Player.class);
    private final Messages messages = mock(Messages.class);
    private final List<String> log = new ArrayList<>();
    private EconomyResult outcome = EconomyResult.done(Money.of(500), Money.ZERO);
    private boolean placementFails;

    private MannequinPurchase purchase(MannequinSettings settings) {
        return new MannequinPurchase(messages,
                (who, kind, where) -> {
                    log.add("place " + kind);
                    if (placementFails) {
                        throw new IllegalStateException("spawn failed");
                    }
                    return Mannequin.freshlyPlaced("m1", who, "world", 0, 64, 0);
                },
                id -> log.add("remove " + id),
                new MannequinPurchase.FeeBank() {
                    @Override
                    public Money quote(Money written) {
                        return written;
                    }

                    @Override
                    public EconomyResult charge(UUID payer, Money fee, String reason) {
                        if (!fee.isPositive()) {
                            return EconomyResult.done(Money.ZERO, Money.ZERO);
                        }
                        log.add("charge " + fee.minor());
                        return outcome;
                    }

                    @Override
                    public void refund(UUID to, Money taken, String reason) {
                        log.add("refund " + taken.minor());
                    }
                }, settings);
    }

    @BeforeEach
    void setUp() {
        when(player.getUniqueId()).thenReturn(owner);
        when(player.getLocation()).thenReturn(mock(Location.class));
    }

    private MannequinSettings priced(String price, double refundPercent) {
        return MannequinSettings.DEFAULTS.withPrice(price).withRefundPercentOnRemove(refundPercent);
    }

    @Test
    @DisplayName("free by default: placed without asking anybody for money")
    void freeByDefault() {
        Optional<Mannequin> made = purchase(MannequinSettings.DEFAULTS).buy(player, MannequinKind.PLAYER);

        assertThat(made).isPresent();
        assertThat(log).containsExactly("place PLAYER");
    }

    @Test
    @DisplayName("a price is charged before the mannequin is placed")
    void chargedBeforePlacing() {
        purchase(priced("5", 0)).buy(player, MannequinKind.ZOMBIE);

        assertThat(log).containsExactly("charge 500", "place ZOMBIE");
    }

    @Test
    @DisplayName("a player who cannot pay gets no mannequin")
    void refusedPlacesNothing() {
        outcome = EconomyResult.failed(EconomyResult.Outcome.NOT_ENOUGH, Money.of(500), Money.ZERO);

        Optional<Mannequin> made = purchase(priced("5", 0)).buy(player, MannequinKind.PLAYER);

        assertThat(made).isEmpty();
        assertThat(log).containsExactly("charge 500");
    }

    @Test
    @DisplayName("when placing fails the price is refunded")
    void failedPlacementRefunds() {
        placementFails = true;

        Optional<Mannequin> made = purchase(priced("5", 0)).buy(player, MannequinKind.PLAYER);

        assertThat(made).isEmpty();
        assertThat(log).containsExactly("charge 500", "place PLAYER", "refund 500");
    }

    @Test
    @DisplayName("removing refunds the configured percent of what was paid for it, then removes")
    void removalRefundsAShare() {
        MannequinPurchase purchase = purchase(priced("10", 50));
        when(player.getUniqueId()).thenReturn(owner);
        Mannequin mannequin = purchase.buy(player, MannequinKind.PLAYER).orElseThrow();
        log.clear();

        purchase.retire(mannequin);

        assertThat(log).containsExactly("refund 250", "remove m1");
    }

    @Test
    @DisplayName("a mannequin nobody paid for refunds nothing, whatever it would cost now — no money from nowhere")
    void nothingPaidNothingBack() {
        purchase(priced("10", 50)).retire(Mannequin.freshlyPlaced("m1", owner, "world", 0, 64, 0));

        assertThat(log).containsExactly("remove m1");
    }

    @Test
    @DisplayName("no refund percent means a plain removal")
    void noRefundByDefault() {
        purchase(priced("10", 0)).retire(Mannequin.freshlyPlaced("m1", owner, "world", 0, 64, 0));

        assertThat(log).containsExactly("remove m1");
    }
}
