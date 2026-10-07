package de.raindancer.modules.moderation;

import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.model.StaffRank;
import de.raindancer.modules.moderation.model.Vital;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * heal, feed, hurt, starve: the four buttons on the player page that change somebody's body. The typed
 * commands moved to Player Utils; these are events rather than states, and the harmful two sit a rank
 * higher than the restoring two.
 */
class VitalsTest {

    @Test
    @DisplayName("all four exist and are wired to Core's PlayerAdmin verbs")
    void allFour() {
        assertThat(Vital.values())
                .extracting(Vital::word)
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
        assertThat(Vital.values())
                .extracting(Vital::permission)
                .doesNotHaveDuplicates()
                .containsExactly(ModerationPermission.HEAL, ModerationPermission.FEED,
                        ModerationPermission.HURT, ModerationPermission.STARVE);
    }

    @Test
    @DisplayName("the two that harm say so, so a screen can colour them apart")
    void harmful() {
        assertThat(Vital.HEAL.harmful()).isFalse();
        assertThat(Vital.FEED.harmful()).isFalse();
        assertThat(Vital.HURT.harmful()).isTrue();
        assertThat(Vital.STARVE.harmful()).isTrue();
    }

    @Test
    @DisplayName("every one has a message key, and none of them is a YAML boolean")
    void messageKeys() {
        // `on`, `off`, `yes` and `no` are booleans in YAML 1.1, so a key called any of them is filed
        // under `true`/`false` and the lookup prints its own name back at the player. That has already
        // happened once here, with moderation.tool.instakill.on.
        for (Vital vital : Vital.values()) {
            assertThat(vital.word())
                    .as("%s would be read as a boolean by the config loader", vital.word())
                    .isNotIn("on", "off", "yes", "no", "true", "false", "y", "n");
            assertThat(vital.messageKey()).startsWith("moderation.vitals.").doesNotEndWith(".");
        }
    }
}
