package de.raindancer.modules.speedrun;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.modules.speedrun.util.PermissionNodes;
import net.kyori.adventure.text.minimessage.MiniMessage;
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

    /**
     * {@code value} as text inside a MiniMessage line: every tag in it shown, never obeyed. For
     * anything a player, a datapack or a typed answer could have chosen — a name, a death message,
     * a seed, an advancement's title.
     */
    static String text(Object value) {
        return MiniMessage.miniMessage().escapeTags(String.valueOf(value));
    }

    /** The server's brand for a window frame — the toolkit's, or the default one for a bare lobby. */
    static Brand brandOf(SpeedrunLobby lobby) {
        return lobby.toolkit().map(SpeedrunToolkit::brand).orElseGet(() -> new Brand("Speedrun"));
    }
}
