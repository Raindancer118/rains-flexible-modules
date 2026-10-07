package de.raindancer.modules.veintoggle;

import de.raindancer.core.RainsCore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.ui.profile.PlayerSwitch;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.util.function.Supplier;

/** What this module built, handed to its command and listener. */
public record VeinToggleServices(
        Server server,
        RainsCore core,
        LogChannel log,
        Messages messages,
        Supplier<VeinToggleSettings> settings) {

    /** The switch, with the server's default as it is now — a changed default reaches everybody who never chose. */
    public PlayerSwitch veins() {
        return new PlayerSwitch("rainsveintoggle", "veins", settings.get().onByDefault());
    }

    public boolean wantsVeins(Player player) {
        return veins().isOn(player);
    }

    /** Whether Veinminer is on this server at all — without it the switch has nothing to switch. */
    public boolean veinminerInstalled() {
        return server.getPluginManager().getPlugin("Veinminer") != null;
    }
}
