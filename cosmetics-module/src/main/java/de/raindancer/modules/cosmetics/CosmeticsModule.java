package de.raindancer.modules.cosmetics;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;
import de.raindancer.modules.cosmetics.listener.JoinListener;
import de.raindancer.modules.cosmetics.model.Catalogue;
import de.raindancer.modules.cosmetics.screen.CosmeticsMenu;
import de.raindancer.modules.cosmetics.screen.NameStyleMenu;
import de.raindancer.modules.cosmetics.screen.ParticleMenu;
import de.raindancer.modules.cosmetics.service.ClearService;
import de.raindancer.modules.cosmetics.service.NameStyleService;
import de.raindancer.modules.cosmetics.service.ParticleService;
import de.raindancer.modules.cosmetics.service.ReloadService;
import de.raindancer.modules.cosmetics.store.CatalogueFile;
import de.raindancer.modules.cosmetics.util.PermissionNodes;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Cosmetics players pick for themselves. So far: their own name, in a colour, a gradient or a preset,
 * with decorations.
 *
 * <p>Owns no player data. The style is Core's {@code Identities}, which chat-module, the tablist and
 * essentials' nicknames already draw names from — so removing this module keeps everybody's colours,
 * and they show without this module being asked.
 */
public final class CosmeticsModule implements FlexModule {

    private static final ModuleInfo INFO = ModuleInfo.of("cosmetics", "Cosmetics", "0.12.0")
            .describedAs("Paint your own name and wear a particle effect")
            .by("Raindancer118");

    private CosmeticsServices services;

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public void enable(ModuleContext context) {
        LogChannel log = context.log();
        Server server = context.plugin().getServer();
        SettingsStore<CosmeticsSettings> settings =
                context.settings(CosmeticsSettings.class, CosmeticsSettings.DEFAULTS);

        // Beside this class, not at the root: Core's own messages.yml is on the classpath too.
        context.core().messages().defineFrom(
                CosmeticsModule.class.getResourceAsStream("messages.yml"),
                context.chat().brand()::chatPrefix);
        // The same lines said plainly, for a server whose Message tone is SERIOUS.
        context.core().messages().seriousFrom(CosmeticsModule.class.getResourceAsStream("messages-serious.yml"));

        CatalogueFile catalogue = new CatalogueFile(context.dataFolder().resolve("config.yml"));
        Catalogue loaded = catalogue.load(warning -> log.warn("{}", warning));

        int registered = PermissionNodes.register(server, PermissionNodes.declared())
                + PermissionNodes.register(server, PermissionNodes.declaredFor(loaded.presets()));
        if (registered > 0) {
            log.info("{} permission(s) registered.", registered);
        }

        NameStyleService names = new NameStyleService(context.core().identities(), context.core().nametags(),
                context.core().messages(), catalogue::current, settings.current());
        names.applyNametagSetting();
        context.closeWith(() -> context.core().nametags().enabled(false));
        // Kept apart from config.yml: these are players' own, not settings an admin edits.
        de.raindancer.modules.cosmetics.store.WingReservations reservations =
                new de.raindancer.modules.cosmetics.store.WingReservations(
                        context.dataFolder().resolve("wing-reservations.yml"));
        reservations.load();
        ParticleService particles = new ParticleService(context.plugin(), server, context.core().vanish(),
                context.core().messages(), settings.current(), reservations);
        particles.start();
        context.closeWith(particles::stop);
        ReloadService reloading = new ReloadService(settings, catalogue, server, log);
        ClearService clearing = new ClearService(names, particles, context.core().messages(),
                context.core().audit(),
                (who, task) -> Scheduling.entity(context.plugin(), who, task), settings.current());
        // Handed to Core, so a player's choice follows them through every plugin's teleports.
        de.raindancer.modules.cosmetics.service.TeleportLookService teleports =
                new de.raindancer.modules.cosmetics.service.TeleportLookService(context.plugin(), context.core().messages(),
                        context.core().travelShow(), particles, settings.current());
        teleports.start();
        context.closeWith(teleports::stop);
        for (Player online : server.getOnlinePlayers()) {
            Scheduling.entity(context.plugin(), online, () -> teleports.load(online));
        }
        services = new CosmeticsServices(context.plugin(), server, log, context.core().messages(),
                context.chat().brand(), catalogue::current, settings::current, names, particles, reloading,
                clearing, teleports, context.core().vanish(), new LiveScreens());

        settings.onChange(fresh -> {
            names.settings(fresh);
            particles.settings(fresh);
            reloading.settings(fresh);
            clearing.settings(fresh);
            teleports.settings(fresh);
            // A sound taken off the list, or a particle blocked, stops being played for whoever had picked it.
            for (Player online : server.getOnlinePlayers()) {
                Scheduling.entity(context.plugin(), online, () -> teleports.load(online));
            }
        });

        context.listener(new JoinListener(services));
        CosmeticsCommands.ready(services);

        log.info("Cosmetics is up: {} palette colour(s), {} preset(s).",
                loaded.palette().size(), loaded.presets().size());
    }

    private final class LiveScreens implements ICosmeticsScreensOpener {

        @Override
        public void hub(Player viewer) {
            new CosmeticsMenu(services, viewer).open();
        }

        @Override
        public void nameStyle(Player viewer) {
            new NameStyleMenu(services, viewer, null).open();
        }

        @Override
        public void particles(Player viewer) {
            new ParticleMenu(services, viewer, null).open();
        }

        @Override
        public void teleports(Player viewer) {
            new de.raindancer.modules.cosmetics.screen.TeleportMenu(services, viewer, null).open();
        }
    }

    @Override
    public List<ModuleCommand> commands() {
        return CosmeticsCommands.declared();
    }

    @Override
    public void disable() {
        CosmeticsCommands.stopped();
        // Nothing to write: every style is in Core's identities, which Core saves.
    }
}
