package de.raindancer.modules.roles.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.store.Documents;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.testkit.TestDatabases;
import de.raindancer.modules.roles.RolesModule;
import de.raindancer.modules.roles.model.Choice;
import de.raindancer.modules.roles.model.Ownership;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** What role a player took, owns and what roles there are: the same on every server of the network. */
@Tag("database")
class BooksNetworkTest {

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

    private ChoiceBook choices(String id) {
        ChoiceBook book = new ChoiceBook(servers().network(servers().server(id), "choices.yml"));
        book.load();
        book.watch();
        return book;
    }

    private OwnedBook owned(String id) {
        OwnedBook book = new OwnedBook(servers().network(servers().server(id), "owned.yml"));
        book.load();
        book.watch();
        return book;
    }

    @AfterEach
    void close() {
        if (network != null) {
            network.close();
        }
    }

    @Test
    @DisplayName("a role taken on one server is the player's on the other, and clearing it clears it there")
    void choiceFollowsThePlayer() {
        ChoiceBook smp = choices("smp");
        ChoiceBook arena = choices("arena");

        smp.put(new Choice(tom, "cook", 10L, 10L));
        network.heard();
        assertThat(arena.of(tom)).contains(new Choice(tom, "cook", 10L, 10L));

        arena.clear(tom);
        network.heard();
        assertThat(smp.of(tom)).isEmpty();
    }

    @Test
    @DisplayName("two players choosing at once on two servers both keep their role")
    void bothChoicesKept() {
        ChoiceBook smp = choices("smp");
        ChoiceBook arena = choices("arena");

        smp.put(new Choice(tom, "cook", 10L));
        arena.put(new Choice(ana, "mage", 20L));
        network.heard();

        assertThat(smp.of(ana)).map(Choice::role).contains("mage");
        assertThat(arena.of(tom)).map(Choice::role).contains("cook");
        assertThat(smp.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("the other server is told whose role changed, and only those")
    void changedElsewhereIsReported() {
        ChoiceBook smp = choices("smp");
        ChoiceBook arena = choices("arena");
        smp.put(new Choice(tom, "cook", 10L));
        network.heard();
        Set<UUID> told = new HashSet<>();
        arena.changedElsewhere(told::addAll);

        smp.put(new Choice(ana, "mage", 20L));
        network.heard();

        assertThat(told).containsExactly(ana);
    }

    @Test
    @DisplayName("a purchase on one server is owned on the other; two purchases at once are both kept")
    void ownershipFollows() {
        OwnedBook smp = owned("smp");
        OwnedBook arena = owned("arena");

        smp.put(new Ownership(tom, "cook", Ownership.Kind.BOUGHT, 0));
        arena.put(new Ownership(tom, "mage", Ownership.Kind.RENTED, 777));
        network.heard();

        assertThat(smp.of(tom)).hasSize(2);
        assertThat(arena.of(tom, "cook")).isPresent();

        arena.remove(tom, "cook");
        network.heard();
        assertThat(smp.of(tom, "cook")).isEmpty();
        assertThat(smp.of(tom, "mage")).isPresent();
    }

    @Test
    @DisplayName("documents that cannot be read keep what is held and refuse every change")
    void unreadableIsNotWrittenOver() {
        Database database = TestDatabases.open(plugins.resolve("lost.db"), Documents.SCHEMA);
        Documents documents = new Documents(database, null, plugins, "smp", System::currentTimeMillis);
        ChoiceBook choices = new ChoiceBook(new YamlStore(plugins.resolve("RainsRoles").resolve("choices.yml"),
                documents, Documents.Scope.NETWORK));
        OwnedBook owned = new OwnedBook(new YamlStore(plugins.resolve("RainsRoles").resolve("owned.yml"),
                documents, Documents.Scope.NETWORK));
        choices.load();
        owned.load();
        choices.put(new Choice(tom, "cook", 10L));
        owned.put(new Ownership(tom, "cook", Ownership.Kind.BOUGHT, 0));

        database.close();
        choices.load();
        owned.load();

        assertThat(choices.readable()).isFalse();
        assertThat(owned.readable()).isFalse();
        assertThat(choices.of(tom)).isPresent();
        assertThat(owned.of(tom, "cook")).isPresent();
        assertThat(choices.put(new Choice(ana, "mage", 20L))).isFalse();
        assertThat(owned.put(new Ownership(ana, "mage", Ownership.Kind.BOUGHT, 0))).isFalse();
        assertThat(choices.of(ana)).isEmpty();
    }

    @Test
    @DisplayName("the roles an owner edits are the network's; the shipped ones are written once")
    void catalogueIsShared() {
        Documents smpDocs = servers().server("smp");
        RoleCatalogue smp = new RoleCatalogue(servers().network(smpDocs, "roles.yml"),
                () -> RolesModule.class.getResourceAsStream("roles.yml"));
        smp.reload();
        smp.watch();
        YamlStore smpStore = servers().network(smpDocs, "roles.yml");
        RoleCatalogue arena = new RoleCatalogue(servers().network(servers().server("arena"), "roles.yml"),
                () -> RolesModule.class.getResourceAsStream("roles.yml"));
        arena.reload();
        arena.watch();
        assertThat(arena.all()).hasSameSizeAs(smp.all());

        String edited = smpStore.readText().orElseThrow().replace("roles:", "roles:\n  boss:\n    title: Boss\n    price: 100");
        smpStore.writeText(edited);
        network.heard();

        assertThat(arena.find("boss")).isPresent();
    }

    @Test
    @DisplayName("a roles.yml that cannot be read leaves the roles held alone")
    void unreadableCatalogueKeepsRoles() {
        Database database = TestDatabases.open(plugins.resolve("lost.db"), Documents.SCHEMA);
        Documents documents = new Documents(database, null, plugins, "smp", System::currentTimeMillis);
        RoleCatalogue catalogue = new RoleCatalogue(
                new YamlStore(plugins.resolve("RainsRoles").resolve("roles.yml"), documents, Documents.Scope.NETWORK),
                () -> RolesModule.class.getResourceAsStream("roles.yml"));
        int before = catalogue.reload();
        assertThat(before).isPositive();

        database.close();

        assertThat(catalogue.reload()).isEqualTo(before);
        assertThat(catalogue.problems()).isNotEmpty();
    }

    @Test
    @DisplayName("1.x files are brought into the network's documents on first use")
    void oneXFilesAreImported() throws Exception {
        Path folder = Files.createDirectories(plugins.resolve("RainsRoles"));
        Files.writeString(folder.resolve("choices.yml"), "choices:\n  " + tom + ":\n    role: cook\n    chosen-at: 5\n");
        Files.writeString(folder.resolve("owned.yml"), "owned:\n  " + tom + ":\n    cook:\n      kind: rented\n      due: 9\n");

        assertThat(choices("smp").of(tom)).map(Choice::role).contains("cook");
        assertThat(owned("smp").of(tom, "cook")).map(Ownership::dueAt).contains(9L);
        assertThat(folder.resolve("choices.yml")).doesNotExist();
    }
}
