package de.raindancer.modules.farmworld.rules;

import de.raindancer.modules.farmworld.util.PermissionNodes;

import java.util.function.Predicate;

/**
 * Who may enter which farm world, and who may change one.
 *
 * <h2>Why it takes a predicate rather than a player</h2>
 * Because this is the module's security decision, and a rule that needed a running server would be
 * one checked by hand on a test server. A predicate is {@code Player::hasPermission} in production and
 * three lines in a test.
 *
 * <h2>Greyed, not hidden — the opposite of the warps module, deliberately</h2>
 * A warp somebody may not use is not shown at all, because the name of a staff warp is the half of the
 * secret worth keeping. A farm world is not a secret: it is one of two or three named places the whole
 * server talks about, and somebody who cannot enter the donor one needs to be told that is what it is
 * rather than left wondering why other people mention a world that is not on their list. So
 * {@link #maySee} is deliberately <em>wider</em> than {@link #mayUse}, and the button carries the
 * reason.
 *
 * <p>Which means a screen here must not act on {@code maySee} alone — the click asks {@code mayUse}.
 * That is the ordinary shape for every other module in this repository and it is why the two methods
 * are named differently rather than one calling the other.
 *
 * <h2>An admin reaches everything</h2>
 * Somebody has to be able to go and look at a farm world that is misbehaving, and an admin who cannot
 * enter the one they are fixing fixes it by regenerating it — which throws away everybody else's work
 * to answer a question they could have answered by walking around.
 */
public final class FarmAccessRule implements IFarmWorldRule {

    /**
     * Whether this player may enter the farm world of this name.
     *
     * @param name          null is refused rather than opened: a farm world whose name could not be
     *                      read is not one to send somebody into on a guess
     * @param hasPermission how to ask; null is nobody
     */
    public boolean mayUse(String name, Predicate<String> hasPermission) {
        if (hasPermission == null || name == null || name.isBlank()) {
            return false;
        }
        if (hasPermission.test(PermissionNodes.MANAGE)) {
            return true;
        }
        // Both: the general node is what a server takes away from a group to switch farm worlds off
        // for them, and the per-world node is what closes one of several. Either alone would leave the
        // other unable to refuse anything.
        return hasPermission.test(PermissionNodes.USE)
                && hasPermission.test(PermissionNodes.forWorld(name));
    }

    /**
     * Whether this player is shown it at all.
     *
     * <p>Anybody who may use farm worlds in general sees every one of them, including the ones they
     * cannot enter — see the note on the class. Somebody with no farm world access at all is shown
     * none, because a list of things that are all refused is not a list, it is a wall.
     */
    public boolean maySee(String name, Predicate<String> hasPermission) {
        if (hasPermission == null || name == null || name.isBlank()) {
            return false;
        }
        return hasPermission.test(PermissionNodes.MANAGE)
                || hasPermission.test(PermissionNodes.USE);
    }

    /**
     * Why a farm world is refused, for the lore line on a greyed button.
     *
     * <p>Two reasons rather than one, because they are two different things for the player to do about
     * it: nothing, and ask whoever hands out the donor rank. A single "you may not go there" is the
     * sort of message that produces a ticket.
     *
     * @return the message key for the refusal, or null when there is nothing to refuse
     */
    public String refusalKey(String name, Predicate<String> hasPermission) {
        if (mayUse(name, hasPermission)) {
            return null;
        }
        if (hasPermission != null && hasPermission.test(PermissionNodes.USE)) {
            return "farmworlds.refused.this-one";
        }
        return "farmworlds.refused.at-all";
    }

    /**
     * Whether this player may make, change or regenerate a farm world.
     *
     * <p>Being allowed <em>into</em> a farm world is not being allowed to delete it, which is why this
     * asks for one node and nothing else. Everything behind it deletes worlds.
     */
    public boolean mayManage(Predicate<String> hasPermission) {
        return hasPermission != null && hasPermission.test(PermissionNodes.MANAGE);
    }

    @Override
    public String describe() {
        return "who may enter and change a farm world";
    }
}
