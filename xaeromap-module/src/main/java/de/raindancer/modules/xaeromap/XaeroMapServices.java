package de.raindancer.modules.xaeromap;

import de.raindancer.core.RainsCore;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.xaeromap.claims.ClaimSource;
import de.raindancer.modules.xaeromap.service.ClaimSyncService;
import de.raindancer.modules.xaeromap.service.WorldIdService;
import de.raindancer.modules.xaeromap.store.SyncIndexTable;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

import java.util.function.Supplier;

/**
 * Everything this module has built, in one place, so a listener or a command can be handed what it
 * needs. See {@code RtpServices} for why this is a record of collaborators rather than a god object.
 *
 * @param claims where claims come from, reached through a supplier because a claims plugin can be
 *               enabled after this module is — see {@code ClaimIntegration}
 */
public record XaeroMapServices(
        Plugin plugin,
        Server server,
        LogChannel log,
        Messages messages,
        Brand brand,
        RainsCore core,

        Supplier<XaeroMapSettings> settings,
        SettingsStore<XaeroMapSettings> store,

        Supplier<ClaimSource> claims,
        SyncIndexTable indices,
        WorldIdService worldIds,
        ClaimSyncService sync) {

    public XaeroMapSettings config() {
        return settings.get();
    }
}
