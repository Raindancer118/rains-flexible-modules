package de.raindancer.modules.hungergames.listener;

import de.raindancer.core.social.team.Team;
import de.raindancer.core.social.team.TeamColour;
import de.raindancer.core.social.team.TeamId;
import de.raindancer.modules.hungergames.HungerGamesSettings;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.model.Winner;
import de.raindancer.modules.hungergames.store.GameEvents;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Everything the server is told about a round, in one place.
 *
 * <h2>Why this is not a Bukkit listener</h2>
 * The plugin this is ported from fired custom Bukkit events — {@code ParticipantKillEvent},
 * {@code ParticipantEliminatedEvent}, {@code WinnerDeclaredEvent} — and listened to them here. This module
 * has {@link GameEvents} instead: a plain interface the session calls, with no event bus in the middle.
 *
 * <p>That is a better fit for the same reason it is less flexible. An announcement that goes out has to go
 * out <em>whether or not</em> anything is listening, and the failure mode of an event bus is that a
 * subscriber which throws, or is never registered, produces a round that runs in silence with a clean log.
 * A direct call cannot be forgotten and cannot be silently dropped. Nothing outside this plugin ever
 * subscribed to those events, so the flexibility was paying for nothing.
 *
 * <p>It still lives in {@code listener/} and still implements {@link IHungerGamesListener}, because what it
 * is doing is listening — to the game's own events rather than to the server's.
 *
 * <h2>What is deliberately not here</h2>
 * The cannon and the elimination broadcast, which are the session's own. This adds the things that need
 * <em>more</em> than one event to know: who killed whom with a running count, and the "only N tributes
 * left" thresholds, which are the one announcement that must fire exactly once each.
 */
public final class AnnouncementListener implements IHungerGamesListener, GameEvents {

    /**
     * Saying one line to the whole server.
     *
     * <p>{@link AnnouncementService} takes a recipient and a set of styles per call, because most of what it
     * sends goes to one person. Everything here goes to everybody, in whichever styles the settings enable,
     * so the module wires this once rather than every call site repeating the audience and the style array.
     *
     * <p>An interface rather than a method on the service, because that service belongs to another part of
     * the module and this listener has no business widening it — and because it makes every branch below
     * testable by collecting strings.
     */
    @FunctionalInterface
    public interface Broadcast {
        void everybody(String key, Object... values);
    }

    private final Broadcast announcements;

    /** A tribute's name, for the wording. UUIDs are the identity; names are what people read. */
    private final Function<UUID, String> nameOf;

    /**
     * Which "only N left" thresholds have already been announced this round.
     *
     * <p>The whole reason this class holds any state. Thresholds are configured as counts — 10, 5, 3, 2 —
     * and eliminations do not arrive one at a time: a border closing on three people can take two of them
     * within a second, and a deathmatch teleport can take several. Without this, crossing from 6 to 2
     * announces 5, 3 <em>and</em> 2 in one breath, and crossing back up on a revive announces them all
     * again.
     */
    private final Set<Integer> alreadyAnnounced = new LinkedHashSet<>();

    private volatile HungerGamesSettings settings;

    public AnnouncementListener(Broadcast announcements, Function<UUID, String> nameOf,
                                HungerGamesSettings settings) {
        this.announcements = announcements;
        this.nameOf = nameOf;
        this.settings = settings;
    }

    public void settings(HungerGamesSettings settings) {
        this.settings = settings;
    }

    @Override
    public void forget(UUID player) {
        // Nothing is kept per player — the thresholds are per round, not per person. Emptied by
        // phaseChanged when a round starts, which is the moment they stop being true.
    }

    // ==================== the round ====================

    @Override
    public void phaseChanged(GamePhase oldPhase, GamePhase newPhase) {
        if (newPhase == GamePhase.RUNNING) {
            // A fresh round has its own thresholds. Not clearing here is how a second round on the same
            // server announces nothing at all — the counts were "already said" last time.
            alreadyAnnounced.clear();
        }
    }

    @Override
    public void kill(UUID killer, UUID victim, int killerTotalKills) {
        if (!settings.announceKillfeedEnabled()) {
            return;
        }
        announcements.everybody("hungergames.kill",
                "victim", nameOf.apply(victim),
                "killer", nameOf.apply(killer),
                "kills", String.valueOf(killerTotalKills));
    }

    @Override
    public void participantEliminated(UUID participant, UUID killer, int remainingAlive) {
        // With a killer the killfeed above has already said it, and with more detail. Announcing both is
        // two lines about one death, which is exactly what the vanilla death message was suppressed for.
        if (killer == null && settings.announceKillfeedEnabled()) {
            announcements.everybody("hungergames.elimination",
                    "victim", nameOf.apply(participant),
                    "alive", String.valueOf(remainingAlive));
        }
        if (settings.announceRemainingPlayersEnabled()) {
            announceThresholds(remainingAlive);
        }
    }

    /**
     * Announces every configured threshold this elimination has just crossed, once each.
     *
     * <p>Crossed rather than equalled, and that is the fix for a real gap: with thresholds of 10, 5, 3, 2 and
     * an elimination taking the count from 6 to 4, an equality test announces nothing at all — 5 was never
     * the count. The border taking two people at once is the ordinary case, not the corner.
     */
    public void announceThresholds(int remainingAlive) {
        if (remainingAlive < 1) {
            // Nothing. The winner announcement is what says the round is over, and "Only 0 tributes still
            // alive!" is not information — it was printed four times in a solo round, once per threshold.
            return;
        }
        boolean saidIt = false;
        for (int threshold : configuredThresholds()) {
            if (remainingAlive > threshold || !alreadyAnnounced.add(threshold)) {
                continue;
            }
            // Every crossing is remembered — that is the loop's real job, and forgetting the ones that went
            // unprinted would announce the same count again on the next elimination. But only one sentence
            // is printed, because the wording interpolates the count and not the threshold: four crossings
            // rendered four identical lines. See OneSentencePerEliminationTest.
            if (saidIt) {
                continue;
            }
            announcements.everybody("hungergames.remaining-players",
                    "alive", String.valueOf(remainingAlive),
                    "threshold", String.valueOf(threshold));
            saidIt = true;
        }
    }

    /**
     * The thresholds as numbers, largest first, with anything unparseable dropped.
     *
     * <p>Largest first so that a single elimination crossing two of them announces them in the order they
     * were passed rather than in the order the config happens to list them. Configured as strings because
     * the settings system stores a list that way and somebody edits it by hand; a stray word must cost that
     * one entry and not the whole announcement.
     *
     * <p>Public because the settings screen wants to show what the list actually resolves to — a page that
     * displays the raw strings shows somebody their typo as though it were a threshold.
     */
    public List<Integer> configuredThresholds() {
        return settings.announceRemainingPlayersThresholds().stream()
                .map(raw -> {
                    try {
                        return Integer.parseInt(raw.strip());
                    } catch (NumberFormatException notANumber) {
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .filter(threshold -> threshold > 0)
                .sorted((one, other) -> Integer.compare(other, one))
                .toList();
    }

    @Override
    public void winnerDeclared(Winner winner) {
        announcements.everybody("hungergames.winner", "winner", describe(winner));
    }

    /**
     * A winner as a name — a tribute's, a team's, or the honest answer when there is neither.
     *
     * <p>A winning team names its members, including the ones who did not survive to see it: they won too,
     * and a team victory that named only the survivor would be the same announcement as a solo one.
     */
    public String describe(Winner winner) {
        return switch (winner) {
            case Winner.Solo solo -> nameOf.apply(solo.uuid());
            case Winner.Team team -> team.teamId().value() + " ("
                    + String.join(", ", team.members().stream().map(nameOf).sorted().toList()) + ")";
            // Not "nobody" phrased as a failure. A round can end with everybody dead, and that is a result
            // rather than the absence of one — see Winner's own javadoc.
            case Winner.None ignored -> "nobody";
        };
    }

    // ==================== the ones this listener has nothing to say about ====================

    @Override
    public void participantRevived(UUID participant) {
        // Deliberately silent, and deliberately *not* un-announcing a threshold. A revive is an admin
        // correcting the plugin, and "only 3 left" followed by "only 4 left" reads as the plugin being
        // confused rather than as a correction — see alreadyAnnounced.
    }

    @Override
    public void whitelistChanged(UUID player, boolean added) {
        // Run-up bookkeeping. Announcing every /allow to the whole server during setup is forty lines
        // nobody wants, and the person who ran the command already knows.
    }

    @Override
    public void teamCreated(Team team) {
        // The team screens show this as it happens, to the people looking at them.
    }

    @Override
    public void teamDeleted(Team team) {
    }

    @Override
    public void teamColourChanged(Team team, TeamColour oldColour, TeamColour newColour) {
    }

    @Override
    public void teamMembershipChanged(UUID player, TeamId oldTeam, TeamId newTeam, MembershipCause cause) {
        // Not announced server-wide: during setup it is noise, and mid-round who is on whose team is
        // something the scoreboard already shows continuously.
    }

    @Override
    public String describe() {
        return "everything the server is told about a round";
    }

}
