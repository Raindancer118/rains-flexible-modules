package de.raindancer.modules.guilds.service;

import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.social.team.TeamColour;
import de.raindancer.core.social.team.TeamEmblem;
import de.raindancer.core.social.team.TeamId;
import de.raindancer.modules.guilds.GuildsSettings;
import de.raindancer.modules.guilds.model.Guild;
import de.raindancer.modules.guilds.model.GuildEvent;
import de.raindancer.modules.guilds.model.GuildOutcome;
import de.raindancer.modules.guilds.model.GuildPermission;
import de.raindancer.modules.guilds.model.Invitation;
import de.raindancer.modules.guilds.model.JoinMode;
import de.raindancer.modules.guilds.model.Member;
import de.raindancer.modules.guilds.model.Rank;
import de.raindancer.modules.guilds.rules.GuildNameRule;
import de.raindancer.modules.guilds.rules.LevelRule;
import de.raindancer.modules.guilds.rules.RankRule;
import de.raindancer.modules.guilds.rules.SuccessionRule;
import de.raindancer.modules.guilds.store.GuildBook;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;

/**
 * Founding, membership, ranks and a guild's own choices. Every change is decided by a rule, made in the book in
 * one step, logged, and told to whoever it concerns. Money for founding goes through Core's {@code Fees}.
 *
 * <p>Returns a {@link GuildOutcome} rather than telling the actor itself: the command and the screen that
 * asked each say it their own way (a chat line, a greyed button), from the same key.
 */
public final class GuildService implements IGuildsService {

    public static final String SOURCE_FOUNDING = "guilds.found";

    private final GuildBook book;
    private final GuildNotifier notify;
    private final LogChannel log;
    private final LongSupplier clock;
    private final Function<UUID, OptionalLong> lastSeen;
    private final RankRule ranks = new RankRule();
    private final SuccessionRule succession = new SuccessionRule();
    private final AtomicReference<GuildsSettings> settings = new AtomicReference<>();
    private volatile GuildNameRule names;
    private volatile LevelRule levels;
    private volatile Runnable onChange = () -> { };

    /** @param lastSeen when a player was last on the server, for succession; empty when unknown */
    public GuildService(GuildBook book, GuildNotifier notify, LogChannel log, LongSupplier clock,
                        Function<UUID, OptionalLong> lastSeen, GuildsSettings settings) {
        this.book = book;
        this.notify = notify;
        this.log = log;
        this.clock = clock;
        this.lastSeen = lastSeen;
        settings(settings);
    }

    @Override
    public void settings(GuildsSettings now) {
        settings.set(now);
        names = new GuildNameRule(now.shortestName(), now.longestName(), now.shortestTag(), now.longestTag(),
                now.bannedWords());
        levels = levelsFrom(now);
    }

    public static LevelRule levelsFrom(GuildsSettings now) {
        return new LevelRule(now.levelBase(), now.levelGrowth(), now.topLevel(), now.membersAtFirst(),
                now.membersPerLevel(), now.mostMembers(), now.alliesAtFirst(), now.levelsPerAlly(), now.homeFromLevel());
    }

    /** Told after anything that changes who is in which guild, so the name badges are drawn again. */
    public void onChange(Runnable whenChanged) {
        this.onChange = whenChanged == null ? () -> { } : whenChanged;
    }

    public GuildsSettings settings() {
        return settings.get();
    }

    public GuildBook book() {
        return book;
    }

    public RankRule ranks() {
        return ranks;
    }

    public LevelRule levels() {
        return levels;
    }

    public GuildNameRule names() {
        return names;
    }

    public int level(Guild guild) {
        return levels.levelOf(guild.xp());
    }

    public int memberCap(Guild guild) {
        return levels.memberCap(level(guild));
    }

    public Optional<Guild> guildOf(UUID player) {
        return book.guildOf(player);
    }

    private long now() {
        return clock.getAsLong();
    }

    private void log(Guild guild, GuildEvent.Kind kind, UUID actor, UUID target, long amount, String detail) {
        book.log(GuildEvent.of(guild.id().value(), now(), kind, actor, target, amount, detail));
    }

    /** The guild the actor is in, or the refusal saying they are in none. */
    private Optional<Guild> own(UUID actor) {
        return book.guildOf(actor);
    }

    // ------------------------------------------------------------------ founding and disbanding

    /** What founding costs right now, after Core's price index and levers. */
    public Money foundingPrice() {
        return Fees.quote(SOURCE_FOUNDING, Fees.amount(settings.get().foundingPrice()));
    }

    public GuildOutcome found(UUID founder, String typedName, String typedTag) {
        String name = GuildNameRule.tidy(typedName);
        String tag = typedTag == null ? "" : typedTag.strip();
        if (book.guildOf(founder).isPresent()) {
            return GuildOutcome.ALREADY_IN_GUILD;
        }
        Optional<GuildOutcome> wrong = names.name(name).or(() -> names.tag(tag));
        if (wrong.isPresent()) {
            return wrong.get();
        }
        if (book.find(name).isPresent()) {
            return GuildOutcome.NAME_TAKEN;
        }
        if (book.tagTaken(tag, null)) {
            return GuildOutcome.TAG_TAKEN;
        }
        GuildsSettings now = settings.get();
        Money price = Fees.amount(now.foundingPrice());
        EconomyResult paid = Fees.charge(founder, price, "Founding the guild " + name, SOURCE_FOUNDING);
        if (!paid.succeeded()) {
            return paid.outcome() == EconomyResult.Outcome.NOT_ENOUGH ? GuildOutcome.CANNOT_AFFORD : GuildOutcome.ECONOMY_DOWN;
        }
        TeamColour colour = TeamColour.values()[Math.floorMod(name.toLowerCase().hashCode(), TeamColour.values().length)];
        Guild guild = new Guild(TeamId.fromName(name), name, tag, colour, TeamEmblem.NONE, "", now(), founder,
                JoinMode.INVITE, 0, 0, Optional.empty(), false, false,
                Map.of(founder, Member.joining(founder, Rank.LEADER, now())),
                Rank.defaults(Fees.amount(now.officerLimit()).minor(), Fees.amount(now.memberLimit()).minor()), Map.of());
        GuildOutcome made = book.create(guild);
        if (!made.isSuccess()) {
            if (paid.amount().isPositive()) {
                Fees.refund(founder, paid.amount(), "Founding " + name + " did not go through", SOURCE_FOUNDING);
            }
            return made;
        }
        book.forgetQuestionsOf(founder);
        log(guild, GuildEvent.Kind.FOUNDED, founder, null, paid.amount().minor(), name);
        if (now.announce()) {
            notify.everybody("guilds.announce.founded", "guild", GuildNotifier.title(guild), "player", notify.name(founder));
        }
        onChange.run();
        return GuildOutcome.SUCCESS;
    }

    /**
     * Ends a guild. Its bank is shared out first by {@link BankService#shareOut}; a guild whose bank cannot be
     * paid out right now is not disbanded, because disbanding it would destroy the money.
     */
    public GuildOutcome disband(UUID actor, TeamId id, boolean staff, BankService bank) {
        Optional<Guild> found = book.guild(id);
        if (found.isEmpty()) {
            return GuildOutcome.NO_SUCH_GUILD;
        }
        Guild guild = found.get();
        if (!staff && !guild.leader().equals(actor)) {
            return guild.isMember(actor) ? GuildOutcome.NOT_ALLOWED : GuildOutcome.NOT_A_MEMBER;
        }
        GuildOutcome paidOut = bank.shareOut(guild);
        if (!paidOut.isSuccess()) {
            return paidOut;
        }
        if (book.disband(id).isEmpty()) {
            return GuildOutcome.NOT_SAVED;
        }
        notify.members(guild, "guilds.disbanded", "guild", GuildNotifier.title(guild), "player", notify.name(actor));
        if (settings.get().announce()) {
            notify.everybody("guilds.announce.disbanded", "guild", GuildNotifier.title(guild));
        }
        log.info("The guild {} was disbanded by {}{}.", guild.name(), notify.name(actor), staff ? " (staff)" : "");
        onChange.run();
        return GuildOutcome.SUCCESS;
    }

    // ------------------------------------------------------------------ getting in

    private GuildOutcome room(Guild guild) {
        return guild.size() < memberCap(guild) ? GuildOutcome.SUCCESS : GuildOutcome.FULL;
    }

    public GuildOutcome invite(UUID actor, UUID invitee) {
        Optional<Guild> found = own(actor);
        if (found.isEmpty()) {
            return GuildOutcome.NO_GUILD;
        }
        Guild guild = found.get();
        GuildOutcome may = ranks.may(guild, actor, GuildPermission.INVITE);
        if (!may.isSuccess()) {
            return may;
        }
        if (actor.equals(invitee)) {
            return GuildOutcome.YOURSELF;
        }
        if (book.guildOf(invitee).isPresent()) {
            return GuildOutcome.ALREADY_IN_GUILD;
        }
        GuildOutcome space = room(guild);
        if (!space.isSuccess()) {
            return space;
        }
        long expires = now() + Duration.ofHours(settings.get().invitationHours()).toMillis();
        if (!book.ask(new Invitation(Invitation.Kind.INVITE, guild.id().value(), invitee, actor, "", expires), now())) {
            return GuildOutcome.ALREADY_INVITED;
        }
        log(guild, GuildEvent.Kind.INVITED, actor, invitee, 0, "");
        TeamId id = guild.id();
        notify.ask(invitee, Duration.ofHours(settings.get().invitationHours()),
                yes -> notify.tell(yes, acceptInvitation(yes, id).messageKey()),
                no -> notify.tell(no, declineInvitation(no, id).messageKey()),
                "guilds.invite.received", "guild", GuildNotifier.title(guild), "player", notify.name(actor));
        return GuildOutcome.SUCCESS;
    }

    public List<Invitation> invitationsOf(UUID player) {
        return book.questions(Invitation.Kind.INVITE, now()).stream()
                .filter(each -> each.subject().equals(player)).toList();
    }

    public List<Invitation> applicationsTo(Guild guild) {
        return book.questions(Invitation.Kind.APPLICATION, now()).stream()
                .filter(each -> each.guild().equals(guild.id().value())).toList();
    }

    public GuildOutcome acceptInvitation(UUID player, TeamId id) {
        Optional<Invitation> invitation = book.answer(Invitation.Kind.INVITE, id.value(), player, "", now());
        if (invitation.isEmpty()) {
            return GuildOutcome.NOT_INVITED;
        }
        return admit(id, player, invitation.get().by(), GuildEvent.Kind.JOINED);
    }

    public GuildOutcome declineInvitation(UUID player, TeamId id) {
        Optional<Invitation> invitation = book.answer(Invitation.Kind.INVITE, id.value(), player, "", now());
        if (invitation.isEmpty()) {
            return GuildOutcome.NOT_INVITED;
        }
        book.guild(id).ifPresent(guild -> notify.tell(invitation.get().by(), "guilds.invite.declined",
                "player", notify.name(player), "guild", GuildNotifier.title(guild)));
        return GuildOutcome.SUCCESS;
    }

    /** Joins an open guild, or applies to one that takes applications. {@link GuildOutcome#ASKED} for the latter. */
    public GuildOutcome request(UUID player, TeamId id) {
        Optional<Guild> found = book.guild(id);
        if (found.isEmpty()) {
            return GuildOutcome.NO_SUCH_GUILD;
        }
        Guild guild = found.get();
        if (book.guildOf(player).isPresent()) {
            return GuildOutcome.ALREADY_IN_GUILD;
        }
        if (book.answer(Invitation.Kind.INVITE, id.value(), player, "", now()).isPresent()) {
            return admit(id, player, null, GuildEvent.Kind.JOINED);
        }
        return switch (guild.joinMode()) {
            case OPEN -> admit(id, player, null, GuildEvent.Kind.JOINED);
            case INVITE -> GuildOutcome.CLOSED;
            case APPLY -> {
                GuildOutcome space = room(guild);
                if (!space.isSuccess()) {
                    yield space;
                }
                long expires = now() + Duration.ofDays(settings.get().applicationDays()).toMillis();
                if (!book.ask(new Invitation(Invitation.Kind.APPLICATION, id.value(), player, player, "", expires), now())) {
                    yield GuildOutcome.ALREADY_APPLIED;
                }
                log(guild, GuildEvent.Kind.APPLIED, player, null, 0, "");
                for (UUID officer : guild.members().keySet()) {
                    if (guild.may(officer, GuildPermission.INVITE)) {
                        notify.ask(officer, Duration.ofDays(settings.get().applicationDays()),
                                yes -> notify.tell(yes, acceptApplication(yes, player).messageKey()),
                                no -> notify.tell(no, declineApplication(no, player).messageKey()),
                                "guilds.apply.received", "player", notify.name(player));
                    }
                }
                yield GuildOutcome.ASKED;
            }
        };
    }

    public GuildOutcome acceptApplication(UUID actor, UUID applicant) {
        Optional<Guild> found = own(actor);
        if (found.isEmpty()) {
            return GuildOutcome.NO_GUILD;
        }
        Guild guild = found.get();
        GuildOutcome may = ranks.may(guild, actor, GuildPermission.INVITE);
        if (!may.isSuccess()) {
            return may;
        }
        if (book.answer(Invitation.Kind.APPLICATION, guild.id().value(), applicant, "", now()).isEmpty()) {
            return GuildOutcome.NO_APPLICATION;
        }
        return admit(guild.id(), applicant, actor, GuildEvent.Kind.JOINED);
    }

    public GuildOutcome declineApplication(UUID actor, UUID applicant) {
        Optional<Guild> found = own(actor);
        if (found.isEmpty()) {
            return GuildOutcome.NO_GUILD;
        }
        Guild guild = found.get();
        GuildOutcome may = ranks.may(guild, actor, GuildPermission.INVITE);
        if (!may.isSuccess()) {
            return may;
        }
        if (book.answer(Invitation.Kind.APPLICATION, guild.id().value(), applicant, "", now()).isEmpty()) {
            return GuildOutcome.NO_APPLICATION;
        }
        notify.tell(applicant, "guilds.apply.declined", "guild", GuildNotifier.title(guild));
        return GuildOutcome.SUCCESS;
    }

    /** Puts a player in a guild, at its lowest rank, if there is room. */
    private GuildOutcome admit(TeamId id, UUID player, UUID admittedBy, GuildEvent.Kind kind) {
        AtomicReference<GuildOutcome> refused = new AtomicReference<>();
        GuildOutcome changed = book.update(id, guild -> {
            GuildOutcome space = room(guild);
            if (!space.isSuccess()) {
                refused.set(space);
                return guild;
            }
            return guild.withMember(Member.joining(player, guild.lowestRank().id(), now()));
        });
        if (refused.get() != null) {
            return refused.get();
        }
        if (!changed.isSuccess()) {
            return changed;
        }
        book.forgetQuestionsOf(player);
        Guild guild = book.guild(id).orElseThrow();
        log(guild, kind, admittedBy, player, 0, "");
        notify.members(guild, member -> !member.equals(player), "guilds.joined", "player", notify.name(player));
        notify.tell(player, "guilds.welcome", "guild", GuildNotifier.title(guild));
        if (!guild.motd().isBlank()) {
            notify.tell(player, "guilds.motd", "guild", GuildNotifier.title(guild), "motd", guild.motd());
        }
        onChange.run();
        return GuildOutcome.SUCCESS;
    }

    // ------------------------------------------------------------------ getting out

    /** Leaves. The leader of a guild with others in it must hand over first; the last member disbands it. */
    public GuildOutcome leave(UUID player, BankService bank) {
        Optional<Guild> found = own(player);
        if (found.isEmpty()) {
            return GuildOutcome.NO_GUILD;
        }
        Guild guild = found.get();
        if (guild.leader().equals(player)) {
            if (guild.size() > 1) {
                return GuildOutcome.LEADER_MUST_HAND_OVER;
            }
            return disband(player, guild.id(), false, bank);
        }
        GuildOutcome changed = book.update(guild.id(), now -> now.withoutMember(player));
        if (!changed.isSuccess()) {
            return changed;
        }
        log(guild, GuildEvent.Kind.LEFT, player, null, 0, "");
        notify.members(book.guild(guild.id()).orElse(guild), "guilds.left", "player", notify.name(player));
        onChange.run();
        return GuildOutcome.SUCCESS;
    }

    public GuildOutcome kick(UUID actor, UUID target) {
        Optional<Guild> found = own(actor);
        if (found.isEmpty()) {
            return GuildOutcome.NO_GUILD;
        }
        Guild guild = found.get();
        GuildOutcome may = ranks.mayActOn(guild, actor, target, GuildPermission.KICK);
        if (!may.isSuccess()) {
            return may;
        }
        GuildOutcome changed = book.update(guild.id(), now -> now.withoutMember(target));
        if (!changed.isSuccess()) {
            return changed;
        }
        log(guild, GuildEvent.Kind.KICKED, actor, target, 0, "");
        notify.tell(target, "guilds.kicked", "guild", GuildNotifier.title(guild), "player", notify.name(actor));
        notify.members(book.guild(guild.id()).orElse(guild), "guilds.kicked-told", "player", notify.name(target),
                "by", notify.name(actor));
        onChange.run();
        return GuildOutcome.SUCCESS;
    }

    // ------------------------------------------------------------------ the ladder

    public GuildOutcome setRank(UUID actor, UUID target, String rankId) {
        Optional<Guild> found = own(actor);
        if (found.isEmpty()) {
            return GuildOutcome.NO_GUILD;
        }
        Guild guild = found.get();
        Optional<Rank> rank = guild.rank(rankId);
        if (rank.isEmpty()) {
            return GuildOutcome.NO_SUCH_RANK;
        }
        GuildOutcome may = ranks.mayAssign(guild, actor, target, rank.get());
        if (!may.isSuccess()) {
            return may;
        }
        String before = guild.rankOf(target).map(Rank::name).orElse("?");
        GuildOutcome changed = book.update(guild.id(), now -> now.member(target)
                .map(member -> now.withMember(member.withRank(rank.get().id()))).orElse(now));
        if (!changed.isSuccess()) {
            return changed;
        }
        log(guild, GuildEvent.Kind.RANK_CHANGED, actor, target, 0, before + " → " + rank.get().name());
        notify.members(guild, "guilds.rank.changed", "player", notify.name(target), "rank", rank.get().name(),
                "by", notify.name(actor));
        return GuildOutcome.SUCCESS;
    }

    /** One step up or down the ladder. */
    public GuildOutcome step(UUID actor, UUID target, boolean up) {
        Optional<Guild> found = own(actor);
        if (found.isEmpty()) {
            return GuildOutcome.NO_GUILD;
        }
        Optional<Rank> next = ranks.step(found.get(), target, up);
        if (next.isEmpty()) {
            GuildOutcome may = ranks.mayActOn(found.get(), actor, target, GuildPermission.PROMOTE);
            return may.isSuccess() ? GuildOutcome.NO_SUCH_RANK : may;
        }
        return setRank(actor, target, next.get().id());
    }

    /** The leader hands the guild to another member, and becomes the most senior rank below leader. */
    public GuildOutcome handOver(UUID actor, UUID heir) {
        Optional<Guild> found = own(actor);
        if (found.isEmpty()) {
            return GuildOutcome.NO_GUILD;
        }
        Guild guild = found.get();
        if (!guild.leader().equals(actor)) {
            return GuildOutcome.NOT_ALLOWED;
        }
        if (actor.equals(heir)) {
            return GuildOutcome.YOURSELF;
        }
        if (!guild.isMember(heir)) {
            return GuildOutcome.NOT_A_MEMBER;
        }
        GuildOutcome changed = book.update(guild.id(), now -> crown(now, heir));
        if (!changed.isSuccess()) {
            return changed;
        }
        log(guild, GuildEvent.Kind.LEADER_CHANGED, actor, heir, 0, "");
        notify.members(guild, "guilds.leader.handed-over", "player", notify.name(heir), "by", notify.name(actor));
        return GuildOutcome.SUCCESS;
    }

    private static Guild crown(Guild guild, UUID heir) {
        String below = guild.ranks().stream().filter(rank -> !rank.isLeader()).findFirst()
                .map(Rank::id).orElse(guild.lowestRank().id());
        Guild changed = guild.withMember(guild.member(heir).orElseThrow().withRank(Rank.LEADER)).withLeader(heir);
        return changed.withMember(changed.member(guild.leader()).orElseThrow().withRank(below));
    }

    /** Hands over every guild whose leader has been gone too long. @return how many changed hands */
    public int succeed() {
        int days = settings.get().successionDays();
        if (days <= 0) {
            return 0;
        }
        int changed = 0;
        for (Guild guild : book.all()) {
            Optional<UUID> heir = succession.heir(guild, lastSeen, now(), Duration.ofDays(days));
            if (heir.isEmpty()) {
                continue;
            }
            UUID old = guild.leader();
            if (book.update(guild.id(), now -> now.leader().equals(old) && now.isMember(heir.get()) ? crown(now, heir.get()) : now)
                    .isSuccess()) {
                log(guild, GuildEvent.Kind.SUCCEEDED, old, heir.get(), 0, days + "");
                notify.members(guild, "guilds.leader.succeeded", "player", notify.name(heir.get()),
                        "old", notify.name(old), "days", days);
                log.info("{} leads {} now: {} had not been seen in {} days.", notify.name(heir.get()), guild.name(),
                        notify.name(old), days);
                changed++;
            }
        }
        return changed;
    }

    // ------------------------------------------------------------------ ranks themselves

    public GuildOutcome createRank(UUID actor, String typedName) {
        Optional<Guild> found = own(actor);
        if (found.isEmpty()) {
            return GuildOutcome.NO_GUILD;
        }
        Guild guild = found.get();
        GuildOutcome may = ranks.may(guild, actor, GuildPermission.MANAGE_RANKS);
        if (!may.isSuccess()) {
            return may;
        }
        if (guild.ranks().size() >= settings.get().mostRanks()) {
            return GuildOutcome.TOO_MANY_RANKS;
        }
        String name = GuildNameRule.tidy(typedName);
        Optional<GuildOutcome> wrong = names.rankName(name);
        if (wrong.isPresent()) {
            return wrong.get();
        }
        String id = uniqueRankId(guild, Rank.idFrom(name));
        // A new rank goes just above the lowest, so it starts with as little say as possible.
        int lowest = guild.lowestRank().weight();
        int next = guild.ranks().stream().mapToInt(Rank::weight).filter(weight -> weight > lowest).min().orElse(lowest + 100);
        int weight = Math.max(lowest + 1, (lowest + next) / 2);
        if (weight >= next) {
            return GuildOutcome.TOO_MANY_RANKS;
        }
        GuildOutcome changed = book.update(guild.id(), now -> now.withRank(new Rank(id, name, weight, GuildPermission.none(), 0)));
        if (changed.isSuccess()) {
            log(guild, GuildEvent.Kind.RANKS_CHANGED, actor, null, 0, "+ " + name);
        }
        return changed;
    }

    private static String uniqueRankId(Guild guild, String wanted) {
        String id = wanted;
        int suffix = 2;
        while (guild.rank(id).isPresent() || Rank.LEADER.equals(id)) {
            id = wanted + "-" + suffix++;
        }
        return id;
    }

    /** Changes a rank the actor outranks, through {@code change}. */
    public GuildOutcome editRank(UUID actor, String rankId, UnaryOperator<Rank> change, String described) {
        Optional<Guild> found = own(actor);
        if (found.isEmpty()) {
            return GuildOutcome.NO_GUILD;
        }
        Guild guild = found.get();
        Optional<Rank> rank = guild.rank(rankId);
        if (rank.isEmpty()) {
            return GuildOutcome.NO_SUCH_RANK;
        }
        GuildOutcome may = ranks.mayEdit(guild, actor, rank.get());
        if (!may.isSuccess()) {
            return may;
        }
        Rank changedRank = change.apply(rank.get());
        int mine = guild.rankOf(actor).map(Rank::weight).orElse(0);
        if (changedRank.weight() >= mine) {
            return GuildOutcome.OUTRANKED;
        }
        GuildOutcome changed = book.update(guild.id(), now -> now.withRank(changedRank));
        if (changed.isSuccess()) {
            log(guild, GuildEvent.Kind.RANKS_CHANGED, actor, null, 0, changedRank.name() + ": " + described);
        }
        return changed;
    }

    public GuildOutcome toggle(UUID actor, String rankId, GuildPermission permission) {
        Optional<Guild> found = own(actor);
        if (found.isEmpty()) {
            return GuildOutcome.NO_GUILD;
        }
        Optional<Rank> rank = found.get().rank(rankId);
        if (rank.isEmpty()) {
            return GuildOutcome.NO_SUCH_RANK;
        }
        GuildOutcome may = ranks.mayGrant(found.get(), actor, rank.get(), permission);
        if (!may.isSuccess()) {
            return may;
        }
        boolean on = !rank.get().may(permission);
        return editRank(actor, rankId, each -> each.toggled(permission), (on ? "+ " : "− ") + permission.title());
    }

    public GuildOutcome renameRank(UUID actor, String rankId, String typedName) {
        String name = GuildNameRule.tidy(typedName);
        Optional<GuildOutcome> wrong = names.rankName(name);
        if (wrong.isPresent()) {
            return wrong.get();
        }
        return editRank(actor, rankId, each -> each.withName(name), "renamed to " + name);
    }

    /**
     * Sets a rank's daily bank limit — never above the actor's own: otherwise an officer allowed to manage
     * ranks could open the bank to a friend by raising the rank below and promoting them into it.
     */
    public GuildOutcome limitRank(UUID actor, String rankId, long minor) {
        Optional<Rank> mine = own(actor).flatMap(guild -> guild.rankOf(actor));
        if (mine.isPresent() && !mine.get().isUnlimited() && (minor < 0 || minor > mine.get().dailyLimit())) {
            return GuildOutcome.OUTRANKED;
        }
        return editRank(actor, rankId, each -> each.withDailyLimit(minor), "daily limit " + minor);
    }

    /** Swaps a rank with its neighbour above or below — both must be ranks the actor outranks. */
    public GuildOutcome moveRank(UUID actor, String rankId, boolean up) {
        Optional<Guild> found = own(actor);
        if (found.isEmpty()) {
            return GuildOutcome.NO_GUILD;
        }
        Guild guild = found.get();
        List<Rank> ladder = guild.ranks().stream().filter(rank -> !rank.isLeader()).toList();
        int at = -1;
        for (int index = 0; index < ladder.size(); index++) {
            if (ladder.get(index).id().equals(rankId)) {
                at = index;
            }
        }
        if (at < 0) {
            return GuildOutcome.NO_SUCH_RANK;
        }
        int other = up ? at - 1 : at + 1;
        if (other < 0 || other >= ladder.size()) {
            return GuildOutcome.NO_SUCH_RANK;
        }
        Rank moving = ladder.get(at);
        Rank neighbour = ladder.get(other);
        GuildOutcome first = ranks.mayEdit(guild, actor, moving);
        GuildOutcome second = ranks.mayEdit(guild, actor, neighbour);
        if (!first.isSuccess() || !second.isSuccess()) {
            return first.isSuccess() ? second : first;
        }
        GuildOutcome changed = book.update(guild.id(), now -> now.withRank(moving.withWeight(neighbour.weight()))
                .withRank(neighbour.withWeight(moving.weight())));
        if (changed.isSuccess()) {
            log(guild, GuildEvent.Kind.RANKS_CHANGED, actor, null, 0, moving.name() + (up ? " up" : " down"));
        }
        return changed;
    }

    /** Deletes a rank nobody holds. */
    public GuildOutcome deleteRank(UUID actor, String rankId) {
        Optional<Guild> found = own(actor);
        if (found.isEmpty()) {
            return GuildOutcome.NO_GUILD;
        }
        Guild guild = found.get();
        Optional<Rank> rank = guild.rank(rankId);
        if (rank.isEmpty()) {
            return GuildOutcome.NO_SUCH_RANK;
        }
        GuildOutcome may = ranks.mayEdit(guild, actor, rank.get());
        if (!may.isSuccess()) {
            return may;
        }
        if (guild.members().values().stream().anyMatch(member -> member.rank().equals(rankId))) {
            return GuildOutcome.RANK_IN_USE;
        }
        if (guild.ranks().size() <= 2) {
            return GuildOutcome.TOO_MANY_RANKS;
        }
        List<Rank> kept = new ArrayList<>(guild.ranks().stream().filter(each -> !each.id().equals(rankId)).toList());
        GuildOutcome changed = book.update(guild.id(), now -> now.withRanks(kept));
        if (changed.isSuccess()) {
            log(guild, GuildEvent.Kind.RANKS_CHANGED, actor, null, 0, "− " + rank.get().name());
        }
        return changed;
    }

    // ------------------------------------------------------------------ the guild's own choices

    private GuildOutcome edit(UUID actor, GuildPermission needed, UnaryOperator<Guild> change, GuildEvent.Kind kind,
                              String detail) {
        Optional<Guild> found = own(actor);
        if (found.isEmpty()) {
            return GuildOutcome.NO_GUILD;
        }
        Guild guild = found.get();
        GuildOutcome may = ranks.may(guild, actor, needed);
        if (!may.isSuccess()) {
            return may;
        }
        GuildOutcome changed = book.update(guild.id(), change);
        if (changed.isSuccess()) {
            log(guild, kind, actor, null, 0, detail);
        }
        return changed;
    }

    public GuildOutcome rename(UUID actor, String typedName) {
        String name = GuildNameRule.tidy(typedName);
        Optional<GuildOutcome> wrong = names.name(name);
        if (wrong.isPresent()) {
            return wrong.get();
        }
        Optional<Guild> taken = book.find(name);
        Optional<Guild> own = own(actor);
        if (taken.isPresent() && (own.isEmpty() || !taken.get().id().equals(own.get().id()))) {
            return GuildOutcome.NAME_TAKEN;
        }
        GuildOutcome changed = edit(actor, GuildPermission.EDIT_INFO, guild -> guild.withName(name), GuildEvent.Kind.RENAMED, name);
        if (changed.isSuccess()) {
            own.ifPresent(guild -> notify.members(guild, "guilds.renamed", "name", name, "by", notify.name(actor)));
            onChange.run();
        }
        return changed;
    }

    public GuildOutcome retag(UUID actor, String typedTag) {
        String tag = typedTag == null ? "" : typedTag.strip();
        Optional<GuildOutcome> wrong = names.tag(tag);
        if (wrong.isPresent()) {
            return wrong.get();
        }
        GuildOutcome changed = edit(actor, GuildPermission.EDIT_INFO, guild -> guild.withTag(tag), GuildEvent.Kind.RENAMED, "[" + tag + "]");
        if (changed.isSuccess()) {
            onChange.run();
        }
        return changed;
    }

    public GuildOutcome motd(UUID actor, String text) {
        String motd = text == null ? "" : text.strip();
        if (motd.length() > 200) {
            motd = motd.substring(0, 200);
        }
        String kept = motd;
        return edit(actor, GuildPermission.EDIT_INFO, guild -> guild.withMotd(kept), GuildEvent.Kind.SETTINGS_CHANGED, "motd");
    }

    public GuildOutcome look(UUID actor, TeamColour colour, TeamEmblem emblem) {
        GuildOutcome changed = edit(actor, GuildPermission.EDIT_INFO, guild -> guild.withLook(
                colour == null ? guild.colour() : colour, emblem == null ? guild.emblem() : emblem),
                GuildEvent.Kind.LOOK_CHANGED, (colour == null ? "" : colour.describe()) + (emblem == null ? "" : " " + emblem.describe()));
        if (changed.isSuccess()) {
            onChange.run();
        }
        return changed;
    }

    public GuildOutcome joinMode(UUID actor, JoinMode mode) {
        return edit(actor, GuildPermission.EDIT_INFO, guild -> guild.withJoinMode(mode), GuildEvent.Kind.SETTINGS_CHANGED,
                "join: " + mode.key());
    }

    public GuildOutcome friendlyFire(UUID actor, boolean own, boolean allies) {
        Optional<Guild> found = own(actor);
        if (found.isPresent() && !found.get().leader().equals(actor)) {
            return GuildOutcome.NOT_ALLOWED;
        }
        return edit(actor, GuildPermission.EDIT_INFO, guild -> guild.withFriendlyFire(own, allies),
                GuildEvent.Kind.SETTINGS_CHANGED, "friendly fire: " + own + ", allies: " + allies);
    }

    // ------------------------------------------------------------------ staff

    /** Staff: experience set directly. Logged as staff's doing in the guild's own log. */
    public GuildOutcome staffSetXp(UUID staff, TeamId id, long xp) {
        GuildOutcome changed = book.update(id, guild -> guild.withXp(Math.max(0, xp)));
        book.guild(id).ifPresent(guild -> log(guild, GuildEvent.Kind.STAFF, staff, null, xp, "experience set"));
        return changed;
    }

    public GuildOutcome staffRename(UUID staff, TeamId id, String name) {
        // Staff skip the banned words, not the characters: the name is drawn in menus and chat like any other.
        Optional<GuildOutcome> wrong = names.name(name).filter(problem -> problem != GuildOutcome.NAME_BANNED);
        if (wrong.isPresent()) {
            return wrong.get();
        }
        GuildOutcome changed = book.update(id, guild -> guild.withName(GuildNameRule.tidy(name)));
        book.guild(id).ifPresent(guild -> log(guild, GuildEvent.Kind.STAFF, staff, null, 0, "renamed to " + name));
        if (changed.isSuccess()) {
            onChange.run();
        }
        return changed;
    }

    public GuildOutcome staffLeader(UUID staff, TeamId id, UUID heir) {
        Optional<Guild> found = book.guild(id);
        if (found.isEmpty()) {
            return GuildOutcome.NO_SUCH_GUILD;
        }
        if (!found.get().isMember(heir)) {
            return GuildOutcome.NOT_A_MEMBER;
        }
        GuildOutcome changed = book.update(id, guild -> guild.leader().equals(heir) ? guild : crown(guild, heir));
        log(found.get(), GuildEvent.Kind.STAFF, staff, heir, 0, "made leader");
        return changed;
    }
}
