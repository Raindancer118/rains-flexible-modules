package de.raindancer.modules.guilds.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.modules.guilds.model.Guild;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Brings the vaults this server kept as documents — one {@code <uuid>.yml} each — into the guilds database, where a
 * stack is a row. Once per server and vault: what is taken out afterwards is not brought in again. The lines are
 * moved as they are, so what this server cannot read comes along untouched.
 */
public final class VaultImport {

    private VaultImport() {
    }

    /**
     * @param documents the vault document for a vault key
     * @param server    this server's id: another server's document for the same guild is brought in as well
     * @return how many guilds' vaults were brought in now
     */
    public static int run(Database database, List<Guild> guilds, Function<UUID, YamlStore> documents, String server,
                          Consumer<String> problems) {
        int brought = 0;
        for (Guild guild : guilds) {
            UUID owner = GuildBook.vaultOwner(guild);
            String source = "vault:" + owner + ":" + server;
            Optional<Boolean> done = database.read(connection -> {
                try (PreparedStatement query = connection.prepareStatement("SELECT 1 FROM guild_migration WHERE source = ?")) {
                    query.setString(1, source);
                    try (var rows = query.executeQuery()) {
                        return rows.next();
                    }
                }
            });
            if (done.isEmpty() || done.get()) {
                continue;
            }
            YamlStore document = documents.apply(owner);
            if (!document.exists()) {
                continue;
            }
            Optional<YamlConfiguration> yaml = document.readIfReadable();
            if (yaml.isEmpty()) {
                problems.accept("the vault of " + guild.name() + " could not be read; it is tried again at the next start");
                continue;
            }
            List<String[]> lines = new ArrayList<>();
            yaml.get().getStringList("items").forEach(line -> lines.add(new String[]{"", line}));
            ConfigurationSection armour = yaml.get().getConfigurationSection("armour");
            if (armour != null) {
                for (String piece : armour.getKeys(false)) {
                    lines.add(new String[]{"armour:" + piece.toUpperCase(java.util.Locale.ROOT), armour.getString(piece, "")});
                }
            }
            yaml.get().getStringList("kept").forEach(line -> lines.add(new String[]{"kept", line}));
            if (lines.isEmpty()) {
                continue;
            }
            boolean written = database.write(connection -> {
                long pos = 0;
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO guild_vault (id, vault, slot, pos, item) VALUES (?, ?, ?, ?, ?)")) {
                    // After whatever the vault holds already, if another server's document came first.
                    try (PreparedStatement last = connection.prepareStatement("SELECT MAX(pos) FROM guild_vault WHERE vault = ?")) {
                        last.setString(1, owner.toString());
                        try (var rows = last.executeQuery()) {
                            pos = rows.next() ? rows.getLong(1) : 0;
                        }
                    }
                    for (String[] line : lines) {
                        insert.setString(1, UUID.randomUUID().toString());
                        insert.setString(2, owner.toString());
                        insert.setString(3, line[0]);
                        insert.setLong(4, ++pos);
                        insert.setString(5, line[1]);
                        insert.executeUpdate();
                    }
                }
                try (PreparedStatement mark = connection.prepareStatement(
                        "INSERT INTO guild_migration (source, at) VALUES (?, ?) ON CONFLICT (source) DO NOTHING")) {
                    mark.setString(1, source);
                    mark.setLong(2, System.currentTimeMillis());
                    mark.executeUpdate();
                }
            });
            if (written) {
                brought++;
            }
        }
        return brought;
    }
}
