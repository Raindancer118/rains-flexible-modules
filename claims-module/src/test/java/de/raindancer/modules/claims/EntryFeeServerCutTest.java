package de.raindancer.modules.claims;

import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimShape;
import de.raindancer.modules.claims.model.CostType;
import de.raindancer.modules.claims.model.EntryFee;
import de.raindancer.modules.claims.service.ClaimService;
import de.raindancer.modules.claims.service.EntryFeeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EntryFeeServerCutTest {

    private static Claim claim() {
        return new Claim(UUID.randomUUID(), "home", UUID.randomUUID(), "world",
                ClaimShape.rectangle(0, 0, 15, 15, 0, 64), UUID.randomUUID());
    }

    private static EntryFee points(int amount) {
        EntryFee fee = new EntryFee();
        fee.type(CostType.XP_POINTS);
        fee.amount(amount);
        return fee;
    }

    @Test
    @DisplayName("with the cut at its default the owners get the whole fee, as before")
    void defaultKeepsEverything() {
        Claim claim = claim();
        EntryFeeService.bank(claim, points(40), ClaimSettings.DEFAULTS.entryFeeServerCutPercent());
        assertThat(claim.bank().experiencePoints()).isEqualTo(40);
    }

    @Test
    @DisplayName("a server cut destroys that share of the fee instead of banking it")
    void cutIsDestroyed() {
        Claim claim = claim();
        EntryFeeService.bank(claim, points(40), 25);
        assertThat(claim.bank().experiencePoints()).isEqualTo(30);
    }

    @Test
    @DisplayName("levels are cut on the points they are worth")
    void levelsAreCutAsPoints() {
        Claim claim = claim();
        EntryFee fee = new EntryFee();
        fee.type(CostType.XP_LEVELS);
        fee.amount(5);
        EntryFeeService.bank(claim, fee, 50);
        assertThat(claim.bank().experiencePoints()).isEqualTo(55 - 27);
    }

    @Test
    @DisplayName("deleting a claim refunds the settled amount at the delete rate, whole by default")
    void deleteRefundRate() {
        assertThat(ClaimService.deleteRefund(10, ClaimSettings.DEFAULTS.deleteRate())).isEqualTo(10);
        assertThat(ClaimService.deleteRefund(10, 0.5)).isEqualTo(5);
        assertThat(ClaimService.deleteRefund(9, 0.5)).isEqualTo(4);
        assertThat(ClaimService.deleteRefund(10, 3.0)).as("never more than was paid").isEqualTo(10);
        assertThat(ClaimService.deleteRefund(10, 0)).isZero();
    }
}
