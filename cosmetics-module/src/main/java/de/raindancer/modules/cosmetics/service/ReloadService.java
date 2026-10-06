package de.raindancer.modules.cosmetics.service;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.cosmetics.CosmeticsSettings;
import de.raindancer.modules.cosmetics.model.Catalogue;
import de.raindancer.modules.cosmetics.store.CatalogueFile;
import de.raindancer.modules.cosmetics.util.PermissionNodes;
import org.bukkit.Server;

/** {@code /cosmetics reload}: the settings, the palette and the presets, and nodes for new presets. */
public final class ReloadService implements ICosmeticsService {

    private final SettingsStore<CosmeticsSettings> settings;
    private final CatalogueFile catalogue;
    private final Server server;
    private final LogChannel log;

    public ReloadService(SettingsStore<CosmeticsSettings> settings, CatalogueFile catalogue, Server server,
                         LogChannel log) {
        this.settings = settings;
        this.catalogue = catalogue;
        this.server = server;
        this.log = log;
    }

    @Override
    public void settings(CosmeticsSettings fresh) {
        // Nothing to swap: this is what does the swapping.
    }

    public Catalogue reload() {
        settings.load();
        for (String problem : settings.problems()) {
            log.warn("{}", problem);
        }
        Catalogue loaded = catalogue.load(warning -> log.warn("{}", warning));
        PermissionNodes.register(server, PermissionNodes.declaredFor(loaded.presets()));
        return loaded;
    }

    @Override
    public String describe() {
        return "re-reads the settings, the palette and the presets";
    }
}
