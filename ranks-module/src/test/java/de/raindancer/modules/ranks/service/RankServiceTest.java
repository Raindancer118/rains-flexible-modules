package de.raindancer.modules.ranks.service;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.ui.identity.NameBadges;
import de.raindancer.core.ui.text.Text;
import de.raindancer.modules.ranks.RanksModule;
import de.raindancer.modules.ranks.RanksSettings;
import de.raindancer.modules.ranks.model.Holder;
import de.raindancer.modules.ranks.model.Place;
import de.raindancer.modules.ranks.model.Rank;
import de.raindancer.modules.ranks.service.RankService.Outcome;
import de.raindancer.modules.ranks.store.HolderBook;
import de.raindancer.modules.ranks.store.RankCatalogue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RankServiceTest {

    private static final UUID ADA = UUID.randomUUID();

    @TempDir
    Path folder;
    private RankService service;
    private Rank owner;
    private Rank dev;

    @BeforeEach
    void setUp() {
        RankCatalogue catalogue = new RankCatalogue(new YamlStore(folder.resolve("ranks.yml")),
                () -> RanksModule.class.getResourceAsStream("ranks.yml"));
        catalogue.reload();
        HolderBook book = new HolderBook(new YamlStore(folder.resolve("holders.yml")));
        book.load();
        service = new RankService(catalogue, book, RanksSettings.DEFAULTS);
        owner = service.rank("owner").orElseThrow();
        dev = service.rank("dev").orElseThrow();
    }

    private String badge(NameBadges.Place place) {
        return service.badge(ADA, place).map(Text::plain).orElse("");
    }

    @Test
    @DisplayName("a given rank is the badge in chat, above the head and in the tablist")
    void given() {
        assertThat(badge(NameBadges.Place.CHAT)).isEmpty();
        assertThat(service.give(ADA, owner)).isEqualTo(Outcome.DONE);
        assertThat(service.give(ADA, owner)).isEqualTo(Outcome.ALREADY);

        assertThat(badge(NameBadges.Place.CHAT)).isEqualTo("[OWNER]");
        assertThat(badge(NameBadges.Place.NAMETAG)).isEqualTo("[OWNER]");
        assertThat(badge(NameBadges.Place.TABLIST)).isEqualTo("[OWNER]");
        assertThat(service.sortWeight(ADA)).isEqualTo(99);
    }

    @Test
    @DisplayName("a player hides their rank in one place and it is gone from exactly that place")
    void hideOnePlace() {
        service.give(ADA, owner);
        assertThat(service.show(ADA, Place.NAMETAG, false)).isEqualTo(Outcome.DONE);

        assertThat(badge(NameBadges.Place.NAMETAG)).isEmpty();
        assertThat(badge(NameBadges.Place.CHAT)).isEqualTo("[OWNER]");
        assertThat(badge(NameBadges.Place.TABLIST)).isEqualTo("[OWNER]");
    }

    @Test
    @DisplayName("the server switching a place off wins over the player")
    void serverOff() {
        service.give(ADA, owner);
        service.settings(new RanksSettings(true, true, false, true, true));
        assertThat(badge(NameBadges.Place.TABLIST)).isEmpty();
        assertThat(badge(NameBadges.Place.CHAT)).isEqualTo("[OWNER]");
    }

    @Test
    @DisplayName("wearing: a held rank, none, back to auto; somebody else's rank is refused")
    void wearing() {
        service.give(ADA, owner);
        assertThat(service.wear(ADA, "dev")).isEqualTo(Outcome.NOT_HELD);
        service.give(ADA, dev);
        assertThat(service.wear(ADA, "dev")).isEqualTo(Outcome.DONE);
        assertThat(badge(NameBadges.Place.CHAT)).isEqualTo("[DEV]");
        assertThat(service.wear(ADA, Holder.HIDDEN)).isEqualTo(Outcome.DONE);
        assertThat(badge(NameBadges.Place.CHAT)).isEmpty();
        assertThat(service.wear(ADA, "")).isEqualTo(Outcome.DONE);
        assertThat(badge(NameBadges.Place.CHAT)).isEqualTo("[OWNER]");
    }

    @Test
    @DisplayName("taking the worn rank falls back to the next; taking one not held says so")
    void taking() {
        service.give(ADA, owner);
        service.give(ADA, dev);
        service.wear(ADA, "owner");
        assertThat(service.take(ADA, owner)).isEqualTo(Outcome.DONE);
        assertThat(badge(NameBadges.Place.CHAT)).isEqualTo("[DEV]");
        assertThat(service.holder(ADA).worn()).isEmpty();
        assertThat(service.take(ADA, owner)).isEqualTo(Outcome.NOT_HELD);
        assertThat(service.holdersOf(dev)).containsExactly(ADA);
    }

    @Test
    @DisplayName("with choosing off, players cannot change where or which — and their old choices stop counting")
    void choosingOff() {
        service.give(ADA, owner);
        service.show(ADA, Place.CHAT, false);
        service.settings(new RanksSettings(true, true, true, false, true));
        assertThat(service.show(ADA, Place.CHAT, true)).isEqualTo(Outcome.NOT_ALLOWED);
        assertThat(service.wear(ADA, Holder.HIDDEN)).isEqualTo(Outcome.NOT_ALLOWED);
        assertThat(badge(NameBadges.Place.CHAT)).isEqualTo("[OWNER]");
    }
}
