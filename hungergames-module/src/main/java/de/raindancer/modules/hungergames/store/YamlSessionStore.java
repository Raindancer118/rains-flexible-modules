package de.raindancer.modules.hungergames.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.social.team.Team;
import de.raindancer.core.social.team.TeamColour;
import de.raindancer.core.social.team.TeamEmblem;
import de.raindancer.core.social.team.TeamId;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.model.ParticipantState;
import de.raindancer.modules.hungergames.model.SessionSnapshot;
import de.raindancer.modules.hungergames.model.Winner;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Persists one round to {@code session.yml}, so a server killed mid-round comes back to the same round
 * rather than a fresh one.
 *
 * <h2>Why the whole snapshot is written every time, rather than the bit that changed</h2>
 * {@code GameSession} calls {@link #save} after every single mutation — a join, an elimination, a team
 * rename — and each of those calls hands over the complete {@link SessionSnapshot}, not a diff. Writing it
 * whole means there is nothing here to get out of step: a store that tried to patch in just the changed
 * field would need to know the shape of every kind of change, and the one kind it did not anticipate is
 * the one that leaves {@code session.yml} describing a round that half happened. {@link YamlStore} makes
 * writing the whole thing cheap and safe — a fresh file, moved into place atomically — so there is no
 * reason to be cleverer than that.
 *
 * <h2>Why a corrupt file is quarantined rather than being written over on the next save</h2>
 * A round that survives a restart is this store's entire reason to exist, so the one failure worse than
 * losing the file is silently discarding evidence of what went wrong with it. On a corrupt read the broken
 * file is moved aside by {@link YamlStore#quarantine()} — kept, timestamped, out of the way — before this
 * store ever calls {@code save} again. Without that step the very next mutation would call {@code save},
 * which writes {@code session.yml} whether or not the old one could be read, and the corrupt copy — along
 * with whatever forensic value it had — would be gone.
 */
public final class YamlSessionStore implements SessionStore {

    private static final LogChannel log = Log.of("hungergames");

    private final YamlStore store;
    private final List<String> problems = new ArrayList<>();

    public YamlSessionStore(Path file) {
        this.store = new YamlStore(file);
    }

    /** What could not be read the last time {@link #load()} ran. Empty when it was clean. */
    public List<String> problems() {
        return List.copyOf(problems);
    }

    @Override
    public void save(SessionSnapshot snapshot) {
        store.write(yaml -> write(yaml, snapshot));
    }

    @Override
    public Optional<SessionSnapshot> load() {
        synchronized (problems) {
            problems.clear();
        }
        if (!store.exists()) {
            return Optional.empty();
        }
        YamlConfiguration yaml = store.read();
        if (!store.problems().isEmpty()) {
            carry();
            store.quarantine();
            return Optional.empty();
        }
        try {
            return Optional.of(read(yaml));
        } catch (RuntimeException broken) {
            note("session.yml could not be read (" + broken.getMessage() + ")");
            store.quarantine();
            return Optional.empty();
        }
    }

    @Override
    public void clear() {
        try {
            java.nio.file.Files.deleteIfExists(store.file());
        } catch (java.io.IOException failure) {
            log.warn("session.yml could not be deleted: {}", failure.getMessage());
        }
    }

    // ---------------------------------------------------------------------------- writing

    private static void write(YamlConfiguration yaml, SessionSnapshot snapshot) {
        yaml.set("phase", snapshot.phase().name());
        if (snapshot.runningSinceMillis() != null) {
            yaml.set("running-since", snapshot.runningSinceMillis());
        }

        List<Map<String, Object>> participants = new ArrayList<>();
        for (SessionSnapshot.ParticipantData p : snapshot.participants()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("uuid", p.uuid().toString());
            entry.put("name", p.name());
            entry.put("state", p.state().name());
            participants.add(entry);
        }
        yaml.set("participants", participants);

        for (Team team : snapshot.teams()) {
            String path = "teams." + team.id().value();
            yaml.set(path + ".name", team.name());
            yaml.set(path + ".colour", team.colour().name());
            yaml.set(path + ".emblem", team.emblem().key());
            // Written always, so a team that picked its own item keeps it across a restart — which is the
            // whole point of letting its members choose one.
            yaml.set(path + ".badge", team.badge().name());
            yaml.set(path + ".members", team.members().stream().map(UUID::toString).sorted().toList());
            team.captain().ifPresent(captain -> yaml.set(path + ".captain", captain.toString()));
        }

        Map<String, Object> kills = new LinkedHashMap<>();
        snapshot.kills().forEach((uuid, count) -> kills.put(uuid.toString(), count));
        yaml.set("kills", kills);

        switch (snapshot.winner()) {
            case Winner.Solo solo -> yaml.set("winner.solo", solo.uuid().toString());
            case Winner.Team team -> {
                yaml.set("winner.team", team.teamId().value());
                yaml.set("winner.members", team.members().stream().map(UUID::toString).sorted().toList());
            }
            case Winner.None ignored -> yaml.set("winner.none", true);
            case null -> {
                // undecided — nothing to write
            }
        }
    }

    // ---------------------------------------------------------------------------- reading

    private SessionSnapshot read(YamlConfiguration yaml) {
        GamePhase phase = readPhase(yaml);

        List<SessionSnapshot.ParticipantData> participants = new ArrayList<>();
        for (Map<?, ?> entry : yaml.getMapList("participants")) {
            try {
                participants.add(new SessionSnapshot.ParticipantData(
                        UUID.fromString(String.valueOf(entry.get("uuid"))),
                        String.valueOf(entry.get("name")),
                        ParticipantState.valueOf(String.valueOf(entry.get("state")))));
            } catch (RuntimeException broken) {
                note("a participant entry was skipped (" + broken.getMessage() + ")");
            }
        }

        List<Team> teams = new ArrayList<>();
        ConfigurationSection teamsSection = yaml.getConfigurationSection("teams");
        if (teamsSection != null) {
            for (String id : teamsSection.getKeys(false)) {
                readTeam(teamsSection, id).ifPresentOrElse(teams::add,
                        () -> note("team '" + id + "' was skipped (could not be read)"));
            }
        }

        Map<UUID, Integer> kills = new LinkedHashMap<>();
        ConfigurationSection killsSection = yaml.getConfigurationSection("kills");
        if (killsSection != null) {
            for (String uuid : killsSection.getKeys(false)) {
                try {
                    kills.put(UUID.fromString(uuid), killsSection.getInt(uuid));
                } catch (RuntimeException broken) {
                    note("a kill entry for '" + uuid + "' was skipped (" + broken.getMessage() + ")");
                }
            }
        }

        Winner winner = readWinner(yaml);
        Long runningSince = yaml.contains("running-since") ? yaml.getLong("running-since") : null;

        return new SessionSnapshot(phase, participants, teams, winner, kills, runningSince);
    }

    private GamePhase readPhase(YamlConfiguration yaml) {
        try {
            return GamePhase.valueOf(yaml.getString("phase", "NOT_INITIALIZED"));
        } catch (IllegalArgumentException broken) {
            note("the saved phase was unreadable — falling back to NOT_INITIALIZED");
            return GamePhase.NOT_INITIALIZED;
        }
    }

    private Optional<Team> readTeam(ConfigurationSection teamsSection, String id) {
        ConfigurationSection t = teamsSection.getConfigurationSection(id);
        if (t == null) {
            return Optional.empty();
        }
        try {
            TeamColour colour = TeamColour.valueOf(t.getString("colour", "WHITE"));
            Set<UUID> members = new LinkedHashSet<>();
            for (String raw : t.getStringList("members")) {
                members.add(UUID.fromString(raw));
            }
            // The emblem and the badge are new and are read defensively: a session written before they
            // existed has neither, and a round in progress across an upgrade must come back rather than
            // refusing. An unrecognised emblem falls back to NONE and an unrecognised material to the
            // emblem's own suggestion — see TeamEmblem.
            TeamEmblem emblem = TeamEmblem.named(t.getString("emblem", "")).orElse(TeamEmblem.NONE);
            Material badge = Material.matchMaterial(t.getString("badge", ""));

            Team team = new Team(new TeamId(id), t.getString("name", id), colour, emblem, badge, members,
                    Optional.empty());
            String captain = t.getString("captain");
            if (captain != null) {
                UUID captainId = UUID.fromString(captain);
                if (members.contains(captainId)) {
                    team = team.withCaptain(Optional.of(captainId));
                }
            }
            return Optional.of(team);
        } catch (RuntimeException broken) {
            note("team '" + id + "' was skipped (" + broken.getMessage() + ")");
            return Optional.empty();
        }
    }

    private Winner readWinner(YamlConfiguration yaml) {
        try {
            if (yaml.contains("winner.solo")) {
                return new Winner.Solo(UUID.fromString(yaml.getString("winner.solo")));
            }
            if (yaml.contains("winner.team")) {
                Set<UUID> members = new LinkedHashSet<>();
                for (String raw : yaml.getStringList("winner.members")) {
                    members.add(UUID.fromString(raw));
                }
                return new Winner.Team(new TeamId(yaml.getString("winner.team")), members);
            }
            if (yaml.getBoolean("winner.none", false)) {
                return new Winner.None();
            }
            return null;
        } catch (RuntimeException broken) {
            note("the saved winner was unreadable and was dropped (" + broken.getMessage() + ")");
            return null;
        }
    }

    // ---------------------------------------------------------------------------- problems

    private void carry() {
        List<String> fromFile = store.problems();
        synchronized (problems) {
            problems.addAll(fromFile);
        }
    }

    private void note(String problem) {
        synchronized (problems) {
            problems.add(problem);
        }
        log.warn("session.yml: {}", problem);
    }
}
