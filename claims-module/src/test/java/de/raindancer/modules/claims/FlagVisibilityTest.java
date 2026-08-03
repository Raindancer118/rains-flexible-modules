package de.raindancer.modules.claims;

import de.raindancer.core.world.protection.FlagPolicy;
import de.raindancer.core.world.protection.FlagRules;
import de.raindancer.core.world.protection.LandFlag;
import de.raindancer.core.world.protection.LandFlagGroup;
import de.raindancer.core.world.protection.LandPolicies;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the flag screens are allowed to show.
 *
 * <p>The rule: <b>a flag the server does not enforce is not on the screen at all.</b> Not greyed, not listed as
 * unavailable — absent. Greying is for something that is somebody else's to change; a disabled flag is not a
 * choice anybody on this server has, and a toggle that produces no effect is worse than a missing one because
 * the owner believes they have set something.
 *
 * <p>The same rule one level up: a group whose flags are all disabled has no button, because a button that opens
 * an empty page is a button that wastes a click every time.
 *
 * <p>Checked against the same calls the screens make, which is what makes this a test of the behaviour rather
 * than a restatement of it — {@code FlagsMenu.entries()} and {@code FlagGroupsMenu.shownGroups()} are these two
 * loops.
 */
class FlagVisibilityTest {

    private final LandPolicies policies = LandPolicies.builtIn();
    private final FlagRules flags = new FlagRules(policies);

    /** What FlagsMenu lists for a group. */
    private List<LandFlag> shownIn(LandFlagGroup group) {
        List<LandFlag> shown = new ArrayList<>();
        for (LandFlag flag : group.flags()) {
            if (flags.isEnforced(flag)) {
                shown.add(flag);
            }
        }
        return shown;
    }

    /** What FlagGroupsMenu puts a button on. */
    private List<LandFlagGroup> shownGroups() {
        List<LandFlagGroup> shown = new ArrayList<>();
        for (LandFlagGroup group : LandFlagGroup.occupied()) {
            if (!shownIn(group).isEmpty()) {
                shown.add(group);
            }
        }
        return shown;
    }

    @Test
    @DisplayName("with nothing switched off, everything is offered")
    void everythingIsThereByDefault() {
        assertThat(shownGroups()).containsExactlyElementsOf(LandFlagGroup.occupied());
        assertThat(shownIn(LandFlagGroup.MOBS)).contains(LandFlag.MONSTER_SPAWNING);
    }

    @Test
    @DisplayName("a flag the server does not enforce is not on the screen")
    void aDisabledFlagDisappears() {
        policies.policy(LandFlag.MONSTER_SPAWNING, FlagPolicy.DISABLED);

        assertThat(shownIn(LandFlagGroup.MOBS))
                .as("a disabled flag is not a choice anybody has; showing it produces a click that does nothing")
                .doesNotContain(LandFlag.MONSTER_SPAWNING);
    }

    @Test
    @DisplayName("a forced flag is still shown, because it still applies")
    void aForcedFlagStays() {
        // Forced is not disabled. The rule is in force — the owner simply does not decide it — and hiding it
        // would leave them wondering why mobs still cannot spawn.
        policies.policy(LandFlag.MONSTER_SPAWNING, FlagPolicy.FORCED_OFF);

        assertThat(shownIn(LandFlagGroup.MOBS)).contains(LandFlag.MONSTER_SPAWNING);
        assertThat(flags.isEditableByOwner(LandFlag.MONSTER_SPAWNING))
                .as("shown, but not theirs to change — which is what greys the button")
                .isFalse();
    }

    @Test
    @DisplayName("a group with everything switched off has no button")
    void anEmptyGroupIsNotOffered() {
        for (LandFlag flag : LandFlagGroup.DEATH.flags()) {
            policies.policy(flag, FlagPolicy.DISABLED);
        }

        assertThat(shownGroups())
                .as("a button that opens an empty page wastes a click every time")
                .doesNotContain(LandFlagGroup.DEATH);
    }

    @Test
    @DisplayName("switching one flag off does not empty its group")
    void aGroupSurvivesLosingOne() {
        policies.policy(LandFlag.KEEP_INVENTORY, FlagPolicy.DISABLED);

        assertThat(shownGroups()).contains(LandFlagGroup.DEATH);
        assertThat(shownIn(LandFlagGroup.DEATH)).containsExactly(LandFlag.ITEM_DROPS);
    }

    @Test
    @DisplayName("with everything switched off there is nothing to open")
    void aServerCanTurnTheWholeThingOff() {
        for (LandFlag flag : LandFlag.values()) {
            policies.policy(flag, FlagPolicy.DISABLED);
        }

        assertThat(shownGroups()).isEmpty();
    }

    @Test
    @DisplayName("the new flags are grouped where somebody would look for them")
    void theNewFlagsAreFindable() {
        assertThat(LandFlagGroup.of(LandFlag.POTIONS)).isEqualTo(LandFlagGroup.COMBAT);
        assertThat(LandFlagGroup.of(LandFlag.RIPTIDE)).isEqualTo(LandFlagGroup.TRAVEL);
        assertThat(LandFlagGroup.of(LandFlag.KEEP_INVENTORY)).isEqualTo(LandFlagGroup.DEATH);
        assertThat(LandFlagGroup.of(LandFlag.ITEM_DROPS)).isEqualTo(LandFlagGroup.DEATH);
    }
}
