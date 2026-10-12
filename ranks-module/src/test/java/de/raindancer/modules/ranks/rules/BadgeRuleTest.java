package de.raindancer.modules.ranks.rules;

import de.raindancer.modules.ranks.RanksSettings;
import de.raindancer.modules.ranks.model.Holder;
import de.raindancer.modules.ranks.model.Place;
import de.raindancer.modules.ranks.model.Rank;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BadgeRuleTest {

    private static final UUID ADA = UUID.randomUUID();
    private static final Rank OWNER = new Rank("owner", "Owner", "<red>[OWNER]", 99, List.of());
    private static final Rank DEV = new Rank("dev", "Developer", "<aqua>[DEV]", 80, List.of());
    private static final Map<String, Rank> KNOWN = Map.of("owner", OWNER, "dev", DEV);
    private static final RanksSettings ALL = RanksSettings.DEFAULTS;

    private final BadgeRule rule = new BadgeRule();

    private static Holder holding(String... ranks) {
        return Holder.fresh(ADA).withRanks(Set.of(ranks));
    }

    @Test
    @DisplayName("without a pick, the most senior rank is worn")
    void mostSenior() {
        assertThat(rule.worn(holding("dev", "owner"), KNOWN, true)).contains(OWNER);
    }

    @Test
    @DisplayName("a picked rank is worn while it is held; a taken-away pick falls back to the most senior")
    void picked() {
        assertThat(rule.worn(holding("dev", "owner").wearing("dev"), KNOWN, true)).contains(DEV);
        assertThat(rule.worn(holding("owner").wearing("dev"), KNOWN, true)).contains(OWNER);
    }

    @Test
    @DisplayName("'none' wears nothing — unless players may not choose, then the server's pick stands")
    void hidden() {
        assertThat(rule.worn(holding("owner").wearing(Holder.HIDDEN), KNOWN, true)).isEmpty();
        assertThat(rule.worn(holding("owner", "dev").wearing("dev"), KNOWN, false)).contains(OWNER);
        assertThat(rule.worn(holding("owner").wearing(Holder.HIDDEN), KNOWN, false)).contains(OWNER);
    }

    @Test
    @DisplayName("a rank deleted from ranks.yml is not shown, and nobody without a rank shows one")
    void unknownAndNone() {
        assertThat(rule.worn(holding("ghost"), KNOWN, true)).isEmpty();
        assertThat(rule.worn(Holder.fresh(ADA), KNOWN, true)).isEmpty();
        assertThat(rule.worn(null, KNOWN, true)).isEmpty();
    }

    @Test
    @DisplayName("each place: the server's switch first, then the player's own")
    void places() {
        Holder ada = holding("owner").showing(Place.NAMETAG, false);
        assertThat(rule.shown(ada, KNOWN, ALL, Place.CHAT)).contains(OWNER);
        assertThat(rule.shown(ada, KNOWN, ALL, Place.NAMETAG)).isEmpty();
        assertThat(rule.shown(ada, KNOWN, ALL, Place.TABLIST)).contains(OWNER);

        RanksSettings noChat = new RanksSettings(false, true, true, true, true);
        assertThat(rule.shown(ada, KNOWN, noChat, Place.CHAT)).isEmpty();
    }

    @Test
    @DisplayName("with choosing off, a player's own switches are ignored rather than obeyed")
    void choosingOff() {
        RanksSettings decided = new RanksSettings(true, true, true, false, true);
        Holder ada = holding("owner").showing(Place.CHAT, false);
        assertThat(rule.shown(ada, KNOWN, decided, Place.CHAT)).contains(OWNER);
    }

    @Test
    @DisplayName("the tablist sorts by the worn rank's weight, or not at all when sorting is off")
    void sorting() {
        assertThat(rule.sortWeight(holding("dev"), KNOWN, ALL)).isEqualTo(80);
        assertThat(rule.sortWeight(holding("owner").wearing(Holder.HIDDEN), KNOWN, ALL)).isZero();
        assertThat(rule.sortWeight(holding("owner"), KNOWN, new RanksSettings(true, true, true, true, false))).isZero();
    }
}
