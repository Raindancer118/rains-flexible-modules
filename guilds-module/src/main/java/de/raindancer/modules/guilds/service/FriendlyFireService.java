package de.raindancer.modules.guilds.service;

import de.raindancer.core.world.combat.Attack;
import de.raindancer.core.world.combat.Combat;
import de.raindancer.core.world.combat.Verdict;
import de.raindancer.modules.guilds.GuildsSettings;
import de.raindancer.modules.guilds.rules.FriendlyFireRule;
import de.raindancer.modules.guilds.store.GuildBook;

import java.util.function.Function;

/**
 * Hands {@link FriendlyFireRule} to Core's {@link Combat}, which is the one place that decides who may hurt whom.
 * A guild only ever adds a refusal, so it never makes PvP possible where the world or a claim forbids it.
 */
public final class FriendlyFireService implements IGuildsService {

    private final GuildBook book;
    private final FriendlyFireRule rule = new FriendlyFireRule();
    private final Function<Attack, Verdict> asked = this::judge;

    public FriendlyFireService(GuildBook book) {
        this.book = book;
    }

    @Override
    public void settings(GuildsSettings settings) {
        // Friendly fire is each guild's own choice, stored with the guild, not the owner's.
    }

    public Verdict judge(Attack attack) {
        if (!attack.isPlayerVersusPlayer() || attack.isSelfInflicted()) {
            return null;
        }
        return rule.judge(attack.attackerOf().flatMap(book::guildOf), attack.victimOf().flatMap(book::guildOf));
    }

    public void open(Combat combat, String plugin) {
        combat.alsoAsk(plugin, asked);
    }

    public void close(Combat combat) {
        combat.stopAsking(asked);
    }
}
