package de.raindancer.modules.anticheat;

import de.raindancer.core.RainsCore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.anticheat.service.AlertService;
import de.raindancer.modules.anticheat.service.ClickService;
import de.raindancer.modules.anticheat.service.CombatService;
import de.raindancer.modules.anticheat.service.EspShield;
import de.raindancer.modules.anticheat.service.MovementEngine;
import de.raindancer.modules.anticheat.service.PacketTap;
import de.raindancer.modules.anticheat.service.PunishService;
import de.raindancer.modules.anticheat.service.Tracks;
import de.raindancer.modules.anticheat.service.ViolationService;
import de.raindancer.modules.anticheat.service.WorldService;
import de.raindancer.modules.anticheat.store.EvidenceLog;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

import java.util.function.Supplier;

/** What this module built, handed to its listeners, commands and screens. */
public record AntiCheatServices(
        Plugin plugin,
        Server server,
        RainsCore core,
        LogChannel log,
        Messages messages,
        Chat chat,
        Supplier<AntiCheatSettings> settings,
        de.raindancer.core.data.settings.SettingsStore<AntiCheatSettings> store,
        Tracks tracks,
        ViolationService violations,
        AlertService alerts,
        PunishService punishments,
        EvidenceLog evidence,
        de.raindancer.modules.anticheat.store.ReplayStore replays,
        MovementEngine engine,
        CombatService combat,
        WorldService world,
        ClickService clicks,
        PacketTap tap,
        EspShield shield) {

    public AntiCheatSettings config() {
        return settings.get();
    }
}
