package de.raindancer.modules.ctf.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.modules.ctf.model.CaptureShow;
import de.raindancer.modules.ctf.model.Challenge;
import de.raindancer.modules.ctf.model.Role;
import de.raindancer.modules.ctf.model.Trail;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** What each player picked to look like, the class they last played, and how far their challenges are. */
public final class ProfileStore {

    /** A player's picks. */
    public record Profile(Trail trail, CaptureShow show, Role lastClass) {
        public static Profile defaults() {
            return new Profile(Trail.TEAM, CaptureShow.BURST, null);
        }
    }

    private final Database database;

    public ProfileStore(Database database) {
        this.database = database;
    }

    public Profile of(UUID player) {
        return database.read(connection -> {
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT trail, show, class FROM profile WHERE player = ?")) {
                query.setString(1, player.toString());
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next()) {
                        return null;
                    }
                    return new Profile(parse(Trail.class, rows.getString(1)).orElse(Trail.TEAM),
                            parse(CaptureShow.class, rows.getString(2)).orElse(CaptureShow.BURST),
                            Role.parse(rows.getString(3)).orElse(null));
                }
            }
        }).orElse(Profile.defaults());
    }

    public boolean save(UUID player, Profile profile) {
        return database.write(connection -> {
            try (PreparedStatement row = connection.prepareStatement(
                    "INSERT INTO profile (player, trail, show, class) VALUES (?,?,?,?) ON CONFLICT(player) DO UPDATE "
                            + "SET trail = excluded.trail, show = excluded.show, class = excluded.class")) {
                row.setString(1, player.toString());
                row.setString(2, profile.trail().name());
                row.setString(3, profile.show().name());
                row.setString(4, profile.lastClass() == null ? "" : profile.lastClass().name());
                row.executeUpdate();
            }
        });
    }

    /** How far each challenge of a day is for a player. */
    public Map<Challenge, Integer> challenges(UUID player, long day) {
        return database.read(connection -> {
            Map<Challenge, Integer> done = new EnumMap<>(Challenge.class);
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT challenge, done FROM challenge WHERE player = ? AND day = ?")) {
                query.setString(1, player.toString());
                query.setLong(2, day);
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        int count = rows.getInt(2);
                        parse(Challenge.class, rows.getString(1)).ifPresent(challenge -> done.put(challenge, count));
                    }
                }
            }
            return done;
        }).orElse(new EnumMap<>(Challenge.class));
    }

    public boolean setChallenge(UUID player, long day, Challenge challenge, int done) {
        return database.write(connection -> {
            try (PreparedStatement row = connection.prepareStatement(
                    "INSERT INTO challenge (player, day, challenge, done) VALUES (?,?,?,?) "
                            + "ON CONFLICT(player, day, challenge) DO UPDATE SET done = excluded.done")) {
                row.setString(1, player.toString());
                row.setLong(2, day);
                row.setString(3, challenge.name());
                row.setInt(4, done);
                row.executeUpdate();
            }
            // Yesterday's and older are of no more use to anybody.
            try (PreparedStatement old = connection.prepareStatement("DELETE FROM challenge WHERE day < ?")) {
                old.setLong(1, day - 7);
                old.executeUpdate();
            }
        });
    }

    private static <E extends Enum<E>> Optional<E> parse(Class<E> type, String name) {
        try {
            return name == null || name.isBlank() ? Optional.empty() : Optional.of(Enum.valueOf(type, name));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }
}
