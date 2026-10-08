package de.raindancer.modules.moderation.rules;

import de.raindancer.core.platform.rule.Verdict;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Banhammer: a mace named "Banhammer", a kill with it by somebody allowed to swing it, and the
 * victim is banned for ever. A ban by accident is the one thing this must never produce, so most of
 * these are about what does <em>not</em> count.
 */
class BanhammerRuleTest {

    private final BanhammerRule rule = new BanhammerRule();

    /** Everything right; each test changes one thing. */
    private static BanhammerRule.Strike strike() {
        return new BanhammerRule.Strike(true, true, Material.MACE, "Banhammer", true, false, false);
    }

    @Test
    @DisplayName("a mace named Banhammer, swung by somebody allowed to, bans")
    void theBanhammerBans() {
        assertThat(rule.judge(strike()).isAllowed()).isTrue();
    }

    @Test
    @DisplayName("the name counts in any colour, any case, with stray spaces — the plain text is compared")
    void nameIsComparedAsPlainText() {
        assertThat(rule.isBanhammer(Material.MACE, " banHAMMER ")).isTrue();
        assertThat(rule.isBanhammer(Material.MACE, "Banhammer")).isTrue();
    }

    @Test
    @DisplayName("anything else is just a weapon")
    void onlyTheNamedMace() {
        assertThat(rule.isBanhammer(Material.NETHERITE_SWORD, "Banhammer")).isFalse();
        assertThat(rule.isBanhammer(Material.MACE, "Ban hammer")).isFalse();
        assertThat(rule.isBanhammer(Material.MACE, "Banhammer 2")).isFalse();
        assertThat(rule.isBanhammer(Material.MACE, null)).isFalse();
        assertThat(rule.isBanhammer(null, "Banhammer")).isFalse();
    }

    @Test
    @DisplayName("each missing condition is a different, silent no")
    void everyConditionMatters() {
        assertThat(rule.judge(new BanhammerRule.Strike(false, true, Material.MACE, "Banhammer", true, false,
                false)).reason()).as("switched off").isEqualTo(BanhammerRule.OFF);
        assertThat(rule.judge(new BanhammerRule.Strike(true, false, Material.MACE, "Banhammer", true, false,
                false)).reason()).as("not allowed to swing it").isEqualTo(BanhammerRule.NOT_ALLOWED);
        assertThat(rule.judge(new BanhammerRule.Strike(true, true, Material.MACE, "Hammer", true, false,
                false)).reason()).isEqualTo(BanhammerRule.NOT_THE_HAMMER);
        assertThat(rule.judge(new BanhammerRule.Strike(true, true, Material.MACE, "Banhammer", false, false,
                false)).reason())
                .as("an arrow or a fall while holding it — the hammer did not land the blow")
                .isEqualTo(BanhammerRule.NOT_THE_BLOW);
        assertThat(rule.judge(new BanhammerRule.Strike(true, true, Material.MACE, "Banhammer", true, true,
                false)).reason()).isEqualTo(BanhammerRule.SELF);
    }

    @Test
    @DisplayName("a protected account is not banned, and the swinger is told why")
    void protectedAccounts() {
        Verdict verdict = rule.judge(new BanhammerRule.Strike(true, true, Material.MACE, "Banhammer", true,
                false, true));
        assertThat(verdict.reason()).isEqualTo(BanhammerRule.IMMUNE);
        assertThat(rule.tellsTheSwinger(verdict)).isTrue();
        assertThat(rule.tellsTheSwinger(rule.judge(new BanhammerRule.Strike(true, true, Material.MACE,
                "Hammer", true, false, false)))).as("an ordinary kill says nothing").isFalse();
    }

    @Test
    @DisplayName("the reason is exactly the wording asked for, with the swinger's name")
    void theReason() {
        assertThat(BanhammerRule.reason("Raindancer118"))
                .isEqualTo("YOU'VE BEEN HIT WITH THE BANHAMMER BY Raindancer118");
    }

    @Test
    @DisplayName("somebody already banned is not banned again by the next swing")
    void alreadyBanned() {
        assertThat(rule.judge(new BanhammerRule.Strike(true, true, Material.MACE, "Banhammer", true, false,
                false, true)).reason()).isEqualTo(BanhammerRule.ALREADY_BANNED);
    }

    @Test
    @DisplayName("sneaking and right-clicking with the hammer puts it away, for somebody with a vault")
    void stashing() {
        assertThat(rule.stashes(true, true, Material.MACE, "Banhammer", true)).isTrue();
        assertThat(rule.stashes(false, true, Material.MACE, "Banhammer", true)).as("not sneaking").isFalse();
        assertThat(rule.stashes(true, false, Material.MACE, "Banhammer", true)).as("the off hand").isFalse();
        assertThat(rule.stashes(true, true, Material.MACE, "Hammer", true)).as("another mace").isFalse();
        assertThat(rule.stashes(true, true, Material.MACE, "Banhammer", false)).as("no vault").isFalse();
    }

    @Test
    @DisplayName("sneaking and right-clicking with an empty hand draws the hammer back out of the vault")
    void drawing() {
        assertThat(rule.draws(true, true, true, true, true)).isTrue();
        assertThat(rule.draws(false, true, true, true, true)).as("not sneaking").isFalse();
        assertThat(rule.draws(true, false, true, true, true)).as("the off hand").isFalse();
        assertThat(rule.draws(true, true, false, true, true)).as("something in hand").isFalse();
        assertThat(rule.draws(true, true, true, false, true)).as("no vault").isFalse();
        assertThat(rule.draws(true, true, true, true, false)).as("no hammer in the vault").isFalse();
    }

    @Test
    @DisplayName("only a real swing counts — thorns on the op's armour must never ban whoever hit them")
    void onlyASwing() {
        assertThat(BanhammerRule.isSwing("minecraft:player_attack")).isTrue();
        assertThat(BanhammerRule.isSwing("minecraft:mace_smash")).isTrue();
        assertThat(BanhammerRule.isSwing("minecraft:thorns")).isFalse();
        assertThat(BanhammerRule.isSwing("minecraft:arrow")).isFalse();
        assertThat(BanhammerRule.isSwing(null)).isFalse();
    }
}
