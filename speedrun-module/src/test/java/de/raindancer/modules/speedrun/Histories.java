package de.raindancer.modules.speedrun;

import de.raindancer.core.data.runs.RunHistory;
import de.raindancer.core.data.store.YamlStore;

import java.nio.file.Path;

/** Speedrun histories for tests that are not about where runs are kept: Core's history, in memory. */
public final class Histories {

    private Histories() {
    }

    /** Runs in memory; standings in {@code folder}'s standings.yml, so they can be read back. */
    public static SpeedrunHistory inMemory(Path folder) {
        return new SpeedrunHistory(new RunHistory(null, SpeedrunHistory.GAME),
                new YamlStore(folder.resolve("standings.yml")), Runnable::run);
    }

    /**
     * Runs in Core's database, read back as a restart would; standings in {@code folder}'s
     * standings.yml. The caller opens and closes the database.
     */
    public static SpeedrunHistory onDisk(de.raindancer.core.data.sql.Database database, Path folder) {
        RunHistory runs = new RunHistory(database, SpeedrunHistory.GAME);
        runs.load();
        return new SpeedrunHistory(runs, new YamlStore(folder.resolve("standings.yml")), Runnable::run);
    }

    /** Runs and standings in memory only. */
    public static SpeedrunHistory inMemory() {
        return new SpeedrunHistory(new RunHistory(null, SpeedrunHistory.GAME), null, Runnable::run);
    }
}
