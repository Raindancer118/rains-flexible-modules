package de.raindancer.modules.speedrun;

import de.raindancer.modules.speedrun.util.PermissionNodes;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.function.Consumer;

/**
 * Who may do what — once, for every way in. A {@code /speedrun} word, a hub button and a chat button
 * that do the same thing as a standalone command need exactly that command's node, and they ask at
 * the moment they are used, not only when the button was drawn: a page left open, or a chat button
 * sent half an hour ago, outlives a permission taken away in between.
 */
public enum SpeedrunAccess {

    /** Opening the menu, going to the lobby, reading stats, the leaderboard, history, the checks — what {@code /speedrun} itself needs. */
    VIEW(null),
    /** Where my own splits are shown. */
    HUD(null),
    /** Racing or not — {@code /speedrunspectate}. */
    SPECTATE(PermissionNodes.SPECTATE),
    /** Starting a run — the start block's own rule: anybody, unless {@code start-block-staff-only}. */
    START(PermissionNodes.START),
    /** {@code /speedrunresume}. */
    RESUME(PermissionNodes.ADMIN),
    /** {@code /speedruntime}. */
    SET_CLOCK(PermissionNodes.ADMIN),
    /** {@code /speedrunreset}. */
    RESET(PermissionNodes.ADMIN),
    /** The seed settings and "same seed again". */
    SEEDS(PermissionNodes.ADMIN),
    /** Every setting, through Core's pages. */
    SETTINGS(PermissionNodes.ADMIN),
    /** The setup assistant. */
    SETUP(PermissionNodes.ADMIN),
    /** A pre-flight check's one-click fix. */
    FIX(PermissionNodes.ADMIN),
    /** Switching somebody else between racing and not. */
    ROSTER_OTHERS(PermissionNodes.ADMIN),
    /** {@code /lemmemove <player>} and {@code /freezeagain <player>}. */
    RELEASE_OTHERS(PermissionNodes.LEMMEMOVE_OTHERS);

    private final String node;

    SpeedrunAccess(String node) {
        this.node = node;
    }

    /** The node, or {@code null} for nothing beyond {@code /speedrun}'s own. */
    public String node() {
        return node;
    }

    /** Whether the node is staff's. */
    public boolean staff() {
        return PermissionNodes.ADMIN.equals(node);
    }

    public boolean allows(SpeedrunLobby lobby, Player player) {
        if (player == null) {
            return false;
        }
        if (this == START) {
            return SpeedrunScreens.mayStart(lobby, player);
        }
        return node == null || player.hasPermission(node);
    }

    /** {@code handler}, run only if {@code player} is still allowed at the moment of the click. */
    public Consumer<InventoryClickEvent> guard(SpeedrunLobby lobby, Player player,
                                               Consumer<InventoryClickEvent> handler) {
        return click -> {
            if (allows(lobby, player)) {
                handler.accept(click);
            }
        };
    }
}
