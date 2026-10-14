package de.raindancer.modules.cosmetics.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.store.Documents;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.testkit.TestDatabases;
import de.raindancer.core.ui.effect.ParticleShape;
import de.raindancer.modules.cosmetics.model.Catalogue;
import de.raindancer.modules.cosmetics.model.ParticleChoice;
import de.raindancer.modules.cosmetics.store.WingReservations.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** What a player bought, what is reserved and what the owner put in the catalogue: one truth for the network. */
@Tag("database")
class CosmeticsNetworkTest {

    private static final ParticleChoice PLUM = new ParticleChoice("DUST", ParticleShape.GRAND_WINGS, 0x3B1A2F)
            .withColourTo(0x5C203E);

    @TempDir
    Path plugins;

    private Servers network;
    private final UUID tom = UUID.randomUUID();
    private final UUID ana = UUID.randomUUID();

    private Servers servers() {
        if (network == null) {
            network = new Servers(plugins);
        }
        return network;
    }

    private UnlockBook unlocks(String id) {
        UnlockBook book = new UnlockBook(servers().network(servers().server(id), "unlocks.yml"));
        book.load();
        book.watch();
        return book;
    }

    private WingReservations wings(String id) {
        WingReservations reservations = new WingReservations(servers().network(servers().server(id), "wing-reservations.yml"));
        reservations.load();
        reservations.watch();
        return reservations;
    }

    private CatalogueFile catalogue(String id) {
        Documents documents = servers().server(id);
        CatalogueFile file = new CatalogueFile(servers().network(documents, "catalogue.yml"), servers().own(documents, "config.yml"));
        file.load(problem -> { });
        file.watch(problem -> { }, loaded -> { });
        return file;
    }

    @AfterEach
    void close() {
        if (network != null) {
            network.close();
        }
    }

    @Test
    @DisplayName("what is bought on one server is the player's on the other; two purchases at once are both kept")
    void purchasesFollowThePlayer() {
        UnlockBook smp = unlocks("smp");
        UnlockBook arena = unlocks("arena");

        smp.add(tom, "name.gradient");
        arena.add(tom, "particles");
        arena.add(ana, "preset.sunset");
        network.heard();

        assertThat(smp.of(tom)).containsExactlyInAnyOrder("name.gradient", "particles");
        assertThat(arena.of(tom)).containsExactlyInAnyOrder("name.gradient", "particles");
        assertThat(smp.has(ana, "preset.sunset")).isTrue();
    }

    @Test
    @DisplayName("a purchase is added to the player's list as the document has it, not as this server last saw it")
    void purchaseIsAddedToWhatIsStored() {
        UnlockBook smp = unlocks("smp");
        UnlockBook arena = unlocks("arena");

        smp.add(tom, "name.gradient");
        arena.add(tom, "particles");   // the arena has not heard of the first one yet
        network.heard();

        assertThat(smp.of(tom)).containsExactlyInAnyOrder("name.gradient", "particles");
    }

    @Test
    @DisplayName("purchases that cannot be read keep what is held and refuse buying")
    void unreadableIsNotWrittenOver() {
        Database database = TestDatabases.open(plugins.resolve("lost.db"), Documents.SCHEMA);
        Documents documents = new Documents(database, null, plugins, "smp", System::currentTimeMillis);
        UnlockBook book = new UnlockBook(new YamlStore(plugins.resolve("RainsCosmetics").resolve("unlocks.yml"),
                documents, Documents.Scope.NETWORK));
        book.load();
        book.add(tom, "particles");

        database.close();
        book.load();

        assertThat(book.readable()).isFalse();
        assertThat(book.has(tom, "particles")).isTrue();
        assertThat(book.add(ana, "particles")).isEqualTo(UnlockBook.Added.NOT_SAVED);
        assertThat(book.has(ana, "particles")).isFalse();
    }

    @Test
    @DisplayName("wings reserved on one server cannot be worn or reserved on the other")
    void reservationHoldsEverywhere() {
        WingReservations smp = wings("smp");
        WingReservations arena = wings("arena");

        assertThat(smp.reserve(tom, "Tom", PLUM)).isEqualTo(Outcome.RESERVED);
        network.heard();

        assertThat(arena.mayWear(ana, PLUM)).isFalse();
        assertThat(arena.reserve(ana, "Ana", PLUM)).isEqualTo(Outcome.TAKEN);
        assertThat(arena.release(ana, false, PLUM)).isEqualTo(Outcome.NOT_YOURS);
        assertThat(arena.release(ana, true, PLUM)).isEqualTo(Outcome.RELEASED);
        network.heard();
        assertThat(smp.mayWear(ana, PLUM)).isTrue();
    }

    @Test
    @DisplayName("two servers reserving the same wings before either has heard: exactly one wins")
    void raceHasOneWinner() {
        WingReservations smp = wings("smp");
        WingReservations arena = wings("arena");

        Outcome first = smp.reserve(tom, "Tom", PLUM);
        Outcome second = arena.reserve(ana, "Ana", PLUM);
        network.heard();

        assertThat(first).isEqualTo(Outcome.RESERVED);
        assertThat(second).isEqualTo(Outcome.TAKEN);
        assertThat(arena.holderOf(PLUM).orElseThrow().owner()).isEqualTo(tom);
        assertThat(smp.all()).hasSize(1);
    }

    @Test
    @DisplayName("releasing everything of one owner on one server frees it on the other, and counts what it freed from the document")
    void releaseAllTravels() {
        WingReservations smp = wings("smp");
        WingReservations arena = wings("arena");
        smp.reserve(tom, "Tom", PLUM);
        arena.reserve(tom, "Tom", PLUM.withShape(ParticleShape.DRAGON_WINGS));
        network.heard();

        assertThat(smp.releaseAllOf("tom")).isEqualTo(2);
        network.heard();

        assertThat(arena.all()).isEmpty();
    }

    @Test
    @DisplayName("reservations that cannot be saved say so and hold nothing")
    void unsavedReservationIsNotHeld() {
        Database database = TestDatabases.open(plugins.resolve("lost.db"), Documents.SCHEMA);
        Documents documents = new Documents(database, null, plugins, "smp", System::currentTimeMillis);
        WingReservations reservations = new WingReservations(new YamlStore(
                plugins.resolve("RainsCosmetics").resolve("wing-reservations.yml"), documents, Documents.Scope.NETWORK));
        reservations.load();
        database.close();

        assertThat(reservations.reserve(tom, "Tom", PLUM)).isEqualTo(Outcome.NOT_SAVED);
        assertThat(reservations.mayWear(ana, PLUM)).isTrue();
    }

    @Test
    @DisplayName("an edit of the catalogue on one server is read by the other; the shipped one is written once")
    void catalogueIsShared() {
        CatalogueFile smp = catalogue("smp");
        CatalogueFile arena = catalogue("arena");
        YamlStore owner = servers().network(servers().server("owner"), "catalogue.yml");
        assertThat(arena.current().preset("sunset")).isPresent();

        owner.update(yaml -> yaml.set("presets.sunset", null));
        network.heard();

        assertThat(smp.current().preset("sunset")).isEmpty();
        assertThat(arena.current().preset("sunset")).isEmpty();
        assertThat(arena.current().preset("ocean")).isPresent();
    }

    @Test
    @DisplayName("a newer server is told when the catalogue changes, so it can register what is new")
    void reloadIsReported() {
        Documents documents = servers().server("smp");
        CatalogueFile file = new CatalogueFile(servers().network(documents, "catalogue.yml"), null);
        file.load(problem -> { });
        AtomicInteger told = new AtomicInteger();
        file.watch(problem -> { }, loaded -> told.incrementAndGet());

        servers().network(servers().server("owner"), "catalogue.yml").update(yaml -> yaml.set("presets.dusk.title", "Dusk"));
        network.heard();

        assertThat(told).hasValue(1);
    }

    @Test
    @DisplayName("1.x files are brought into the network's documents on first use")
    void oneXFilesAreImported() throws Exception {
        Path folder = Files.createDirectories(plugins.resolve("RainsCosmetics"));
        Files.writeString(folder.resolve("unlocks.yml"), "unlocks:\n  " + tom + ": [particles]\n");
        Files.writeString(folder.resolve("wing-reservations.yml"), "reservations:\n  x:\n    combo: x\n    owner: " + tom
                + "\n    owner-name: Tom\n    shown: X\n");

        assertThat(unlocks("smp").has(tom, "particles")).isTrue();
        assertThat(wings("smp").all()).hasSize(1);
        assertThat(folder.resolve("unlocks.yml")).doesNotExist();
    }

    @Test
    @DisplayName("sold on two servers at once: the second finds it bought already, so it can be given back")
    void boughtTwiceIsSeen() {
        UnlockBook smp = unlocks("smp");
        UnlockBook arena = unlocks("arena");
        assertThat(smp.add(tom, "particles")).isEqualTo(UnlockBook.Added.ADDED);
        assertThat(arena.add(tom, "particles")).as("arena had not heard yet").isEqualTo(UnlockBook.Added.ALREADY);
        assertThat(arena.has(tom, "particles")).isTrue();
    }
}
