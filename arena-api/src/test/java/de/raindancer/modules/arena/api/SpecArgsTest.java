package de.raindancer.modules.arena.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpecArgsTest {

    @Test
    @DisplayName("words in any order: a mode is a mode, a number the stake, bo3 the rounds, anything else the kit")
    void anyOrder() {
        MatchSpec spec = SpecArgs.parse(new String[]{"invite", "Bo", "500", "knight", "bo3", "duel"}, 2, Mode.DUEL);

        assertThat(spec.mode()).isEqualTo(Mode.DUEL);
        assertThat(spec.kit()).isEqualTo("knight");
        assertThat(spec.stake()).isEqualTo(500);
        assertThat(spec.bestOf()).isEqualTo(3);
    }

    @Test
    @DisplayName("2v2 is teams of two, a preset's name is a mob run with it, 'own' is own gear")
    void shorthands() {
        assertThat(SpecArgs.parse(new String[]{"2v2"}, 0, Mode.DUEL).teamSize()).isEqualTo(2);
        assertThat(SpecArgs.parse(new String[]{"3v3"}, 0, Mode.DUEL).mode()).isEqualTo(Mode.TEAMS);
        MatchSpec nether = SpecArgs.parse(new String[]{"nether", "archer"}, 0, Mode.DUEL);
        assertThat(nether.mode()).isEqualTo(Mode.WAVES);
        assertThat(nether.waves().preset()).isEqualTo("nether");
        assertThat(nether.kit()).isEqualTo("archer");
        assertThat(SpecArgs.parse(new String[]{"own"}, 0, Mode.DUEL).isOwnGear()).isTrue();
    }

    @Test
    @DisplayName("nothing typed is the default fight of the command")
    void nothing() {
        assertThat(SpecArgs.parse(new String[]{"queue"}, 1, Mode.DUEL)).isEqualTo(MatchSpec.duel(null));
        assertThat(SpecArgs.parse(new String[0], 0, Mode.WAVES).mode()).isEqualTo(Mode.WAVES);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("naval, boats, ships and navy all mean a naval battle: its own kit, one round, two to eight")
    void naval() {
        for (String word : java.util.List.of("naval", "boats", "ships", "navy")) {
            MatchSpec spec = SpecArgs.parse(new String[]{word}, 0, Mode.DUEL);
            org.assertj.core.api.Assertions.assertThat(spec.mode()).as(word).isEqualTo(Mode.NAVAL);
            org.assertj.core.api.Assertions.assertThat(spec.kit()).isEqualTo("naval");
        }
        MatchSpec spec = SpecArgs.parse(new String[]{"naval", "archer", "bo5"}, 0, Mode.DUEL);
        org.assertj.core.api.Assertions.assertThat(spec.kit()).as("another kit may be chosen").isEqualTo("archer");
        org.assertj.core.api.Assertions.assertThat(spec.bestOf()).as("one flood, one round").isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(spec.players()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(Mode.NAVAL.most()).isEqualTo(8);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("war, guildwar and gw mean a guild war; training and sparring mean guild training")
    void guildWords() {
        org.assertj.core.api.Assertions.assertThat(SpecArgs.parse(new String[]{"guildwar"}, 0, Mode.DUEL).mode()).isEqualTo(Mode.GUILD_WAR);
        org.assertj.core.api.Assertions.assertThat(SpecArgs.parse(new String[]{"war"}, 0, Mode.DUEL).mode()).isEqualTo(Mode.GUILD_WAR);
        org.assertj.core.api.Assertions.assertThat(SpecArgs.parse(new String[]{"gw", "3v3"}, 0, Mode.DUEL).teamSize()).as("3v3 keeps the guild mode").isEqualTo(3);
        org.assertj.core.api.Assertions.assertThat(SpecArgs.parse(new String[]{"gw", "3v3"}, 0, Mode.DUEL).mode()).isEqualTo(Mode.GUILD_WAR);
        org.assertj.core.api.Assertions.assertThat(SpecArgs.parse(new String[]{"sparring"}, 0, Mode.DUEL).mode()).isEqualTo(Mode.GUILD_TRAINING);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("mounted, joust, pigs and cavalry mean mounted combat: the lancer kit, one round, two to eight")
    void mounted() {
        for (String word : java.util.List.of("mounted", "joust", "pigs")) {
            MatchSpec spec = SpecArgs.parse(new String[]{word}, 0, Mode.DUEL);
            org.assertj.core.api.Assertions.assertThat(spec.mode()).as(word).isEqualTo(Mode.MOUNTED);
            org.assertj.core.api.Assertions.assertThat(spec.kit()).isEqualTo("lancer");
            org.assertj.core.api.Assertions.assertThat(spec.bestOf()).isEqualTo(1);
        }
        org.assertj.core.api.Assertions.assertThat(Mode.MOUNTED.isFreeForAll()).isTrue();
        org.assertj.core.api.Assertions.assertThat(Mode.MOUNTED.isRidden()).isTrue();
        org.assertj.core.api.Assertions.assertThat(Mode.NAVAL.isRidden()).isTrue();
        org.assertj.core.api.Assertions.assertThat(Mode.DUEL.isRidden()).isFalse();
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("horse, horseback, cavalry and knights mean horseback combat: the rider kit, ridden, two to eight")
    void horseback() {
        for (String word : java.util.List.of("horse", "horseback", "cavalry", "knights")) {
            MatchSpec spec = SpecArgs.parse(new String[]{word}, 0, Mode.DUEL);
            org.assertj.core.api.Assertions.assertThat(spec.mode()).as(word).isEqualTo(Mode.HORSEBACK);
            org.assertj.core.api.Assertions.assertThat(spec.kit()).isEqualTo("rider");
        }
        org.assertj.core.api.Assertions.assertThat(Mode.HORSEBACK.isRidden()).isTrue();
        org.assertj.core.api.Assertions.assertThat(Mode.HORSEBACK.isFreeForAll()).isTrue();
    }
}
