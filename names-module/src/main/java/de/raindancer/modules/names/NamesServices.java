package de.raindancer.modules.names;

import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.names.rules.CraftRule;
import de.raindancer.modules.names.service.CraftService;
import de.raindancer.modules.names.service.MobNameService;
import de.raindancer.modules.names.service.ReloadService;
import de.raindancer.modules.names.service.WashService;
import de.raindancer.modules.names.store.Palette;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

import java.util.function.Supplier;

/**
 * Everything the names module has built, in one place, so a listener, a screen or a command can be
 * handed what it needs.
 *
 * <h2>Why this is not the god object it replaces</h2>
 * The thing before it was the {@code JavaPlugin} subclass, passed to every listener as a
 * {@code ColouredNames} interface with an {@code options()} on it. That worked, and it also meant the
 * one interface grew a method every time anything needed anything.
 *
 * <p>The difference is that this is <em>data</em>. A record of collaborators, constructed once by the
 * module and handed over; a test builds one with fakes in the fields it cares about. Nothing here
 * reaches back into Bukkit and nothing here is static.
 *
 * @param palette  behind a supplier, not captured: {@code /namestyle reload} replaces it wholesale, and
 *                 a screen or a listener holding the old one would keep painting yesterday's colours
 * @param settings the same, for the same reason
 * @param screens  opening a screen, as an interface — so nothing here depends on the menus
 */
public record NamesServices(
        Plugin plugin,
        Server server,
        LogChannel log,
        Messages messages,
        Chat chat,
        Brand brand,

        Supplier<Palette> palette,
        Supplier<NamesSettings> settings,

        CraftService crafting,
        WashService washing,
        MobNameService mobNames,
        ReloadService reloading,

        INamesScreensOpener screens) {

    /** The settings as they are right now. */
    public NamesSettings config() {
        return settings.get();
    }

    /** The palette as it is right now. */
    public Palette colours() {
        return palette.get();
    }

    /**
     * What a crafting grid means, with the palette and the ceiling as they are now.
     *
     * <p>Asked through the crafting service rather than built here, so there is exactly one place that
     * decides what a grid means — the preview, the click that charges for it and any screen that
     * explains it all get the same answer.
     */
    public CraftRule craftRule() {
        return crafting.rule();
    }
}
