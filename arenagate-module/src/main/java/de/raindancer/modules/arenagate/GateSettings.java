package de.raindancer.modules.arenagate;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Range;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import org.bukkit.Material;

/** What an owner can decide about the way to the arena. */
@Settings(id = "arenagate", topics = {
        @Topic(path = "arenagate", title = "Arena gate", icon = Material.IRON_SWORD),
})
public record GateSettings(

        @In("arenagate") @Title("Arena server")
        @Describe("The proxy's name for the arena server, as in velocity.toml.")
        @Key("arena-server")
        String arenaServer,

        @In("arenagate") @Title("Stand still before going") @Range(min = 0, max = 30)
        @Describe("Seconds somebody stands still after /arena before they are sent. Moving or being hurt calls "
                + "it off, so nobody leaves a fight with it.")
        @Key("warmup-seconds")
        int warmupSeconds) {

    public static final GateSettings DEFAULTS = new GateSettings("arena", 3);

    public String arenaOrDefault() {
        return arenaServer == null || arenaServer.isBlank() ? "arena" : arenaServer.strip();
    }

    public int warmupClamped() {
        return Math.max(0, Math.min(30, warmupSeconds));
    }

    public GateSettings withArenaServer(String value) {
        return new GateSettings(value, warmupSeconds);
    }

    public GateSettings withWarmupSeconds(int value) {
        return new GateSettings(arenaServer, value);
    }
}
