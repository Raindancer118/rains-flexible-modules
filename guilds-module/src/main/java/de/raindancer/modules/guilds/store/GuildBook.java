package de.raindancer.modules.guilds.store;

import de.raindancer.core.social.team.Team;
import de.raindancer.core.social.team.TeamId;
import de.raindancer.core.social.team.TeamOutcome;
import de.raindancer.core.social.team.TeamPolicy;
import de.raindancer.core.social.team.Teams;
import de.raindancer.modules.guilds.model.Guild;
import de.raindancer.modules.guilds.model.GuildEvent;
import de.raindancer.modules.guilds.model.GuildOutcome;
import de.raindancer.modules.guilds.model.Invitation;
import de.raindancer.modules.guilds.model.Relation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Every guild, in memory, and the only place one changes.
 *
 * <h2>One lock, two registries</h2>
 * Core's {@link Teams} is the roster: it refuses a second guild of the same name and a player in two guilds at
 * once, and it is what anything outside this module that understands teams can read. The {@link Guild} records
 * are everything else. Both change under this object's lock, in one step, so they never disagree — a guild a
 * player is in according to one and not the other is the bug this arrangement exists to make impossible.
 *
 * <h2>Memory first, disk behind</h2>
 * A change is in memory at once and written with the next {@link #flush}, off the server thread. A flush that
 * fails leaves everything it tried to write queued for the next one, so a locked database file costs a delay and
 * never a guild. An unreadable database is never written over: until it can be read, every change is refused
 * with {@link GuildOutcome#NOT_SAVED} — saving then would replace everybody's guilds with nobody's.
 */
public final class GuildBook {

    private final GuildStorage storage;
    private final Teams roster = new Teams(TeamPolicy::clans, () -> false, player -> true);
    private final Map<String, Guild> guilds = new LinkedHashMap<>();
    private final Map<UUID, String> memberOf = new HashMap<>();
    private final Map<String, Invitation> invitations = new LinkedHashMap<>();

    private final Set<String> dirty = new LinkedHashSet<>();
    private final Set<String> deleted = new LinkedHashSet<>();
    private final List<GuildEvent> pendingEvents = new ArrayList<>();
    private boolean invitationsDirty;
    private final List<java.util.function.Consumer<String>> onDisband = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile boolean readable = true;
    private final Object flushing = new Object();

    public GuildBook(GuildStorage storage) {
        this.storage = storage;
    }

    /** @return the problems Core's roster had to correct while loading, for the module to log once */
    public synchronized List<String> load() {
        guilds.clear();
        memberOf.clear();
        invitations.clear();
        Optional<List<Guild>> loaded = storage.loadAll();
        readable = loaded.isPresent();
        List<Team> teams = new ArrayList<>();
        for (Guild guild : loaded.orElse(List.of())) {
            List<UUID> dropped = new ArrayList<>();
            Guild kept = guild;
            for (UUID player : guild.members().keySet()) {
                String already = memberOf.get(player);
                if (already != null && !player.equals(guild.leader())) {
                    dropped.add(player);
                } else {
                    memberOf.put(player, guild.id().value());
                }
            }
            for (UUID player : dropped) {
                kept = kept.withoutMember(player);
                dirty.add(guild.id().value());
            }
            guilds.put(kept.id().value(), kept);
            teams.add(team(kept));
        }
        roster.restore(teams);
        if (readable) {
            for (Invitation invitation : storage.loadInvitations()) {
                invitations.put(key(invitation), invitation);
            }
        }
        return roster.problems();
    }

    private static Team team(Guild guild) {
        return new Team(guild.id(), guild.name(), guild.colour(), guild.emblem(), null,
                guild.members().keySet(), Optional.of(guild.leader()));
    }

    public boolean readable() {
        return readable;
    }

    // ------------------------------------------------------------------ reading

    public synchronized Optional<Guild> guild(TeamId id) {
        return Optional.ofNullable(guilds.get(id.value()));
    }

    public synchronized Optional<Guild> guild(String id) {
        return Optional.ofNullable(guilds.get(id));
    }

    public synchronized Optional<Guild> guildOf(UUID player) {
        String id = player == null ? null : memberOf.get(player);
        return id == null ? Optional.empty() : Optional.ofNullable(guilds.get(id));
    }

    /**
     * A guild by its name, its tag or its id, ignoring case — in that order, though {@link #claimed} keeps the three
     * one namespace, so a typed word can only ever match one guild.
     */
    public synchronized Optional<Guild> find(String typed) {
        if (typed == null || typed.isBlank()) {
            return Optional.empty();
        }
        String wanted = typed.strip();
        String asId = wanted.toLowerCase(Locale.ROOT).replaceAll("\\s+", "-");
        return guilds.values().stream().filter(guild -> guild.name().equalsIgnoreCase(wanted)).findFirst()
                .or(() -> guilds.values().stream().filter(guild -> guild.tag().equalsIgnoreCase(wanted)).findFirst())
                .or(() -> guilds.values().stream().filter(guild -> guild.id().value().equals(asId)).findFirst());
    }

    /** Whether {@code word} is already some other guild's name, tag or id. */
    public synchronized boolean claimed(String word, TeamId except) {
        String wanted = word.strip();
        String asId = wanted.toLowerCase(Locale.ROOT).replaceAll("\\s+", "-");
        return guilds.values().stream().filter(guild -> !guild.id().equals(except)).anyMatch(guild ->
                guild.name().equalsIgnoreCase(wanted) || guild.tag().equalsIgnoreCase(wanted)
                        || guild.id().value().equals(asId));
    }

    public synchronized List<Guild> all() {
        return List.copyOf(guilds.values());
    }

    public synchronized int count() {
        return guilds.size();
    }

    /** What Core's roster says — the same answer this book gives, by construction. */
    public Optional<TeamId> rosterTeamOf(UUID player) {
        return roster.teamIdOf(player);
    }

    public synchronized boolean tagTaken(String tag, TeamId except) {
        return claimed(tag, except);
    }

    // ------------------------------------------------------------------ changing

    /** Founds a guild: the roster gets a team, the leader joins it and leads it. */
    public synchronized GuildOutcome create(Guild guild) {
        if (!readable) {
            return GuildOutcome.NOT_SAVED;
        }
        if (memberOf.containsKey(guild.leader())) {
            return GuildOutcome.ALREADY_IN_GUILD;
        }
        if (claimed(guild.name(), null)) {
            return GuildOutcome.NAME_TAKEN;
        }
        if (tagTaken(guild.tag(), null)) {
            return GuildOutcome.TAG_TAKEN;
        }
        Teams.CreationResult made = roster.create(guild.name(), guild.colour());
        if (made.status().isRefusal()) {
            return outcome(made.status());
        }
        TeamId id = made.team().orElseThrow().id();
        if (!id.equals(guild.id())) {
            roster.delete(id);
            throw new IllegalStateException("Core made the team " + id + " for the guild " + guild.id());
        }
        roster.setEmblem(id, guild.emblem());
        for (UUID player : guild.members().keySet()) {
            Teams.MembershipChange joined = roster.join(player, id);
            if (joined.status().isRefusal()) {
                roster.delete(id);
                guild.members().keySet().forEach(memberOf::remove);
                return outcome(joined.status());
            }
            memberOf.put(player, id.value());
        }
        roster.setCaptain(id, guild.leader());
        guilds.put(id.value(), guild);
        dirty.add(id.value());
        deleted.remove(id.value());
        return GuildOutcome.SUCCESS;
    }

    /**
     * Changes one guild. Whatever the change does to the name, the members, the leader or the look is carried
     * into Core's roster in the same step; a change the roster refuses changes nothing at all.
     */
    public synchronized GuildOutcome update(TeamId id, UnaryOperator<Guild> change) {
        if (!readable) {
            return GuildOutcome.NOT_SAVED;
        }
        Guild before = guilds.get(id.value());
        if (before == null) {
            return GuildOutcome.NO_SUCH_GUILD;
        }
        Guild after = change.apply(before);
        if (after == null || after == before) {
            return GuildOutcome.SUCCESS;
        }
        if (!after.id().equals(before.id())) {
            throw new IllegalArgumentException("a guild's id never changes");
        }
        if (!after.tag().equalsIgnoreCase(before.tag()) && tagTaken(after.tag(), id)) {
            return GuildOutcome.TAG_TAKEN;
        }
        if (!after.name().equalsIgnoreCase(before.name()) && claimed(after.name(), id)) {
            return GuildOutcome.NAME_TAKEN;
        }
        Set<UUID> joining = new LinkedHashSet<>(after.members().keySet());
        joining.removeAll(before.members().keySet());
        for (UUID player : joining) {
            String already = memberOf.get(player);
            if (already != null && !already.equals(id.value())) {
                return GuildOutcome.ALREADY_IN_GUILD;
            }
        }
        if (!after.name().equals(before.name())) {
            TeamOutcome renamed = roster.rename(id, after.name());
            if (renamed.isRefusal()) {
                return outcome(renamed);
            }
        }
        for (UUID player : joining) {
            roster.join(player, id);
            memberOf.put(player, id.value());
        }
        for (UUID player : before.members().keySet()) {
            if (!after.members().containsKey(player)) {
                roster.forceRemove(player);
                memberOf.remove(player);
            }
        }
        if (!after.leader().equals(before.leader()) || !joining.isEmpty()) {
            roster.setCaptain(id, after.leader());
        }
        if (after.colour() != before.colour()) {
            roster.setColour(id, after.colour());
        }
        if (after.emblem() != before.emblem()) {
            roster.setEmblem(id, after.emblem());
        }
        guilds.put(id.value(), after);
        dirty.add(id.value());
        return GuildOutcome.SUCCESS;
    }

    /** Changes two guilds as one — both sides of an alliance — or neither. */
    public synchronized GuildOutcome updateBoth(TeamId first, UnaryOperator<Guild> firstChange,
                                                TeamId second, UnaryOperator<Guild> secondChange) {
        if (!readable) {
            return GuildOutcome.NOT_SAVED;
        }
        Guild a = guilds.get(first.value());
        Guild b = guilds.get(second.value());
        if (a == null || b == null) {
            return GuildOutcome.NO_SUCH_GUILD;
        }
        Guild newA = firstChange.apply(a);
        Guild newB = secondChange.apply(b);
        if (!newA.members().keySet().equals(a.members().keySet()) || !newB.members().keySet().equals(b.members().keySet())
                || !newA.name().equals(a.name()) || !newB.name().equals(b.name())) {
            throw new IllegalArgumentException("updateBoth is for relations, not the roster");
        }
        guilds.put(first.value(), newA);
        guilds.put(second.value(), newB);
        dirty.add(first.value());
        dirty.add(second.value());
        return GuildOutcome.SUCCESS;
    }

    /** Told the id of every guild that is disbanded, after it is gone — for what other stores keep about it. */
    public void onDisband(java.util.function.Consumer<String> whenGone) {
        onDisband.add(whenGone);
    }

    /** Takes a guild away, with every relation to it and every open question about it. */
    public synchronized Optional<Guild> disband(TeamId id) {
        if (!readable) {
            return Optional.empty();
        }
        Guild gone = guilds.remove(id.value());
        if (gone == null) {
            return Optional.empty();
        }
        roster.delete(id);
        gone.members().keySet().forEach(memberOf::remove);
        for (Guild other : List.copyOf(guilds.values())) {
            if (other.relations().containsKey(id.value())) {
                guilds.put(other.id().value(), other.withRelation(id, Relation.NEUTRAL));
                dirty.add(other.id().value());
            }
        }
        if (invitations.values().removeIf(each -> each.guild().equals(id.value()) || each.other().equals(id.value()))) {
            invitationsDirty = true;
        }
        dirty.remove(id.value());
        deleted.add(id.value());
        onDisband.forEach(each -> each.accept(id.value()));
        return Optional.of(gone);
    }

    public synchronized void log(GuildEvent event) {
        pendingEvents.add(Objects.requireNonNull(event));
    }

    // ------------------------------------------------------------------ open questions

    private static String key(Invitation invitation) {
        return invitation.kind() + "|" + invitation.guild() + "|" + invitation.subject() + "|" + invitation.other();
    }

    /** Puts a question, replacing the same one asked before. @return false when it was already open and unexpired */
    public synchronized boolean ask(Invitation invitation, long now) {
        Invitation before = invitations.get(key(invitation));
        invitations.put(key(invitation), invitation);
        invitationsDirty = true;
        return before == null || before.isExpiredAt(now);
    }

    /** Takes an open question away, answering it. Empty when there was none, or it had run out. */
    public synchronized Optional<Invitation> answer(Invitation.Kind kind, String guild, UUID subject, String other, long now) {
        Invitation found = invitations.remove(kind + "|" + guild + "|" + subject + "|" + (other == null ? "" : other));
        if (found == null) {
            return Optional.empty();
        }
        invitationsDirty = true;
        return found.isExpiredAt(now) ? Optional.empty() : Optional.of(found);
    }

    public synchronized List<Invitation> questions(Invitation.Kind kind, long now) {
        return invitations.values().stream()
                .filter(each -> each.kind() == kind && !each.isExpiredAt(now))
                .sorted(Comparator.comparingLong(Invitation::expires))
                .toList();
    }

    /** Drops every question that has run out. @return those dropped */
    public synchronized List<Invitation> expire(long now) {
        List<Invitation> gone = invitations.values().stream().filter(each -> each.isExpiredAt(now)).toList();
        if (!gone.isEmpty()) {
            gone.forEach(each -> invitations.remove(key(each)));
            invitationsDirty = true;
        }
        return gone;
    }

    /** Drops every question naming this player: they joined somewhere, or left the server's guilds for good. */
    public synchronized void forgetQuestionsOf(UUID player) {
        if (invitations.values().removeIf(each -> each.kind() != Invitation.Kind.ALLIANCE && each.subject().equals(player))) {
            invitationsDirty = true;
        }
    }

    // ------------------------------------------------------------------ writing

    public synchronized boolean hasUnwritten() {
        return !dirty.isEmpty() || !deleted.isEmpty() || !pendingEvents.isEmpty() || invitationsDirty;
    }

    /**
     * Writes whatever changed. Off the server thread; never two at once. Whatever could not be written stays
     * queued for the next call.
     *
     * @return whether everything queued when it started is now on disk
     */
    public boolean flush() {
        synchronized (flushing) {
            List<Guild> changed;
            Set<String> gone;
            List<GuildEvent> events;
            List<Invitation> questions;
            synchronized (this) {
                if (!readable) {
                    return false;
                }
                if (!hasUnwritten()) {
                    return true;
                }
                changed = dirty.stream().map(guilds::get).filter(Objects::nonNull).toList();
                gone = Set.copyOf(deleted);
                events = List.copyOf(pendingEvents);
                questions = invitationsDirty ? List.copyOf(invitations.values()) : null;
                dirty.clear();
                deleted.clear();
                pendingEvents.clear();
                invitationsDirty = false;
            }
            boolean written = storage.write(changed, gone, questions, events);
            if (!written) {
                synchronized (this) {
                    for (Guild guild : changed) {
                        if (guilds.containsKey(guild.id().value())) {
                            dirty.add(guild.id().value());
                        }
                    }
                    for (String id : gone) {
                        if (!guilds.containsKey(id)) {
                            deleted.add(id);
                        }
                    }
                    pendingEvents.addAll(0, events);
                    invitationsDirty |= questions != null;
                }
            }
            return written;
        }
    }

    public List<GuildEvent> events(TeamId guild, Set<GuildEvent.Kind> kinds, int limit, int offset) {
        return storage.events(guild.value(), kinds, limit, offset);
    }

    private static GuildOutcome outcome(TeamOutcome refused) {
        return switch (refused) {
            case NAME_TAKEN -> GuildOutcome.NAME_TAKEN;
            case NO_SUCH_TEAM -> GuildOutcome.NO_SUCH_GUILD;
            case TEAM_FULL -> GuildOutcome.FULL;
            case MUST_LEAVE_FIRST, ALREADY_IN_THAT_TEAM -> GuildOutcome.ALREADY_IN_GUILD;
            case NOT_IN_A_TEAM -> GuildOutcome.NO_GUILD;
            case SUCCESS -> GuildOutcome.SUCCESS;
            default -> GuildOutcome.NOT_ALLOWED;
        };
    }
}
