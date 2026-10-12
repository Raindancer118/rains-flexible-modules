package de.raindancer.modules.guilds.service;

import de.raindancer.core.social.team.TeamId;
import de.raindancer.modules.guilds.GuildsSettings;
import de.raindancer.modules.guilds.model.Guild;
import de.raindancer.modules.guilds.model.GuildEvent;
import de.raindancer.modules.guilds.model.GuildOutcome;
import de.raindancer.modules.guilds.model.GuildPermission;
import de.raindancer.modules.guilds.model.Invitation;
import de.raindancer.modules.guilds.model.Relation;
import de.raindancer.modules.guilds.rules.LevelRule;
import de.raindancer.modules.guilds.rules.RankRule;
import de.raindancer.modules.guilds.store.GuildBook;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Alliances and rivalries. An alliance takes both guilds: one asks, the other accepts, and if both ask each
 * other it simply forms. Both sides are written in one step, so there is never an alliance only one guild
 * holds. A rivalry is declared alone, and ended alone by the guild that declared it.
 */
public final class RelationService implements IGuildsService {

    /** Alliance questions name no player; the asked guild's id goes in "other" and this in "subject". */
    private static final UUID NOBODY = new UUID(0, 0);

    private final GuildBook book;
    private final GuildNotifier notify;
    private final LongSupplier clock;
    private final RankRule ranks = new RankRule();
    private volatile GuildsSettings settings;
    private volatile LevelRule levels;

    public RelationService(GuildBook book, GuildNotifier notify, LongSupplier clock, GuildsSettings settings) {
        this.book = book;
        this.notify = notify;
        this.clock = clock;
        settings(settings);
    }

    @Override
    public void settings(GuildsSettings settings) {
        this.settings = settings;
        this.levels = GuildService.levelsFrom(settings);
    }

    public int allySlots(Guild guild) {
        return levels.allySlots(levels.levelOf(guild.xp()));
    }

    private void log(Guild guild, GuildEvent.Kind kind, UUID actor, String detail) {
        book.log(GuildEvent.of(guild.id().value(), clock.getAsLong(), kind, actor, null, 0, detail));
    }

    private record Sides(Guild own, Guild other) {
    }

    private GuildOutcome sides(UUID actor, TeamId otherId, java.util.function.Function<Sides, GuildOutcome> then) {
        Optional<Guild> own = book.guildOf(actor);
        if (own.isEmpty()) {
            return GuildOutcome.NO_GUILD;
        }
        GuildOutcome may = ranks.may(own.get(), actor, GuildPermission.RELATIONS);
        if (!may.isSuccess()) {
            return may;
        }
        Optional<Guild> other = book.guild(otherId);
        if (other.isEmpty()) {
            return GuildOutcome.NO_SUCH_GUILD;
        }
        if (other.get().id().equals(own.get().id())) {
            return GuildOutcome.SAME_GUILD;
        }
        return then.apply(new Sides(own.get(), other.get()));
    }

    /** Asks for an alliance — or, when the other guild already asked, forms it. */
    public GuildOutcome askAlliance(UUID actor, TeamId otherId) {
        return sides(actor, otherId, sides -> {
            Guild own = sides.own();
            Guild other = sides.other();
            if (own.relationTo(other.id()) == Relation.ALLY) {
                return GuildOutcome.ALREADY_ALLIED;
            }
            // Both sides, on both paths: asking each other forms the alliance at once, and an alliance the other
            // guild has no slot for would put it over its cap without anybody there agreeing to that.
            if (own.allies() >= allySlots(own) || other.allies() >= allySlots(other)) {
                return GuildOutcome.TOO_MANY_ALLIES;
            }
            long now = clock.getAsLong();
            if (book.answer(Invitation.Kind.ALLIANCE, other.id().value(), NOBODY, own.id().value(), now).isPresent()) {
                return form(actor, own, other);
            }
            long expires = now + Duration.ofHours(settings.invitationHours()).toMillis();
            if (!book.ask(new Invitation(Invitation.Kind.ALLIANCE, own.id().value(), NOBODY, actor, other.id().value(), expires), now)) {
                return GuildOutcome.ALREADY_INVITED;
            }
            TeamId asking = own.id();
            for (UUID officer : other.members().keySet()) {
                if (other.may(officer, GuildPermission.RELATIONS)) {
                    notify.ask(officer, Duration.ofHours(settings.invitationHours()),
                            yes -> notify.tell(yes, acceptAlliance(yes, asking).messageKey()),
                            no -> notify.tell(no, declineAlliance(no, asking).messageKey()),
                            "guilds.ally.asked", "guild", GuildNotifier.title(own), "player", notify.name(actor));
                }
            }
            return GuildOutcome.ASKED;
        });
    }

    /** The open alliance questions put to this guild. */
    public List<Invitation> alliancesAskedOf(Guild guild) {
        return book.questions(Invitation.Kind.ALLIANCE, clock.getAsLong()).stream()
                .filter(each -> each.other().equals(guild.id().value())).toList();
    }

    public GuildOutcome acceptAlliance(UUID actor, TeamId askingId) {
        return sides(actor, askingId, sides -> {
            if (book.answer(Invitation.Kind.ALLIANCE, sides.other().id().value(), NOBODY, sides.own().id().value(),
                    clock.getAsLong()).isEmpty()) {
                return GuildOutcome.NOT_INVITED;
            }
            if (sides.own().allies() >= allySlots(sides.own())) {
                return GuildOutcome.TOO_MANY_ALLIES;
            }
            if (sides.other().allies() >= allySlots(sides.other())) {
                return GuildOutcome.TOO_MANY_ALLIES;
            }
            return form(actor, sides.own(), sides.other());
        });
    }

    public GuildOutcome declineAlliance(UUID actor, TeamId askingId) {
        return sides(actor, askingId, sides -> book.answer(Invitation.Kind.ALLIANCE, sides.other().id().value(), NOBODY,
                sides.own().id().value(), clock.getAsLong()).isPresent() ? GuildOutcome.SUCCESS : GuildOutcome.NOT_INVITED);
    }

    private GuildOutcome form(UUID actor, Guild own, Guild other) {
        GuildOutcome changed = book.updateBoth(own.id(), guild -> guild.withRelation(other.id(), Relation.ALLY),
                other.id(), guild -> guild.withRelation(own.id(), Relation.ALLY));
        if (changed.isSuccess()) {
            log(own, GuildEvent.Kind.ALLIED, actor, other.name());
            log(other, GuildEvent.Kind.ALLIED, actor, own.name());
            notify.members(own, "guilds.ally.formed", "guild", GuildNotifier.title(other));
            notify.members(other, "guilds.ally.formed", "guild", GuildNotifier.title(own));
        }
        return changed;
    }

    public GuildOutcome endAlliance(UUID actor, TeamId otherId) {
        return sides(actor, otherId, sides -> {
            if (sides.own().relationTo(sides.other().id()) != Relation.ALLY) {
                return GuildOutcome.NOT_ALLIED;
            }
            GuildOutcome changed = book.updateBoth(sides.own().id(), guild -> guild.withRelation(sides.other().id(), Relation.NEUTRAL),
                    sides.other().id(), guild -> guild.withRelation(sides.own().id(), Relation.NEUTRAL));
            if (changed.isSuccess()) {
                log(sides.own(), GuildEvent.Kind.ALLIANCE_ENDED, actor, sides.other().name());
                log(sides.other(), GuildEvent.Kind.ALLIANCE_ENDED, actor, sides.own().name());
                notify.members(sides.own(), "guilds.ally.ended", "guild", GuildNotifier.title(sides.other()));
                notify.members(sides.other(), "guilds.ally.ended", "guild", GuildNotifier.title(sides.own()));
            }
            return changed;
        });
    }

    /** Declares a rivalry. Ends an alliance first, on both sides — a guild cannot be both. */
    public GuildOutcome declareRival(UUID actor, TeamId otherId) {
        return sides(actor, otherId, sides -> {
            Relation now = sides.own().relationTo(sides.other().id());
            if (now == Relation.RIVAL) {
                return GuildOutcome.ALREADY_RIVALS;
            }
            GuildOutcome changed = book.updateBoth(sides.own().id(), guild -> guild.withRelation(sides.other().id(), Relation.RIVAL),
                    sides.other().id(), guild -> guild.relationTo(sides.own().id()) == Relation.ALLY
                            ? guild.withRelation(sides.own().id(), Relation.NEUTRAL) : guild);
            if (changed.isSuccess()) {
                log(sides.own(), GuildEvent.Kind.RIVALRY, actor, sides.other().name());
                log(sides.other(), GuildEvent.Kind.RIVALRY, actor, sides.own().name());
                notify.members(sides.own(), "guilds.rival.declared", "guild", GuildNotifier.title(sides.other()));
                notify.members(sides.other(), "guilds.rival.declared-on-us", "guild", GuildNotifier.title(sides.own()));
            }
            return changed;
        });
    }

    public GuildOutcome endRivalry(UUID actor, TeamId otherId) {
        return sides(actor, otherId, sides -> {
            if (sides.own().relationTo(sides.other().id()) != Relation.RIVAL) {
                return GuildOutcome.NOT_RIVALS;
            }
            GuildOutcome changed = book.update(sides.own().id(), guild -> guild.withRelation(sides.other().id(), Relation.NEUTRAL));
            if (changed.isSuccess()) {
                log(sides.own(), GuildEvent.Kind.RIVALRY_ENDED, actor, sides.other().name());
                notify.members(sides.other(), "guilds.rival.ended", "guild", GuildNotifier.title(sides.own()));
            }
            return changed;
        });
    }
}
