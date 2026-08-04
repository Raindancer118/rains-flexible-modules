package de.raindancer.modules.moderation;

import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.rules.StaffRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That a tool may be pointed at yourself and a punishment may not.
 *
 * <h2>The defect this exists because of</h2>
 * {@code canAct} refused every action aimed at the actor, with one reason: somebody who bans themselves
 * cannot come back and lift it. That is right about bans and wrong about everything else — and the
 * commands that take an optional target hit it in a way that looked arbitrary from outside:
 *
 * <pre>
 *   /heal                  → works
 *   /heal Raindancer118    → "You cannot do that to yourself."
 * </pre>
 *
 * <p>Same request, same person, two answers, because omitting the name took a different branch from
 * naming yourself. It reads as the plugin being broken, and there is nothing in the message to suggest
 * dropping the argument.
 *
 * <p>So the question moves onto the permission, where it can be answered once for the command and the
 * screen alike, rather than being patched into each command that happens to notice.
 */
class AimingAtYourselfTest {

    private static final UUID ME = UUID.randomUUID();
    private static final UUID SOMEBODY = UUID.randomUUID();

    /** A rule that grants the actor everything, so only the self-question is under test. */
    private static StaffRule everything() {
        return new StaffRule((who, node) -> true, subject -> false);
    }

    @Test
    @DisplayName("a tool may be aimed at yourself")
    void tools() {
        StaffRule rule = everything();

        for (ModerationPermission tool : new ModerationPermission[] {
                ModerationPermission.FLY, ModerationPermission.GOD, ModerationPermission.INSTAKILL,
                ModerationPermission.HEAL, ModerationPermission.FEED,
                ModerationPermission.HURT, ModerationPermission.STARVE}) {
            assertThat(rule.canAct(ME, ME, tool).isAllowed())
                    .as("%s aimed at yourself", tool)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("a punishment may not — the reason the guard exists")
    void punishments() {
        // A moderator who bans themselves cannot come back and lift it. That has happened on a real
        // server and needed a database edit to undo.
        StaffRule rule = everything();

        for (ModerationPermission punishment : new ModerationPermission[] {
                ModerationPermission.BAN, ModerationPermission.TEMPBAN, ModerationPermission.KICK,
                ModerationPermission.MUTE, ModerationPermission.FREEZE, ModerationPermission.WARN}) {
            assertThat(rule.canAct(ME, ME, punishment).isRefused())
                    .as("%s aimed at yourself", punishment)
                    .isTrue();
            assertThat(rule.canAct(ME, ME, punishment).refusal())
                    .contains(StaffRule.NOT_YOURSELF);
        }
    }

    @Test
    @DisplayName("aiming a tool at somebody else still asks every other question")
    void atSomebodyElse() {
        // The self-exemption must not become a hole: pointing a tool at another player is a change to
        // their game they did not ask for, and immunity still applies.
        StaffRule immune = new StaffRule((who, node) -> true, SOMEBODY::equals);

        assertThat(immune.canAct(ME, SOMEBODY, ModerationPermission.HEAL).isRefused()).isTrue();
        assertThat(immune.canAct(ME, SOMEBODY, ModerationPermission.HEAL).refusal())
                .contains(StaffRule.THEY_ARE_IMMUNE);
    }

    @Test
    @DisplayName("without the permission, self-aiming is still refused")
    void withoutThePermission() {
        // The self-exemption is about *whom*, never about *whether*. Somebody with no heal permission
        // does not get one by pointing it at themselves.
        StaffRule nothing = new StaffRule((who, node) -> false, subject -> false);

        assertThat(nothing.canAct(ME, ME, ModerationPermission.HEAL).isRefused()).isTrue();
        assertThat(nothing.canAct(ME, ME, ModerationPermission.HEAL).refusal())
                .contains(StaffRule.NO_PERMISSION);
    }

    @Test
    @DisplayName("every permission answers the question, so a new one cannot be undecided")
    void everyPermissionDecides() {
        // The point of putting it on the enum: a permission added later is forced to say which it is,
        // at the moment it is written, rather than defaulting into whichever answer is more surprising.
        for (ModerationPermission permission : ModerationPermission.values()) {
            StaffRule rule = everything();
            boolean allowedAtSelf = rule.canAct(ME, ME, permission).isAllowed();

            assertThat(allowedAtSelf)
                    .as("%s must agree with its own aimableAtSelf()", permission)
                    .isEqualTo(permission.aimableAtSelf());
        }
    }
}
