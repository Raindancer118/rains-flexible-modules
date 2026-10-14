package de.raindancer.modules.voicebridge.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.sql.Schema;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.network.NetworkBus;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Which Discord account belongs to which player — one each way, for the whole network: a player linked on one server
 * is linked on every other. The database is the truth; this keeps a copy in memory for the audio and menu paths and
 * reads it again when another server says it changed something.
 */
public final class LinkStore {

    public static final String NAME = "voicebridge";
    public static final String TOPIC = "voicebridge-link";

    public static final Schema SCHEMA = Schema.of("""
            CREATE TABLE voice_link (
                discord INTEGER NOT NULL,
                player  TEXT    NOT NULL,
                PRIMARY KEY (discord)
            )""",
            "CREATE UNIQUE INDEX voice_link_player ON voice_link (player)");

    private static final LogChannel log = Log.of("voicebridge");

    private final Database database;
    private final NetworkBus bus;
    private final Map<Long, UUID> players = new HashMap<>();
    private final Map<UUID, Long> accounts = new HashMap<>();

    /**
     * @param legacy the 1.x {@code links.yml} (a store on it brings in the file itself), imported once; null for none
     */
    public LinkStore(Database database, NetworkBus bus, YamlStore legacy) {
        this.database = database;
        this.bus = bus == null ? NetworkBus.NONE : bus;
        if (legacy != null) {
            importLegacy(legacy);
        }
        refreshAll();
    }

    /** Hears the other servers' changes, and reads everything again when this server may have missed some. */
    public AutoCloseable connect(NetworkBus network) {
        NetworkBus.Subscription changes = network.subscribe(TOPIC, event -> refreshAll());
        NetworkBus.Subscription missed = network.onResync(this::refreshAll);
        return () -> {
            changes.close();
            missed.close();
        };
    }

    public synchronized Optional<UUID> playerOf(long discordUser) {
        return Optional.ofNullable(players.get(discordUser));
    }

    public synchronized Optional<Long> discordOf(UUID player) {
        return Optional.ofNullable(accounts.get(player));
    }

    /** @return whether it was stored; false leaves everything as it was */
    public boolean link(long discordUser, UUID player) {
        boolean written = database.write(connection -> {
            try (PreparedStatement clear = connection.prepareStatement(
                    "DELETE FROM voice_link WHERE discord = ? OR player = ?");
                 PreparedStatement insert = connection.prepareStatement(
                         "INSERT INTO voice_link (discord, player) VALUES (?, ?)")) {
                clear.setLong(1, discordUser);
                clear.setString(2, player.toString());
                clear.executeUpdate();
                insert.setLong(1, discordUser);
                insert.setString(2, player.toString());
                insert.executeUpdate();
            }
        });
        return written && changed();
    }

    /** @return whether the player was linked and now is not */
    public boolean unlink(UUID player) {
        int[] removed = new int[1];
        boolean written = database.write(connection -> {
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM voice_link WHERE player = ?")) {
                delete.setString(1, player.toString());
                removed[0] = delete.executeUpdate();
            }
        });
        return written && removed[0] > 0 && changed();
    }

    /** Reads this server's picture from the database; if it cannot be read, the old picture stays. */
    public void refreshAll() {
        Optional<Map<Long, UUID>> rows = database.read(connection -> {
            Map<Long, UUID> found = new HashMap<>();
            try (PreparedStatement select = connection.prepareStatement("SELECT discord, player FROM voice_link");
                 ResultSet result = select.executeQuery()) {
                while (result.next()) {
                    try {
                        found.put(result.getLong(1), UUID.fromString(result.getString(2)));
                    } catch (IllegalArgumentException broken) {
                        log.warn("A voice link with a broken player id was skipped.");
                    }
                }
            }
            return found;
        });
        rows.ifPresent(found -> {
            synchronized (this) {
                players.clear();
                accounts.clear();
                found.forEach((discord, player) -> {
                    players.put(discord, player);
                    accounts.put(player, discord);
                });
            }
        });
    }

    private boolean changed() {
        refreshAll();
        bus.publish(TOPIC, "all");
        return true;
    }

    /** Takes the 1.x links across once; what is already linked in the database wins. */
    private void importLegacy(YamlStore legacy) {
        var yaml = legacy.readIfReadable();
        if (yaml.isEmpty() || yaml.get().getBoolean("migrated-2.0.0", false)) {
            return;
        }
        var section = yaml.get().getConfigurationSection("links");
        int taken = 0;
        if (section != null) {
            for (String key : section.getKeys(false)) {
                try {
                    long discord = Long.parseLong(key);
                    String player = UUID.fromString(section.getString(key, "")).toString();
                    boolean written = database.write(connection -> {
                        try (PreparedStatement insert = connection.prepareStatement(
                                "INSERT INTO voice_link (discord, player) SELECT ?, ? WHERE NOT EXISTS "
                                        + "(SELECT 1 FROM voice_link WHERE discord = ? OR player = ?)")) {
                            insert.setLong(1, discord);
                            insert.setString(2, player);
                            insert.setLong(3, discord);
                            insert.setString(4, player);
                            insert.executeUpdate();
                        }
                    });
                    if (!written) {
                        log.warn("The voice links of 1.x could not be brought into the database; they are tried again next start.");
                        return;
                    }
                    taken++;
                } catch (IllegalArgumentException broken) {
                    // A hand-edited line that is not an id; the rest still counts.
                }
            }
        }
        if (legacy.update(document -> document.set("migrated-2.0.0", true)) && taken > 0) {
            log.info("{} voice links from 1.x are in the network's database now.", taken);
        }
    }
}
