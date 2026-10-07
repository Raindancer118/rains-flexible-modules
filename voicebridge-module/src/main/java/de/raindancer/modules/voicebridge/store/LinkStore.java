package de.raindancer.modules.voicebridge.store;

import de.raindancer.core.data.store.YamlStore;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Which Discord account belongs to which player — one each way. Kept in memory, written through to
 * {@code links.yml} on every change; the file is small and changes only when somebody links.
 */
public final class LinkStore {

    private final YamlStore file;
    private final Map<Long, UUID> players = new HashMap<>();
    private final Map<UUID, Long> accounts = new HashMap<>();

    public LinkStore(Path path) {
        file = new YamlStore(path);
        var yaml = file.read();
        var section = yaml.getConfigurationSection("links");
        if (section != null) {
            for (String discord : section.getKeys(false)) {
                try {
                    put(Long.parseLong(discord), UUID.fromString(section.getString(discord, "")));
                } catch (IllegalArgumentException broken) {
                    // A hand-edited line that is not an id; the rest of the file still counts.
                }
            }
        }
    }

    public synchronized Optional<UUID> playerOf(long discordUser) {
        return Optional.ofNullable(players.get(discordUser));
    }

    public synchronized Optional<Long> discordOf(UUID player) {
        return Optional.ofNullable(accounts.get(player));
    }

    public synchronized void link(long discordUser, UUID player) {
        UUID before = players.remove(discordUser);
        if (before != null) {
            accounts.remove(before);
        }
        Long otherAccount = accounts.remove(player);
        if (otherAccount != null) {
            players.remove(otherAccount);
        }
        put(discordUser, player);
        save();
    }

    public synchronized boolean unlink(UUID player) {
        Long discord = accounts.remove(player);
        if (discord == null) {
            return false;
        }
        players.remove(discord);
        save();
        return true;
    }

    private void put(long discordUser, UUID player) {
        players.put(discordUser, player);
        accounts.put(player, discordUser);
    }

    private void save() {
        Map<Long, UUID> snapshot = Map.copyOf(players);
        file.write(yaml -> snapshot.forEach((discord, player) ->
                yaml.set("links." + discord, player.toString())));
    }
}
