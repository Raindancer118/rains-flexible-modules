package de.raindancer.modules.moderation;

import de.raindancer.modules.moderation.command.VitalsCommand;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.model.StaffRank;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code /heal}, {@code /feed}, {@code /hurt}, {@code /starve} — the four that change somebody's body.
 *
 * <h2>Why they are not {@code SelfToolCommand}</h2>
 * Flight, god and one-hit-kill are <em>states</em>: they are on or off, and asking twice changes
 * nothing. These four are <em>events</em> — healing somebody who is already whole is a no-op, but
 * hurting somebody twice hurts them twice. A toggle that fired an event would be a button whose second
 * click did the opposite of what its label said, and the label would be right about neither.
 *
 * <h2>Why the harmful two sit a rank higher</h2>
 * Restoring somebody is unremarkable and reversible. Taking half their health, from a menu, with no
 * record of it, is a way to kill somebody in a fight they were winning — so it is an admin's, and can
 * still be handed to one mod individually if a server wants that.
 */
class VitalsTest {

    @Test
    @DisplayName("all four exist and are wired to Core's PlayerAdmin verbs")
    void allFour() {
        assertThat(VitalsCommand.Vital.values())
                .extracting(VitalsCommand.Vital::word)
                .containsExactly("heal", "feed", "hurt", "starve");
    }

    @Test
    @DisplayName("mending is a mod's, harming is an admin's")
    void tiers() {
        assertThat(ModerationPermission.HEAL.fromTier()).isEqualTo(2);
        assertThat(ModerationPermission.FEED.fromTier()).isEqualTo(2);
        assertThat(ModerationPermission.HURT.fromTier()).isEqualTo(3);
        assertThat(ModerationPermission.STARVE.fromTier()).isEqualTo(3);
    }

    @Test
    @DisplayName("a mod is handed heal and feed, and not hurt or starve")
    void whatAModGets() {
        // The preset is derived from fromTier, so this is really asserting that derivation still holds
        // for a permission added after it was written — which is the whole reason the tier lives on the
        // enum constant.
        List<String> mod = new ArrayList<>(StaffRank.MOD.nodes());

        assertThat(mod).contains(ModerationPermission.HEAL.node(), ModerationPermission.FEED.node());
        assertThat(mod).doesNotContain(ModerationPermission.HURT.node(),
                ModerationPermission.STARVE.node());
    }

    @Test
    @DisplayName("an admin gets all four")
    void whatAnAdminGets() {
        assertThat(StaffRank.ADMIN.nodes()).contains(
                ModerationPermission.HEAL.node(), ModerationPermission.FEED.node(),
                ModerationPermission.HURT.node(), ModerationPermission.STARVE.node());
    }

    @Test
    @DisplayName("each one names the permission it needs, and no two share one")
    void distinctPermissions() {
        assertThat(VitalsCommand.Vital.values())
                .extracting(VitalsCommand.Vital::permission)
                .doesNotHaveDuplicates()
                .containsExactly(ModerationPermission.HEAL, ModerationPermission.FEED,
                        ModerationPermission.HURT, ModerationPermission.STARVE);
    }

    @Test
    @DisplayName("the two that harm say so, so a screen can colour them apart")
    void harmful() {
        assertThat(VitalsCommand.Vital.HEAL.harmful()).isFalse();
        assertThat(VitalsCommand.Vital.FEED.harmful()).isFalse();
        assertThat(VitalsCommand.Vital.HURT.harmful()).isTrue();
        assertThat(VitalsCommand.Vital.STARVE.harmful()).isTrue();
    }

    @Test
    @DisplayName("every one has a message key, and none of them is a YAML boolean")
    void messageKeys() {
        // `on`, `off`, `yes` and `no` are booleans in YAML 1.1, so a key called any of them is filed
        // under `true`/`false` and the lookup prints its own name back at the player. That has already
        // happened once here, with moderation.tool.instakill.on.
        for (VitalsCommand.Vital vital : VitalsCommand.Vital.values()) {
            assertThat(vital.word())
                    .as("%s would be read as a boolean by the config loader", vital.word())
                    .isNotIn("on", "off", "yes", "no", "true", "false", "y", "n");
            assertThat(vital.messageKey()).startsWith("moderation.vitals.").doesNotEndWith(".");
        }
    }
}
