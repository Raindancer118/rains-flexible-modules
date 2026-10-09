package de.raindancer.modules.cosmetics;

import de.raindancer.core.RainsCore;
import de.raindancer.core.moderation.vanish.Vanish;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.cosmetics.model.Catalogue;
import de.raindancer.modules.cosmetics.service.ClearService;
import de.raindancer.modules.cosmetics.service.NameStyleService;
import de.raindancer.modules.cosmetics.service.ParticleService;
import de.raindancer.modules.cosmetics.service.ReloadService;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

import java.util.function.Supplier;

/**
 * Everything the module built, handed to listeners, screens and commands.
 *
 * @param catalogue behind a supplier: a reload replaces it, and a screen holding the old one would
 *                  offer yesterday's presets
 */
public record CosmeticsServices(
        Plugin plugin,
        Server server,
        LogChannel log,
        Messages messages,
        Brand brand,
        Supplier<Catalogue> catalogue,
        Supplier<CosmeticsSettings> settings,
        NameStyleService names,
        ParticleService particles,
        ReloadService reloading,
        ClearService clearing,
        de.raindancer.modules.cosmetics.service.TeleportLookService teleports,
        Vanish vanish,
        ICosmeticsScreensOpener screens,
        de.raindancer.modules.cosmetics.service.UnlockService unlocks,
        RainsCore core) {

    public Catalogue offered() {
        return catalogue.get();
    }

    /** Who sees the doors to Core's /settings pages. */
    public boolean mayOpenSettings(org.bukkit.entity.Player who) {
        return who.hasPermission("rainscore.settings");
    }

    public CosmeticsSettings config() {
        return settings.get();
    }
}
