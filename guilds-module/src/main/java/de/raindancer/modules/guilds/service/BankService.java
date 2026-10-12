package de.raindancer.modules.guilds.service;

import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.social.economy.Economies;
import de.raindancer.core.social.economy.Economy;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.guilds.GuildsSettings;
import de.raindancer.modules.guilds.model.Guild;
import de.raindancer.modules.guilds.model.GuildEvent;
import de.raindancer.modules.guilds.model.GuildOutcome;
import de.raindancer.modules.guilds.model.GuildPermission;
import de.raindancer.modules.guilds.model.Member;
import de.raindancer.modules.guilds.model.Rank;
import de.raindancer.modules.guilds.rules.RankRule;
import de.raindancer.modules.guilds.rules.ShareRule;
import de.raindancer.modules.guilds.rules.WithdrawRule;
import de.raindancer.modules.guilds.store.GuildBook;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * A guild's money. It moves between a player's account and the guild's in two steps, and the order is what keeps
 * it from being lost or doubled: paying in takes from the player first and credits the guild only once that
 * worked; taking out debits the guild first (re-checking the limit under the book's lock, so two clicks cannot
 * both pass it) and pays the player after. Whichever second step fails, the first is put back.
 */
public final class BankService implements IGuildsService {

    public static final String SOURCE = "guilds.bank";

    private final GuildBook book;
    private final GuildNotifier notify;
    private final LogChannel log;
    private final LongSupplier clock;
    private final Supplier<Optional<Economy>> economy;
    private final RankRule ranks = new RankRule();
    private final WithdrawRule limits = new WithdrawRule();
    private final ShareRule sharing = new ShareRule();
    private volatile GuildsSettings settings;

    public BankService(GuildBook book, GuildNotifier notify, LogChannel log, LongSupplier clock,
                       Supplier<Optional<Economy>> economy, GuildsSettings settings) {
        this.book = book;
        this.notify = notify;
        this.log = log;
        this.clock = clock;
        this.economy = economy;
        this.settings = settings;
    }

    /** The server's economy, as Core knows it. */
    public static Supplier<Optional<Economy>> current() {
        return Economies::current;
    }

    @Override
    public void settings(GuildsSettings settings) {
        this.settings = settings;
    }

    public boolean isOpen() {
        return settings.bankEnabled() && economy.get().isPresent();
    }

    public String format(long minor) {
        return Fees.format(Money.of(minor));
    }

    public Optional<Money> parse(String typed) {
        Money amount = Fees.amount(typed);
        return amount.isPositive() ? Optional.of(amount) : Optional.empty();
    }

    private long day() {
        return Math.floorDiv(clock.getAsLong(), 86_400_000L);
    }

    /** What this member may take out right now. */
    public long mayTake(Guild guild, UUID member) {
        Optional<Rank> rank = guild.rankOf(member);
        Optional<Member> who = guild.member(member);
        if (rank.isEmpty() || who.isEmpty() || !rank.get().may(GuildPermission.WITHDRAW)) {
            return 0;
        }
        return limits.mayTake(rank.get(), who.get(), day(), guild.bank());
    }

    /** Most the bank may still take, in minor units. */
    private long room(Guild guild) {
        Money most = Fees.amount(settings.bankMost());
        return most.isPositive() ? Math.max(0, most.minor() - guild.bank()) : Long.MAX_VALUE - Math.max(0, guild.bank());
    }

    public GuildOutcome deposit(UUID player, Money amount) {
        if (!settings.bankEnabled()) {
            return GuildOutcome.SWITCHED_OFF;
        }
        Optional<Economy> bank = economy.get();
        if (bank.isEmpty()) {
            return GuildOutcome.ECONOMY_DOWN;
        }
        Optional<Guild> found = book.guildOf(player);
        if (found.isEmpty()) {
            return GuildOutcome.NO_GUILD;
        }
        if (amount == null || !amount.isPositive()) {
            return GuildOutcome.INVALID_AMOUNT;
        }
        Guild guild = found.get();
        if (amount.minor() > room(guild)) {
            return GuildOutcome.FULL;
        }
        EconomyResult taken = bank.get().withdraw(player, amount, "Paid into " + guild.name(), SOURCE);
        if (!taken.succeeded()) {
            return taken.outcome() == EconomyResult.Outcome.NOT_ENOUGH ? GuildOutcome.CANNOT_AFFORD : GuildOutcome.ECONOMY_DOWN;
        }
        AtomicReference<GuildOutcome> refused = new AtomicReference<>();
        GuildOutcome credited = book.update(guild.id(), now -> {
            Optional<Member> member = now.member(player);
            if (member.isEmpty()) {
                refused.set(GuildOutcome.NOT_A_MEMBER);
                return now;
            }
            if (amount.minor() > room(now)) {
                refused.set(GuildOutcome.FULL);
                return now;
            }
            return now.withBank(now.bank() + amount.minor()).withMember(member.get().depositing(amount.minor()));
        });
        GuildOutcome outcome = refused.get() != null ? refused.get() : credited;
        if (!outcome.isSuccess()) {
            EconomyResult back = bank.get().refund(player, amount, "Paying into " + guild.name() + " did not go through", SOURCE);
            if (!back.succeeded()) {
                log.error("{} paid {} towards {} and neither the guild could take it nor could it be given back ({}). "
                        + "Give it back by hand.", notify.name(player), format(amount.minor()), guild.name(), back.outcome());
            }
            return outcome;
        }
        book.log(GuildEvent.of(guild.id().value(), clock.getAsLong(), GuildEvent.Kind.DEPOSIT, player, null, amount.minor(), ""));
        notify.holding(guild, GuildPermission.VIEW_LOG, "guilds.bank.deposited-told", "player", notify.name(player),
                "amount", format(amount.minor()));
        return GuildOutcome.SUCCESS;
    }

    public GuildOutcome withdraw(UUID player, Money amount) {
        if (!settings.bankEnabled()) {
            return GuildOutcome.SWITCHED_OFF;
        }
        Optional<Economy> bank = economy.get();
        if (bank.isEmpty()) {
            return GuildOutcome.ECONOMY_DOWN;
        }
        Optional<Guild> found = book.guildOf(player);
        if (found.isEmpty()) {
            return GuildOutcome.NO_GUILD;
        }
        if (amount == null || !amount.isPositive()) {
            return GuildOutcome.INVALID_AMOUNT;
        }
        Guild guild = found.get();
        GuildOutcome may = ranks.may(guild, player, GuildPermission.WITHDRAW);
        if (!may.isSuccess()) {
            return may;
        }
        long day = day();
        AtomicReference<GuildOutcome> refused = new AtomicReference<>();
        GuildOutcome debited = book.update(guild.id(), now -> {
            if (amount.minor() > now.bank()) {
                refused.set(GuildOutcome.BANK_EMPTY);
                return now;
            }
            if (amount.minor() > mayTake(now, player)) {
                refused.set(GuildOutcome.OVER_DAILY_LIMIT);
                return now;
            }
            return now.withBank(now.bank() - amount.minor())
                    .withMember(now.member(player).orElseThrow().withdrawing(amount.minor(), day));
        });
        GuildOutcome outcome = refused.get() != null ? refused.get() : debited;
        if (!outcome.isSuccess()) {
            return outcome;
        }
        EconomyResult paid = bank.get().deposit(player, amount, "Taken from " + guild.name(), SOURCE);
        if (!paid.succeeded()) {
            book.update(guild.id(), now -> {
                Guild back = now.withBank(now.bank() + amount.minor());
                return now.member(player)
                        .map(member -> back.withMember(new Member(member.player(), member.rank(), member.joined(),
                                member.deposited(), member.withdrawn() - amount.minor(), member.xp(),
                                Math.max(0, member.takenOn(day) - amount.minor()), day)))
                        .orElse(back);
            });
            return GuildOutcome.ECONOMY_DOWN;
        }
        book.log(GuildEvent.of(guild.id().value(), clock.getAsLong(), GuildEvent.Kind.WITHDRAWAL, player, null, amount.minor(), ""));
        notify.holding(guild, GuildPermission.VIEW_LOG, "guilds.bank.withdrawn-told", "player", notify.name(player),
                "amount", format(amount.minor()));
        return GuildOutcome.SUCCESS;
    }

    /**
     * Pays a guild's whole bank out to its members, as the settings say, before it is disbanded. Whatever could
     * not be paid stays in the bank and the answer is {@link GuildOutcome#ECONOMY_DOWN}: the guild is then not
     * disbanded, so nobody's money disappears with it.
     */
    public GuildOutcome shareOut(Guild guild) {
        if (guild.bank() <= 0) {
            return GuildOutcome.SUCCESS;
        }
        Optional<Economy> bank = economy.get();
        if (bank.isEmpty()) {
            return GuildOutcome.ECONOMY_DOWN;
        }
        AtomicLong pot = new AtomicLong();
        GuildOutcome emptied = book.update(guild.id(), now -> {
            pot.set(now.bank());
            return now.withBank(0);
        });
        if (!emptied.isSuccess()) {
            return emptied;
        }
        Map<UUID, Long> weights = new LinkedHashMap<>();
        guild.members().values().forEach(member -> weights.put(member.player(), member.netContribution()));
        Map<UUID, Long> shares = sharing.share(pot.get(), settings.disbandShare(), weights, guild.leader());
        long unpaid = 0;
        for (Map.Entry<UUID, Long> share : shares.entrySet()) {
            EconomyResult paid = bank.get().deposit(share.getKey(), Money.of(share.getValue()),
                    "Your share of " + guild.name() + "'s bank", SOURCE);
            if (paid.succeeded()) {
                notify.tell(share.getKey(), "guilds.bank.share", "guild", GuildNotifier.title(guild),
                        "amount", format(share.getValue()));
                book.log(GuildEvent.of(guild.id().value(), clock.getAsLong(), GuildEvent.Kind.WITHDRAWAL, null,
                        share.getKey(), share.getValue(), "disband share"));
            } else {
                unpaid += share.getValue();
            }
        }
        if (unpaid > 0) {
            long back = unpaid;
            book.update(guild.id(), now -> now.withBank(now.bank() + back));
            log.warn("{} of {}'s bank could not be paid out; it stays in the bank and the guild is kept.",
                    format(unpaid), guild.name());
            return GuildOutcome.ECONOMY_DOWN;
        }
        return GuildOutcome.SUCCESS;
    }
}
