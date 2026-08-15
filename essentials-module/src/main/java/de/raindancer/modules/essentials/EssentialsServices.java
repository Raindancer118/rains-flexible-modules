package de.raindancer.modules.essentials;

import de.raindancer.core.RainsCore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.essentials.service.AfkService;
import de.raindancer.modules.essentials.service.MessagingService;
import de.raindancer.modules.essentials.service.NicknameService;
import de.raindancer.modules.essentials.service.SpawnService;
import de.raindancer.modules.essentials.service.WelcomeService;
import de.raindancer.modules.essentials.store.EssentialsStore;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

import java.util.function.Supplier;

/**
 * Everything this module has built, in one place, so a listener or a command can be handed what it
 * needs.
 *
 * <p>Data, not a god object — a record of collaborators built once by the module and passed down. A
 * test builds one with fakes in the fields it cares about.
 *
 * @param settings behind a supplier rather than captured: a reload replaces the snapshot wholesale
 */
public record EssentialsServices(
        Plugin plugin,
        Server server,
        RainsCore core,
        LogChannel log,
        Messages messages,
        Chat chat,
        Brand brand,

        Supplier<EssentialsSettings> settings,

        EssentialsStore store,
        SpawnService spawn,
        AfkService afk,
        MessagingService messaging,
        NicknameService nicknames,
        WelcomeService welcome) {

    /** The settings as they are right now. */
    public EssentialsSettings config() {
        return settings.get();
    }
}
