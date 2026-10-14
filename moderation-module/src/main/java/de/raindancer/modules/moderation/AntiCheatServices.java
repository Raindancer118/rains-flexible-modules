package de.raindancer.modules.moderation;

import de.raindancer.core.RainsCore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.moderation.service.AlertService;
import de.raindancer.modules.moderation.service.EspShield;
import de.raindancer.modules.moderation.service.PacketTap;
import de.raindancer.modules.moderation.service.PunishService;
import de.raindancer.modules.moderation.service.Tracks;
import de.raindancer.modules.moderation.service.ViolationService;
import de.raindancer.modules.moderation.store.EvidenceLog;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

import java.util.function.Supplier;

/** What the anti-cheat part built, handed to its listeners, commands and screens. */
public record AntiCheatServices(
        Plugin plugin,
        Server server,
        RainsCore core,
        LogChannel log,
        Messages messages,
        Chat chat,
        Supplier<AntiCheatSettings> settings,
        de.raindancer.core.data.settings.SettingsStore<ModerationSettings> store,
        Tracks tracks,
        ViolationService violations,
        AlertService alerts,
        PunishService punishments,
        EvidenceLog evidence,
        PacketTap tap,
        EspShield shield) {

    public AntiCheatSettings config() {
        return settings.get();
    }
}
