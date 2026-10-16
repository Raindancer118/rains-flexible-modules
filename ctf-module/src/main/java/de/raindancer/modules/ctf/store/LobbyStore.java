package de.raindancer.modules.ctf.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.modules.ctf.model.Point;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * {@code lobby.yml}: where the lobby is and what is in it — the spawn, the parkour's start, checkpoints and finish,
 * the throwing line. Each spot is the world's name and a {@link Point} line.
 */
public final class LobbyStore {

    /** A spot in some world. */
    public record Place(String world, Point point) {
        String line() {
            return world + " " + point.line();
        }

        static Optional<Place> parse(String line) {
            if (line == null) {
                return Optional.empty();
            }
            String trimmed = line.trim();
            int space = trimmed.indexOf(' ');
            if (space <= 0) {
                return Optional.empty();
            }
            return Point.parse(trimmed.substring(space + 1)).map(point -> new Place(trimmed.substring(0, space), point));
        }
    }

    /** Everything the lobby has, any of it possibly not set. */
    public record Lobby(Place spawn, Place parkourStart, List<Place> checkpoints, Place parkourFinish,
                        Place throwLine) {
        public Lobby {
            checkpoints = checkpoints == null ? List.of() : List.copyOf(checkpoints);
        }

        public static Lobby empty() {
            return new Lobby(null, null, List.of(), null, null);
        }

        public boolean hasParkour() {
            return parkourStart != null && parkourFinish != null;
        }

        public Lobby withSpawn(Place place) {
            return new Lobby(place, parkourStart, checkpoints, parkourFinish, throwLine);
        }

        public Lobby withParkourStart(Place place) {
            return new Lobby(spawn, place, checkpoints, parkourFinish, throwLine);
        }

        public Lobby withCheckpoint(Place place) {
            List<Place> more = new java.util.ArrayList<>(checkpoints);
            more.add(place);
            return new Lobby(spawn, parkourStart, more, parkourFinish, throwLine);
        }

        public Lobby withoutCheckpoints() {
            return new Lobby(spawn, parkourStart, List.of(), parkourFinish, throwLine);
        }

        public Lobby withParkourFinish(Place place) {
            return new Lobby(spawn, parkourStart, checkpoints, place, throwLine);
        }

        public Lobby withThrowLine(Place place) {
            return new Lobby(spawn, parkourStart, checkpoints, parkourFinish, place);
        }
    }

    private final YamlStore file;

    public LobbyStore(Path file) {
        this.file = new YamlStore(file);
    }

    public Lobby load() {
        var yaml = file.read();
        return new Lobby(Place.parse(yaml.getString("spawn")).orElse(null),
                Place.parse(yaml.getString("parkour.start")).orElse(null),
                yaml.getStringList("parkour.checkpoints").stream().map(Place::parse).flatMap(Optional::stream).toList(),
                Place.parse(yaml.getString("parkour.finish")).orElse(null),
                Place.parse(yaml.getString("throw-line")).orElse(null));
    }

    public boolean save(Lobby lobby) {
        return file.write(yaml -> {
            if (lobby.spawn() != null) {
                yaml.set("spawn", lobby.spawn().line());
            }
            if (lobby.parkourStart() != null) {
                yaml.set("parkour.start", lobby.parkourStart().line());
            }
            yaml.set("parkour.checkpoints", lobby.checkpoints().stream().map(Place::line).toList());
            if (lobby.parkourFinish() != null) {
                yaml.set("parkour.finish", lobby.parkourFinish().line());
            }
            if (lobby.throwLine() != null) {
                yaml.set("throw-line", lobby.throwLine().line());
            }
        });
    }
}
