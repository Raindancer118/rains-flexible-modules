package de.raindancer.modules.names.service;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.names.NamesSettings;
import de.raindancer.modules.names.store.Palette;
import de.raindancer.modules.names.store.PaletteFile;

/**
 * Re-reading {@code config.yml} — both halves of it, in the right order.
 *
 * <h2>Why both halves, always</h2>
 * The settings and the palette live in one file and are read by two different things: Core's
 * {@link SettingsStore} owns the keys it declared, and {@link PaletteFile} owns the three sections it
 * does not. A reload that did one and not the other would answer "reloaded" to somebody who had just
 * edited the other one — which is the sort of thing that gets reported as "the config does not work",
 * and is exactly what {@code /namestyle reload} is for.
 *
 * <p>The settings first, because a shorter ceiling has to be in force before the palette it applies to
 * is offered to anybody.
 */
public final class ReloadService implements INamesService {

    private final SettingsStore<NamesSettings> settings;
    private final PaletteFile palette;
    private final LogChannel log;

    public ReloadService(SettingsStore<NamesSettings> settings, PaletteFile palette, LogChannel log) {
        this.settings = settings;
        this.palette = palette;
        this.log = log;
    }

    @Override
    public void settings(NamesSettings fresh) {
        // Nothing to swap: this is what does the swapping. Declared and left empty on purpose — see
        // INamesService on why a service that "does not need it" is the one that later forgets it.
    }

    /**
     * Reads the file again.
     *
     * @return the palette now in force, whose size is what a reload reports back
     */
    public Palette reload() {
        settings.load();
        for (String problem : settings.problems()) {
            log.warn("{}", problem);
        }
        return palette.load(warning -> log.warn("{}", warning));
    }

    @Override
    public String describe() {
        return "re-reads the settings and the palette from config.yml";
    }
}
