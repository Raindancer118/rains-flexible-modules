package de.raindancer.modules.guilds.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.network.NetworkBus;
import de.raindancer.core.social.team.TeamId;
import de.raindancer.modules.guilds.model.Guild;
import de.raindancer.modules.guilds.model.GuildEvent;
import de.raindancer.modules.guilds.model.GuildOutcome;
import de.raindancer.modules.guilds.model.Invitation;
import de.raindancer.modules.guilds.model.Member;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Every guild of the network, in memory, and the only place one changes.
 *
 * <h2>The database decides</h2>
 * A guild is one set of rows in the network's database, whichever server changes it. A change is made from the
 * guild as the database has it just now: {@link #update} reads it, lets the caller's function make the new
 * version, and writes the difference ({@link GuildSql}) in one transaction. What has to hold across servers is
 * the database's to refuse — one guild per player, one owner per name, tag and id, a bank that stays above
 * zero — and a refusal that comes from another server's change simply makes the function run again on the
 * guild as it is now.
 *
 * <h2>Memory</h2>
 * Reading is from memory, and memory is read again from the database when another server announces a change
 * ({@link #connect}), after this server's own writes, and for everything when this server may have missed some.
 * A guild read in the middle of another read or of a write of this server is never allowed to replace a newer
 * one ({@link Freshness}), and experience counted here and not written yet is kept on top of what is read.
 *
 * <h2>Experience</h2>
 * It comes in a stream — every minute of play, every ore — so it is counted here at once and written with the
 * next {@link #flush} as {@code xp = xp + delta}: two servers earning for one guild at once both count, and the
 * level a guild reaches is told to {@link #onExperience} from the total the database says, once.
 */
public final class GuildBook {

    public static final String TOPIC = "guilds";
    public static final String INVITATIONS = "guild-invitations";
    private static final String QUESTIONS = "*questions";
    private static final int ATTEMPTS = 6;
    private static final long ANNOUNCE_EVERY_NANOS = 5_000_000_000L;

    /** Told after experience is written, with the guild's total before and after — from the database's own count. */
    @FunctionalInterface
    public interface ExperienceListener {
        void applied(String guild, long before, long after);
    }

    private static final class Xp {
        long guild;
        final Map<UUID, Long> members = new LinkedHashMap<>();

        void add(UUID member, long amount) {
            guild = saturated(guild, amount);
            if (member != null) {
                members.merge(member, amount, GuildBook::saturated);
            }
        }
    }

    private final GuildSql sql;
    private final Consumer<String> problems;
    private final List<String> found = new ArrayList<>();
    private final Object writing = new Object();
    private final Freshness<String> fresh = new Freshness<>();

    private final Map<String, Guild> guilds = new LinkedHashMap<>();
    private final Map<UUID, String> memberOf = new HashMap<>();
    private final Map<String, Invitation> invitations = new LinkedHashMap<>();
    private Map<String, Xp> pendingXp = new LinkedHashMap<>();
    private Map<String, Xp> inFlightXp = Map.of();
    private final List<GuildEvent> pendingEvents = new ArrayList<>();
    private Map<UUID, Long> pendingSeen = new LinkedHashMap<>();
    private final List<Consumer<String>> onDisband = new CopyOnWriteArrayList<>();
    private final List<ExperienceListener> onXp = new CopyOnWriteArrayList<>();
    private final Object flushing = new Object();
    private final Map<String, Long> announcedAt = new HashMap<>();
    private volatile boolean readable = true;
    private volatile NetworkBus bus = NetworkBus.NONE;

    public GuildBook(Database database, Consumer<String> problems) {
        this.problems = problems;
        this.sql = new GuildSql(database, this::problem);
    }

    private void problem(String text) {
        synchronized (found) {
            found.add(text);
        }
        problems.accept(text);
    }

    /** The key a guild's vault is kept under: its id <em>and</em> when it was founded, so a refounded guild opens nothing. */
    public static UUID vaultOwner(Guild guild) {
        return UUID.nameUUIDFromBytes(("guild-vault:" + guild.id().value() + ":" + guild.founded())
                .getBytes(StandardCharsets.UTF_8));
    }

    private static long saturated(long a, long b) {
        return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b;
    }

    // ------------------------------------------------------------------ loading and the network

    /**
     * Reads everything. Off the server's threads.
     *
     * @return what could not be read and was left out, for the module to log once
     */
    public List<String> load() {
        synchronized (found) {
            found.clear();
        }
        long started = fresh.begin();
        Optional<List<Guild>> loaded = sql.loadAll();
        readable = loaded.isPresent();
        if (loaded.isPresent()) {
            // Guilds from before names were a table claim theirs now; one somebody else holds is left to them.
            sql.claimWords(loaded.get());
            installAll(loaded.get(), started);
            refreshQuestions();
        }
        synchronized (found) {
            return List.copyOf(found);
        }
    }

    /** Reads everything again, for when this server may have missed what others announced. */
    public void refreshAll() {
        long started = fresh.begin();
        Optional<List<Guild>> loaded = sql.loadAll();
        if (loaded.isPresent()) {
            readable = true;
            installAll(loaded.get(), started);
            refreshQuestions();
        }
    }

    /** Hears other servers' changes: the guild named is read again. Close to stop. */
    public NetworkBus.Subscription connect(NetworkBus network) {
        this.bus = network == null ? NetworkBus.NONE : network;
        NetworkBus.Subscription changes = bus.subscribe(TOPIC, event -> refresh(event.key()));
        NetworkBus.Subscription asked = bus.subscribe(INVITATIONS, event -> refreshQuestions());
        NetworkBus.Subscription resync = bus.onResync(this::refreshAll);
        return () -> {
            changes.close();
            asked.close();
            resync.close();
        };
    }

    private void announce(String guild) {
        bus.publish(TOPIC, guild);
    }

    /**
     * Reads one guild from the database again: changed, added, or gone. Experience not written yet stays. An
     * unreadable database leaves what is known.
     */
    public boolean refresh(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            long started = fresh.begin();
            Optional<Optional<Guild>> read = sql.load(id);
            if (read.isEmpty()) {
                return false;
            }
            boolean gone = false;
            synchronized (this) {
                Freshness.Verdict verdict = fresh.check(id, started);
                if (verdict == Freshness.Verdict.STALE) {
                    return true;
                }
                if (verdict == Freshness.Verdict.RETRY) {
                    continue;
                }
                if (read.get().isPresent()) {
                    installLocked(read.get().get());
                } else {
                    gone = guilds.containsKey(id);
                    removeLocked(id);
                }
            }
            if (gone) {
                onDisband.forEach(each -> each.accept(id));
            }
            return true;
        }
        return true;
    }

    private void installAll(List<Guild> loaded, long started) {
        Set<String> existing = new HashSet<>();
        List<String> gone = new ArrayList<>();
        synchronized (this) {
            for (Guild guild : loaded) {
                existing.add(guild.id().value());
                if (fresh.check(guild.id().value(), started) == Freshness.Verdict.INSTALL) {
                    installLocked(guild);
                }
            }
            for (String id : List.copyOf(guilds.keySet())) {
                if (!existing.contains(id) && fresh.check(id, started) == Freshness.Verdict.INSTALL) {
                    removeLocked(id);
                    gone.add(id);
                }
            }
        }
        gone.forEach(id -> onDisband.forEach(each -> each.accept(id)));
    }

    /** Puts a guild read from the database in place, with the experience counted here on top. Under the lock. */
    private void installLocked(Guild read) {
        Guild guild = read;
        for (Map<String, Xp> counted : List.of(pendingXp, inFlightXp)) {
            Xp held = counted.get(read.id().value());
            if (held != null) {
                guild = withXp(guild, held);
            }
        }
        String id = guild.id().value();
        Guild before = guilds.put(id, guild);
        if (before != null) {
            before.members().keySet().forEach(player -> memberOf.remove(player, id));
        }
        guild.members().keySet().forEach(player -> memberOf.put(player, id));
    }

    private void removeLocked(String id) {
        Guild gone = guilds.remove(id);
        if (gone != null) {
            gone.members().keySet().forEach(player -> memberOf.remove(player, id));
        }
        pendingXp.remove(id);
    }

    private static Guild withXp(Guild guild, Xp counted) {
        Guild now = guild.withXp(saturated(guild.xp(), counted.guild));
        for (Map.Entry<UUID, Long> each : counted.members.entrySet()) {
            Optional<Member> member = now.member(each.getKey());
            if (member.isPresent()) {
                now = now.withMember(member.get().earning(each.getValue()));
            }
        }
        return now;
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
     * A guild by its name, its tag or its id, ignoring case — in that order, though the database keeps the three
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

    /** Whether {@code word} is already some other guild's name, tag or id, as far as this server knows. */
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

    public boolean tagTaken(String tag, TeamId except) {
        return claimed(tag, except);
    }

    // ------------------------------------------------------------------ changing

    private static GuildOutcome outcome(GuildSql.Outcome written) {
        return switch (written) {
            case DONE -> GuildOutcome.SUCCESS;
            case NAME_TAKEN -> GuildOutcome.NAME_TAKEN;
            case TAG_TAKEN -> GuildOutcome.TAG_TAKEN;
            case ALREADY_IN_GUILD -> GuildOutcome.ALREADY_IN_GUILD;
            case NO_SUCH_GUILD -> GuildOutcome.NO_SUCH_GUILD;
            case CONFLICT, NOT_EMPTY, FAILED -> GuildOutcome.NOT_SAVED;
        };
    }

    /**
     * Founds a guild. Whether its name, tag and id and its members are free is decided in the database, in the
     * one write that creates it: of two servers founding the same name at once, one gets it.
     */
    public GuildOutcome create(Guild guild) {
        if (!readable) {
            return GuildOutcome.NOT_SAVED;
        }
        synchronized (this) {
            if (memberOf.containsKey(guild.leader())) {
                return GuildOutcome.ALREADY_IN_GUILD;
            }
        }
        synchronized (writing) {
            GuildSql.Outcome written = sql.create(guild);
            if (written != GuildSql.Outcome.DONE) {
                if (written == GuildSql.Outcome.NAME_TAKEN || written == GuildSql.Outcome.TAG_TAKEN
                        || written == GuildSql.Outcome.ALREADY_IN_GUILD) {
                    refreshAll();
                }
                return outcome(written);
            }
            String id = guild.id().value();
            fresh.wrote(id);
            if (!refresh(id)) {
                synchronized (this) {
                    installLocked(guild);
                }
            }
            announce(id);
            return GuildOutcome.SUCCESS;
        }
    }

    /**
     * Changes one guild: {@code change} is given the guild as the database has it and answers the guild as it
     * should be — or the same one, to change nothing. It may run again when another server changed the guild in
     * between, so it must decide from what it is given.
     */
    public GuildOutcome update(TeamId id, UnaryOperator<Guild> change) {
        if (!readable) {
            return GuildOutcome.NOT_SAVED;
        }
        synchronized (writing) {
            for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                Optional<Optional<Guild>> read = sql.load(id.value());
                if (read.isEmpty()) {
                    return GuildOutcome.NOT_SAVED;
                }
                if (read.get().isEmpty()) {
                    refresh(id.value());
                    return GuildOutcome.NO_SUCH_GUILD;
                }
                Guild before = read.get().get();
                Guild after = change.apply(before);
                if (after == null || after == before) {
                    return GuildOutcome.SUCCESS;
                }
                if (!after.id().equals(before.id())) {
                    throw new IllegalArgumentException("a guild's id never changes");
                }
                GuildSql.Outcome written = sql.write(List.of(new GuildSql.Change(before, after)));
                if (written == GuildSql.Outcome.CONFLICT) {
                    continue;
                }
                if (written == GuildSql.Outcome.DONE) {
                    fresh.wrote(id.value());
                    refresh(id.value());
                    announce(id.value());
                } else if (written != GuildSql.Outcome.FAILED) {
                    refresh(id.value());
                }
                return outcome(written);
            }
            return GuildOutcome.NOT_SAVED;
        }
    }

    /** Changes two guilds as one — both sides of an alliance — or neither. */
    public GuildOutcome updateBoth(TeamId first, UnaryOperator<Guild> firstChange,
                                   TeamId second, UnaryOperator<Guild> secondChange) {
        if (!readable) {
            return GuildOutcome.NOT_SAVED;
        }
        synchronized (writing) {
            for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                Optional<Optional<Guild>> a = sql.load(first.value());
                Optional<Optional<Guild>> b = sql.load(second.value());
                if (a.isEmpty() || b.isEmpty()) {
                    return GuildOutcome.NOT_SAVED;
                }
                if (a.get().isEmpty() || b.get().isEmpty()) {
                    refresh(first.value());
                    refresh(second.value());
                    return GuildOutcome.NO_SUCH_GUILD;
                }
                Guild beforeA = a.get().get();
                Guild beforeB = b.get().get();
                Guild afterA = firstChange.apply(beforeA);
                Guild afterB = secondChange.apply(beforeB);
                if (!afterA.members().keySet().equals(beforeA.members().keySet())
                        || !afterB.members().keySet().equals(beforeB.members().keySet())
                        || !afterA.name().equals(beforeA.name()) || !afterB.name().equals(beforeB.name())) {
                    throw new IllegalArgumentException("updateBoth is for relations, not the roster");
                }
                GuildSql.Outcome written = sql.write(List.of(new GuildSql.Change(beforeA, afterA),
                        new GuildSql.Change(beforeB, afterB)));
                if (written == GuildSql.Outcome.CONFLICT) {
                    continue;
                }
                if (written == GuildSql.Outcome.DONE) {
                    fresh.wrote(first.value());
                    fresh.wrote(second.value());
                    refresh(first.value());
                    refresh(second.value());
                    announce(first.value());
                    announce(second.value());
                }
                return outcome(written);
            }
            return GuildOutcome.NOT_SAVED;
        }
    }

    /** Told the id of every guild that is disbanded, here or on another server — for what other stores keep about it. */
    public void onDisband(Consumer<String> whenGone) {
        onDisband.add(whenGone);
    }

    /**
     * Takes a guild away, with every relation to it and every open question about it — unless its bank or its
     * vault holds something, which is decided in the same write: money or items put in on another server since
     * cannot be lost with it.
     */
    public Optional<Guild> disband(TeamId id) {
        if (!readable) {
            return Optional.empty();
        }
        synchronized (writing) {
            Optional<Optional<Guild>> read = sql.load(id.value());
            if (read.isEmpty() || read.get().isEmpty()) {
                return Optional.empty();
            }
            Guild gone = read.get().get();
            GuildSql.Disbanded result = sql.disband(id.value(), vaultOwner(gone).toString());
            if (result.outcome() != GuildSql.Outcome.DONE) {
                return Optional.empty();
            }
            fresh.wrote(id.value());
            boolean known;
            synchronized (this) {
                known = guilds.containsKey(id.value());
                removeLocked(id.value());
                invitations.values().removeIf(each -> each.guild().equals(id.value()) || each.other().equals(id.value()));
            }
            if (known) {
                onDisband.forEach(each -> each.accept(id.value()));
            }
            announce(id.value());
            for (String touched : result.touched()) {
                fresh.wrote(touched);
                refresh(touched);
                announce(touched);
            }
            bus.publish(INVITATIONS, "");
            return Optional.of(gone);
        }
    }

    public synchronized void log(GuildEvent event) {
        pendingEvents.add(Objects.requireNonNull(event));
    }

    // ------------------------------------------------------------------ experience

    /** Told, after experience is written, what a guild had before and has now — once for each stretch of it. */
    public void onExperience(ExperienceListener listener) {
        onXp.add(listener);
    }

    /**
     * Counts experience for a guild, and for the member who earned it. In memory at once; written with the next
     * flush as an addition.
     */
    public synchronized void addXp(TeamId id, UUID member, long amount) {
        Guild guild = guilds.get(id.value());
        if (guild == null || amount <= 0 || !readable) {
            return;
        }
        UUID earner = member != null && guild.isMember(member) ? member : null;
        pendingXp.computeIfAbsent(id.value(), key -> new Xp()).add(earner, amount);
        Xp one = new Xp();
        one.add(earner, amount);
        guilds.put(id.value(), withXp(guild, one));
    }

    private boolean flushXp() {
        Map<String, Xp> writing;
        synchronized (this) {
            if (pendingXp.isEmpty()) {
                return true;
            }
            if (!readable) {
                return false;
            }
            writing = pendingXp;
            pendingXp = new LinkedHashMap<>();
            inFlightXp = writing;
        }
        Map<String, Long> guildDeltas = new LinkedHashMap<>();
        Map<UUID, Map.Entry<String, Long>> memberDeltas = new LinkedHashMap<>();
        writing.forEach((guild, counted) -> {
            guildDeltas.put(guild, counted.guild);
            counted.members.forEach((member, amount) -> memberDeltas.put(member, Map.entry(guild, amount)));
        });
        Optional<Map<String, Long>> after = sql.addXp(guildDeltas, memberDeltas);
        synchronized (this) {
            inFlightXp = Map.of();
            if (after.isEmpty()) {
                writing.forEach((guild, counted) -> {
                    Xp back = pendingXp.computeIfAbsent(guild, key -> new Xp());
                    back.guild = saturated(back.guild, counted.guild);
                    counted.members.forEach((member, amount) -> back.members.merge(member, amount, GuildBook::saturated));
                });
            }
        }
        if (after.isEmpty()) {
            return false;
        }
        long now = System.nanoTime();
        after.get().forEach((guild, total) -> {
            fresh.wrote(guild);
            // A stream of experience is not read back and announced every time: what this server counted is in
            // memory already, and the others catch up every few seconds.
            Long last = announcedAt.get(guild);
            if (last == null || now - last >= ANNOUNCE_EVERY_NANOS) {
                announcedAt.put(guild, now);
                refresh(guild);
                announce(guild);
            }
            onXp.forEach(each -> each.applied(guild, total - guildDeltas.get(guild), total));
        });
        return true;
    }

    /** Notes that a player is on now. In memory at once, written with the next flush. */
    public synchronized void seen(UUID player, long when) {
        pendingSeen.merge(player, when, Math::max);
        String id = memberOf.get(player);
        Guild guild = id == null ? null : guilds.get(id);
        Optional<Member> member = guild == null ? Optional.empty() : guild.member(player);
        if (member.isPresent()) {
            guilds.put(id, guild.withMember(member.get().withSeen(when)));
        }
    }

    // ------------------------------------------------------------------ open questions

    private static String key(Invitation invitation) {
        return invitation.kind() + "|" + invitation.guild() + "|" + invitation.subject() + "|" + invitation.other();
    }

    private void refreshQuestions() {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            long started = fresh.begin();
            Optional<List<Invitation>> read = sql.loadInvitations();
            if (read.isEmpty()) {
                return;
            }
            synchronized (this) {
                Freshness.Verdict verdict = fresh.check(QUESTIONS, started);
                if (verdict == Freshness.Verdict.STALE) {
                    return;
                }
                if (verdict == Freshness.Verdict.RETRY) {
                    continue;
                }
                invitations.clear();
                read.get().forEach(each -> invitations.put(key(each), each));
                return;
            }
        }
    }

    /**
     * Puts a question, replacing the same one asked before.
     *
     * @return false when it was already open and unexpired, or could not be written
     */
    public boolean ask(Invitation invitation, long now) {
        Invitation before;
        synchronized (this) {
            before = invitations.get(key(invitation));
        }
        if (!sql.ask(invitation)) {
            return false;
        }
        fresh.wrote(QUESTIONS);
        synchronized (this) {
            invitations.put(key(invitation), invitation);
        }
        bus.publish(INVITATIONS, "");
        return before == null || before.isExpiredAt(now);
    }

    /**
     * Takes an open question away, answering it: of the servers that answer it, one gets it. Empty when there
     * was none, or it had run out.
     */
    public Optional<Invitation> answer(Invitation.Kind kind, String guild, UUID subject, String other, long now) {
        Optional<Optional<Invitation>> taken = sql.take(kind, guild, subject, other == null ? "" : other);
        if (taken.isEmpty()) {
            return Optional.empty();
        }
        fresh.wrote(QUESTIONS);
        synchronized (this) {
            invitations.remove(kind + "|" + guild + "|" + subject + "|" + (other == null ? "" : other));
        }
        if (taken.get().isPresent()) {
            bus.publish(INVITATIONS, "");
        }
        return taken.get().filter(each -> !each.isExpiredAt(now));
    }

    public synchronized List<Invitation> questions(Invitation.Kind kind, long now) {
        return invitations.values().stream()
                .filter(each -> each.kind() == kind && !each.isExpiredAt(now))
                .sorted(Comparator.comparingLong(Invitation::expires))
                .toList();
    }

    /** Drops every question that has run out. @return those dropped */
    public List<Invitation> expire(long now) {
        List<Invitation> gone;
        synchronized (this) {
            gone = invitations.values().stream().filter(each -> each.isExpiredAt(now)).toList();
        }
        if (gone.isEmpty()) {
            return gone;
        }
        if (sql.expire(now)) {
            fresh.wrote(QUESTIONS);
            synchronized (this) {
                gone.forEach(each -> invitations.remove(key(each)));
            }
        }
        return gone;
    }

    /** Drops every question naming this player: they joined somewhere, or left the server's guilds for good. */
    public void forgetQuestionsOf(UUID player) {
        boolean any;
        synchronized (this) {
            any = invitations.values().stream()
                    .anyMatch(each -> each.kind() != Invitation.Kind.ALLIANCE && each.subject().equals(player));
        }
        if (any && sql.forgetQuestionsOf(player)) {
            fresh.wrote(QUESTIONS);
            synchronized (this) {
                invitations.values().removeIf(each -> each.kind() != Invitation.Kind.ALLIANCE && each.subject().equals(player));
            }
            bus.publish(INVITATIONS, "");
        }
    }

    // ------------------------------------------------------------------ writing

    public synchronized boolean hasUnwritten() {
        return !pendingXp.isEmpty() || !pendingEvents.isEmpty() || !pendingSeen.isEmpty();
    }

    /**
     * Writes the experience counted and the log lines made since the last write. Off the server's threads; never
     * two at once. Whatever could not be written stays for the next call.
     *
     * @return whether everything queued when it started is now written
     */
    public boolean flush() {
        synchronized (flushing) {
            boolean experience = flushXp();
            List<GuildEvent> events;
            synchronized (this) {
                events = List.copyOf(pendingEvents);
                pendingEvents.clear();
            }
            boolean logged = events.isEmpty() || sql.addEvents(events);
            if (!logged) {
                synchronized (this) {
                    pendingEvents.addAll(0, events);
                }
            }
            Map<UUID, Long> seen;
            synchronized (this) {
                seen = pendingSeen;
                pendingSeen = new LinkedHashMap<>();
            }
            boolean marked = seen.isEmpty() || sql.markSeen(seen);
            if (!marked) {
                synchronized (this) {
                    seen.forEach((player, when) -> pendingSeen.merge(player, when, Math::max));
                }
            }
            return experience && logged && marked;
        }
    }

    public List<GuildEvent> events(TeamId guild, Set<GuildEvent.Kind> kinds, int limit, int offset) {
        return sql.events(guild.value(), kinds, limit, offset);
    }

    /** The SQL behind the book, for what migrates or cleans up its tables. */
    GuildSql sql() {
        return sql;
    }
}
