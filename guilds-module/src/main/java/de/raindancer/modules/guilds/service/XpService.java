package de.raindancer.modules.guilds.service;

import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.modules.guilds.GuildsSettings;
import de.raindancer.modules.guilds.model.Guild;
import de.raindancer.modules.guilds.model.GuildEvent;
import de.raindancer.modules.guilds.rules.LevelRule;
import de.raindancer.modules.guilds.rules.XpRule;
import de.raindancer.modules.guilds.store.GuildBook;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Experience members earn their guild. What counts is {@link XpRule}'s; this keeps the hourly tally per member
 * and says so when a guild goes up a level.
 */
public final class XpService implements IGuildsService {

    private static final long HOUR = 3_600_000L;

    private final GuildBook book;
    private final GuildNotifier notify;
    private final Effects effects;
    private final LongSupplier clock;
    /** Per member: the hour (epoch hour) and what they earned in it. Forgotten when they leave the server. */
    private final Map<UUID, long[]> thisHour = new HashMap<>();
    private volatile GuildsSettings settings;
    private volatile XpRule rule;
    private volatile LevelRule levels;

    public XpService(GuildBook book, GuildNotifier notify, Effects effects, LongSupplier clock, GuildsSettings settings) {
        this.book = book;
        this.notify = notify;
        this.effects = effects;
        this.clock = clock;
        settings(settings);
    }

    @Override
    public void settings(GuildsSettings settings) {
        this.settings = settings;
        this.rule = new XpRule(settings.hourlyCap(), settings.sizeDamping());
        this.levels = GuildService.levelsFrom(settings);
    }

    public GuildsSettings current() {
        return settings;
    }

    /**
     * Counts {@code earned} for the member's guild, as much of it as the rule lets through.
     *
     * @return what was actually counted; 0 for somebody in no guild
     */
    public long earn(UUID player, long earned) {
        if (earned <= 0) {
            return 0;
        }
        Optional<Guild> found = book.guildOf(player);
        if (found.isEmpty()) {
            return 0;
        }
        long hour = Math.floorDiv(clock.getAsLong(), HOUR);
        long counted;
        synchronized (thisHour) {
            long[] tally = thisHour.computeIfAbsent(player, who -> new long[]{hour, 0});
            if (tally[0] != hour) {
                tally[0] = hour;
                tally[1] = 0;
            }
            counted = rule.award(earned, tally[1], found.get().size());
            tally[1] += counted;
        }
        if (counted <= 0) {
            return 0;
        }
        AtomicInteger before = new AtomicInteger();
        AtomicInteger after = new AtomicInteger();
        AtomicLong total = new AtomicLong();
        book.update(found.get().id(), guild -> guild.member(player).map(member -> {
            before.set(levels.levelOf(guild.xp()));
            long xp = guild.xp() > Long.MAX_VALUE - counted ? Long.MAX_VALUE : guild.xp() + counted;
            after.set(levels.levelOf(xp));
            total.set(xp);
            return guild.withXp(xp).withMember(member.earning(counted));
        }).orElse(guild));
        if (after.get() > before.get()) {
            Guild guild = book.guild(found.get().id()).orElse(found.get());
            book.log(GuildEvent.of(guild.id().value(), clock.getAsLong(), GuildEvent.Kind.LEVEL_UP, player, null, 0,
                    String.valueOf(after.get())));
            notify.members(guild, "guilds.level.up", "guild", GuildNotifier.title(guild), "level", after.get(),
                    "members", levels.memberCap(after.get()), "allies", levels.allySlots(after.get()));
            guild.members().keySet().forEach(member -> effects.play(member, Cues.REWARD));
            if (levels.hasHome(after.get()) && !levels.hasHome(before.get())) {
                notify.members(guild, "guilds.level.home-unlocked");
            }
        }
        return counted;
    }

    public void forget(UUID player) {
        synchronized (thisHour) {
            thisHour.remove(player);
        }
    }
}
