package de.raindancer.modules.speedrun;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.modules.speedrun.util.PermissionNodes;
import org.bukkit.entity.Player;

/** The small things every speedrun screen needs from the lobby's toolkit. */
final class SpeedrunScreens {

    private SpeedrunScreens() {
    }

    /**
     * Whether {@code player} may start a run here — everybody, or only staff, depending on
     * {@code start-block-staff-only}.
     */
    static boolean mayStart(SpeedrunLobby lobby, Player player) {
        return !lobby.config().startBlockStaffOnly()
                || player.hasPermission(PermissionNodes.START);
    }

    /** The server's brand for a window frame — the toolkit's, or the default one for a bare lobby. */
    static Brand brandOf(SpeedrunLobby lobby) {
        return lobby.toolkit().map(SpeedrunToolkit::brand).orElseGet(() -> new Brand("Speedrun"));
    }
}
