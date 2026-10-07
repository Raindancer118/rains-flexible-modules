package de.raindancer.modules.playerutils;

import de.raindancer.core.RainsCore;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.playerutils.rules.ArgumentRule;
import de.raindancer.modules.playerutils.rules.FlightRule;
import de.raindancer.modules.playerutils.rules.NearRule;
import de.raindancer.modules.playerutils.rules.PingRule;
import de.raindancer.modules.playerutils.rules.SpeedRule;
import de.raindancer.modules.playerutils.rules.SudoRule;
import de.raindancer.modules.playerutils.rules.TargetRule;
import de.raindancer.modules.playerutils.rules.WipeRule;
import de.raindancer.modules.playerutils.service.ActionService;
import de.raindancer.modules.playerutils.service.FlightService;
import de.raindancer.modules.playerutils.service.InfoService;
import de.raindancer.modules.playerutils.service.SpectateService;
import de.raindancer.modules.playerutils.service.SudoService;
import de.raindancer.modules.playerutils.service.Targeting;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

import java.util.function.Supplier;

/** Everything the commands, menus and listeners need, handed over as one. */
public record PlayerUtilsServices(
        Plugin plugin,
        Server server,
        LogChannel log,
        Messages messages,
        Brand brand,
        RainsCore core,
        Supplier<PlayerUtilsSettings> settings,
        SettingsStore<PlayerUtilsSettings> store,
        ArgumentRule arguments,
        TargetRule targetRule,
        SudoRule sudoRule,
        PingRule pings,
        NearRule nearRule,
        FlightRule flightRule,
        WipeRule wipeRule,
        SpeedRule speedRule,
        Targeting targeting,
        ActionService actions,
        FlightService flight,
        SpectateService spectate,
        SudoService sudo,
        InfoService info,
        IPlayerUtilsScreensOpener screens) {

    public PlayerUtilsSettings config() {
        return settings.get();
    }
}
