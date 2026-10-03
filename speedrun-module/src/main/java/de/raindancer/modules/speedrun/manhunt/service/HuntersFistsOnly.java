package de.raindancer.modules.speedrun.manhunt.service;

import de.raindancer.core.world.combat.Attack;
import de.raindancer.core.world.combat.Verdict;
import de.raindancer.modules.speedrun.manhunt.ManhuntSettings;
import de.raindancer.modules.speedrun.manhunt.model.Hunt;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * {@link ManhuntSettings#huntersFistsOnly()}, as one of Core's combat rules
 * ({@code Combat.alsoAsk}) rather than a damage listener of its own: Core already follows an arrow,
 * a pet or a splash potion back to whoever is behind it, and says how the hit landed.
 *
 * <p>Answers null — no opinion — for everything that is not one Hunter hurting another in a hunt
 * that is on, so the server's own PvP rules still decide the rest.
 */
public final class HuntersFistsOnly implements Function<Attack, Verdict> {

    private final Supplier<Optional<Hunt>> liveHunt;
    private final Supplier<ManhuntSettings> settings;

    public HuntersFistsOnly(Supplier<Optional<Hunt>> liveHunt, Supplier<ManhuntSettings> settings) {
        this.liveHunt = Objects.requireNonNull(liveHunt, "liveHunt");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override
    public Verdict apply(Attack attack) {
        if (!settings.get().huntersFistsOnly() || !attack.isPlayerVersusPlayer()) {
            return null;
        }
        Hunt hunt = liveHunt.get().orElse(null);
        if (hunt == null || !hunt.isHunter(attack.attackerId()) || !hunt.isHunter(attack.victimId())) {
            return null;
        }
        return attack.isBareHanded() ? null : Verdict.FISTS_ONLY;
    }
}
