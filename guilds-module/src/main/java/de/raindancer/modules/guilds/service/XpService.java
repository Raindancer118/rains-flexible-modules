package de.raindancer.modules.guilds.service;

import de.raindancer.core.platform.util.Cooldowns;
import de.raindancer.core.platform.util.Times;
import de.raindancer.core.social.team.TeamId;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.modules.guilds.GuildProgressSettings;
import de.raindancer.modules.guilds.GuildsSettings;
import de.raindancer.modules.guilds.model.Activity;
import de.raindancer.modules.guilds.model.Boost;
import de.raindancer.modules.guilds.model.Contract;
import de.raindancer.modules.guilds.model.Guild;
import de.raindancer.modules.guilds.model.GuildEvent;
import de.raindancer.modules.guilds.model.Relation;
import de.raindancer.modules.guilds.model.XpSource;
import de.raindancer.modules.guilds.rules.ActivityRule;
import de.raindancer.modules.guilds.rules.ContractRule;
import de.raindancer.modules.guilds.rules.LevelRule;
import de.raindancer.modules.guilds.rules.TogetherRule;
import de.raindancer.modules.guilds.rules.XpRule;
import de.raindancer.modules.guilds.store.GuildBook;
import de.raindancer.modules.guilds.store.ProgressBook;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.ToIntBiFunction;

/**
 * Everything that earns a guild experience goes through {@link #activity}: what it is worth ({@link ActivityRule}),
 * the server's boost and the bonus for guildmates nearby ({@link TogetherRule}), the member's hourly cap and the
 * guild's size ({@link XpRule}), the tally by source, and the week's contracts — whose rewards are paid here, once.
 */
public final class XpService implements IGuildsService {

    private static final long HOUR = 3_600_000L;

    private final GuildBook book;
    private final ProgressBook progress;
    private final GuildNotifier notify;
    private final Effects effects;
    private final LongSupplier clock;
    private final ToIntBiFunction<UUID, Guild> nearby;
    private final ActivityRule worth = new ActivityRule();
    private final ContractRule contracts = new ContractRule();
    /** Per member: the hour (epoch hour) and what they earned in it. Forgotten when they leave the server. */
    private final Map<UUID, long[]> thisHour = new HashMap<>();
    /** Killer and victim: a rival counts once an hour, so two friends in rival guilds cannot farm each other. */
    private final Cooldowns<String> rivalKills;
    private volatile GuildsSettings settings;
    private volatile GuildProgressSettings more;
    private volatile XpRule rule;
    private volatile LevelRule levels;
    private volatile TogetherRule together;

    /** @param nearby how many guildmates are near that member right now */
    public XpService(GuildBook book, ProgressBook progress, GuildNotifier notify, Effects effects, LongSupplier clock,
                     GuildsSettings settings, GuildProgressSettings more, ToIntBiFunction<UUID, Guild> nearby) {
        this.book = book;
        this.progress = progress;
        this.notify = notify;
        this.effects = effects;
        this.clock = clock;
        this.nearby = nearby;
        this.rivalKills = new Cooldowns<>(clock);
        rivalKills.every(Duration.ofHours(1));
        settings(settings);
        progressSettings(more);
    }

    @Override
    public void settings(GuildsSettings settings) {
        this.settings = settings;
        this.rule = new XpRule(settings.hourlyCap(), settings.sizeDamping());
        this.levels = GuildService.levelsFrom(settings);
    }

    public void progressSettings(GuildProgressSettings more) {
        this.more = more;
        this.together = new TogetherRule(more.togetherPercent(), more.togetherMost());
    }

    public GuildsSettings current() {
        return settings;
    }

    public GuildProgressSettings progressSettings() {
        return more;
    }

    public LevelRule levels() {
        return levels;
    }

    public ProgressBook progress() {
        return progress;
    }

    private long now() {
        return clock.getAsLong();
    }

    // ------------------------------------------------------------------ earning

    /**
     * A member did something. Counts it for their guild and towards its contracts.
     *
     * @return the experience actually counted; 0 for somebody in no guild
     */
    public long activity(UUID player, Activity activity) {
        Optional<Guild> found = book.guildOf(player);
        if (found.isEmpty() || activity.amount() <= 0) {
            return 0;
        }
        Guild guild = found.get();
        long counted = earn(player, guild, activity.source(), worth.worth(activity, settings, more));
        if (more.contracts()) {
            long week = ContractRule.weekOf(now());
            contracts(guild);
            for (Contract finished : progress.advance(guild.id().value(), week, activity)) {
                pay(guild.id(), finished);
            }
        }
        return counted;
    }

    /** The bonus this member gets right now, in percent: the boost and their guildmates nearby. */
    public int bonusPercent(UUID player, Guild guild) {
        return progress.boost().percentAt(now()) + together.bonusPercent(nearby.applyAsInt(player, guild));
    }

    private long earn(UUID player, Guild guild, XpSource source, long base) {
        if (base <= 0) {
            return 0;
        }
        long counted;
        if (source.isCapped()) {
            long scaled = TogetherRule.withBonus(base, bonusPercent(player, guild));
            long hour = Math.floorDiv(now(), HOUR);
            synchronized (thisHour) {
                long[] tally = thisHour.computeIfAbsent(player, who -> new long[]{hour, 0});
                if (tally[0] != hour) {
                    tally[0] = hour;
                    tally[1] = 0;
                }
                counted = rule.award(scaled, tally[1], guild.size());
                tally[1] += counted;
            }
        } else {
            counted = base;
        }
        if (counted > 0) {
            credit(guild.id(), player, source, counted);
        }
        return counted;
    }

    /** Adds experience to a guild — and to the member, when one earned it — and tells everybody of a new level. */
    private void credit(TeamId id, UUID member, XpSource source, long counted) {
        AtomicInteger before = new AtomicInteger();
        AtomicInteger after = new AtomicInteger();
        book.update(id, guild -> {
            before.set(levels.levelOf(guild.xp()));
            long xp = guild.xp() > Long.MAX_VALUE - counted ? Long.MAX_VALUE : guild.xp() + counted;
            after.set(levels.levelOf(xp));
            Guild changed = guild.withXp(xp);
            return member == null ? changed
                    : changed.member(member).map(each -> changed.withMember(each.earning(counted))).orElse(changed);
        });
        progress.add(id.value(), source, counted);
        if (after.get() > before.get()) {
            Guild guild = book.guild(id).orElse(null);
            if (guild == null) {
                return;
            }
            book.log(GuildEvent.of(guild.id().value(), now(), GuildEvent.Kind.LEVEL_UP, member, null, 0,
                    String.valueOf(after.get())));
            notify.members(guild, "guilds.level.up", "guild", GuildNotifier.title(guild), "level", after.get(),
                    "members", levels.memberCap(after.get()), "allies", levels.allySlots(after.get()));
            guild.members().keySet().forEach(each -> effects.play(each, Cues.REWARD));
            if (levels.hasHome(after.get()) && !levels.hasHome(before.get())) {
                notify.members(guild, "guilds.level.home-unlocked");
            }
        }
    }

    /** Staff giving or taking experience by hand, counted under {@link XpSource#STAFF}. */
    public void grant(TeamId id, long amount, UUID by) {
        if (amount > 0) {
            credit(id, null, XpSource.STAFF, amount);
            book.log(GuildEvent.of(id.value(), now(), GuildEvent.Kind.STAFF, by, null, amount, "experience given"));
        }
    }

    // ------------------------------------------------------------------ rivals

    /** A member of one guild killed a member of a rival guild — rival either way round — at most once an hour each. */
    public long rivalDefeated(UUID killer, UUID victim) {
        Optional<Guild> own = book.guildOf(killer);
        Optional<Guild> theirs = book.guildOf(victim);
        if (own.isEmpty() || theirs.isEmpty() || own.get().id().equals(theirs.get().id())) {
            return 0;
        }
        boolean rivals = own.get().relationTo(theirs.get().id()) == Relation.RIVAL
                || theirs.get().relationTo(own.get().id()) == Relation.RIVAL;
        if (!rivals || !rivalKills.tryUse(killer + ":" + victim)) {
            return 0;
        }
        return activity(killer, Activity.of(XpSource.RIVAL_KILL, 1));
    }

    // ------------------------------------------------------------------ contracts

    /** This week's contracts for the guild, dealt the first time the week is asked for; empty when switched off. */
    public List<Contract> contracts(Guild guild) {
        if (!more.contracts()) {
            return List.of();
        }
        long week = ContractRule.weekOf(now());
        int level = levels.levelOf(guild.xp());
        long step = levels.isTop(level) ? levels.toReach(level) - levels.toReach(level - 1) : levels.toReach(level + 1) - levels.toReach(level);
        long reward = ContractRule.reward(step, more.contractRewardPercent());
        return progress.contracts(guild.id().value(), week, () -> contracts.draw(guild.id().value(), week, guild.size(),
                more.contractsPerWeek(), more.contractSizePercent(), reward));
    }

    public long weekEnds() {
        return ContractRule.weekEnds(ContractRule.weekOf(now()));
    }

    private void pay(TeamId id, Contract finished) {
        credit(id, null, XpSource.CONTRACT, finished.reward());
        book.log(GuildEvent.of(id.value(), now(), GuildEvent.Kind.CONTRACT_DONE, null, null, finished.reward(), finished.title()));
        book.guild(id).ifPresent(guild -> {
            notify.members(guild, "guilds.contract.done", "contract", finished.title(), "reward", finished.reward());
            guild.members().keySet().forEach(each -> effects.play(each, Cues.REWARD));
        });
    }

    // ------------------------------------------------------------------ the boost

    public Boost boost() {
        return progress.boost();
    }

    public void boost(int percent, Duration lasting, UUID by) {
        Boost started = new Boost(percent, now() + lasting.toMillis(), Optional.ofNullable(by));
        progress.boost(started);
        if (percent > 0) {
            notify.everybody("guilds.boost.started", "percent", percent, "time", Times.describe(lasting));
        } else {
            notify.everybody("guilds.boost.ended");
        }
    }

    public void forget(UUID player) {
        synchronized (thisHour) {
            thisHour.remove(player);
        }
    }
}
