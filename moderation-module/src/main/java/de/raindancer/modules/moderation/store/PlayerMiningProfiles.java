package de.raindancer.modules.moderation.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.moderation.model.PlayerMiningProfile;
import org.bukkit.configuration.ConfigurationSection;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How suspicious every player's mining has looked, kept across restarts.
 *
 * <h2>Why this survives a restart when {@code MiningWindow} and {@code MiningTrail} do not</h2>
 * Those two exist to answer "right now, does this look like x-ray" as cheaply as possible, and both
 * are allowed to lose everything on a restart because losing them costs nothing — whatever a player
 * does next still gets judged fairly on a fresh window. This answers a different question, asked
 * about everybody at once rather than one player mid-report: "who on this server is worth actually
 * looking at", and that question has no honest answer for somebody the server forgot the moment they
 * last logged off. See {@link PlayerMiningProfile} for what is actually remembered — two learnt
 * averages and a count, nothing about where anybody has ever stood.
 */
public final class PlayerMiningProfiles {

    private static final LogChannel log = Log.of("moderation");

    private final Map<UUID, PlayerMiningProfile> profiles = new ConcurrentHashMap<>();
    private final YamlStore store;

    public PlayerMiningProfiles(Path dataFolder) {
        this.store = new YamlStore(dataFolder.resolve("xray-suspicion.yml"));
    }

    /** Where this is kept — for a diagnostic, and for a test that wants to break the file. */
    public Path file() {
        return store.file();
    }

    /** Theirs, made fresh the first time anybody asks about them. */
    public PlayerMiningProfile of(UUID who) {
        return profiles.computeIfAbsent(who, ignored -> new PlayerMiningProfile());
    }

    /** Everybody this has ever recorded anything about — the list a leaderboard is built from. */
    public Set<UUID> everybody() {
        return Set.copyOf(profiles.keySet());
    }

    /** Reads what is on disk, replacing what is held. */
    public void load() {
        profiles.clear();
        ConfigurationSection root = store.read().getConfigurationSection("players");
        if (root == null) {
            return;
        }
        List<String> unreadable = new ArrayList<>();
        for (String id : root.getKeys(false)) {
            UUID who;
            try {
                who = UUID.fromString(id);
            } catch (IllegalArgumentException notAnId) {
                unreadable.add(id);
                continue;
            }
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null) {
                continue;
            }
            profiles.put(who, new PlayerMiningProfile(section.getDouble("ore-ratio"),
                    section.getDouble("approach-directness"), section.getInt("observed-ore"),
                    section.getLong("last-updated")));
        }
        if (!unreadable.isEmpty()) {
            log.error("{} entry/entries in xray-suspicion.yml are not player ids and have been "
                            + "skipped: {}. Whoever they belonged to starts the score fresh this "
                            + "session, which is the safe direction for a number nobody is punished "
                            + "on directly.",
                    unreadable.size(), String.join(", ", unreadable));
        }
    }

    /** Writes the lot. @return whether it reached the disk */
    public boolean flush() {
        return store.write(yaml -> profiles.forEach((who, profile) -> {
            String prefix = "players." + who + ".";
            yaml.set(prefix + "ore-ratio", profile.oreRatio());
            yaml.set(prefix + "approach-directness", profile.approachDirectness());
            yaml.set(prefix + "observed-ore", profile.observedOre());
            yaml.set(prefix + "last-updated", profile.lastUpdatedEpochMillis());
        }));
    }
}
