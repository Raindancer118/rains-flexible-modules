package de.raindancer.modules.claims;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Claim names belong to their owner, not to the server.
 * <p>
 * Five people each calling a claim "home" is the obvious thing for them to want, so the name index has
 * to hold duplicates and a typed name has to be resolved rather than looked up. The interesting cases
 * are the ambiguous ones: whose "home" did the person mean, and when should the plugin refuse to guess.
 */
class ClaimNameResolutionTest {

    private static final UUID WORLD = UUID.randomUUID();

    private final UUID ada = UUID.randomUUID();
    private final UUID grace = UUID.randomUUID();
    private final UUID linus = UUID.randomUUID();

    private ClaimRegistry registry;
    private ClaimNames names;

    @BeforeEach
    void setUp() {
        registry = new ClaimRegistry();
        // A map rather than a name cache of its own: Core already knows every player it has seen, so
        // ClaimNames takes the lookup as a function and this test simply is one.
        Map<UUID, String> known = Map.of(ada, "Ada", grace, "Grace", linus, "Linus");
        names = new ClaimNames(registry, known::get);
    }

    private Claim claim(String name, UUID owner, int offset) {
        ClaimShape shape = ClaimShape.rectangle(offset, offset, offset + 4, offset + 4, 0, 128);
        Claim claim = new Claim(UUID.randomUUID(), name, WORLD, "world", shape, owner);
        registry.add(claim);
        return claim;
    }

    @Test
    @DisplayName("several people may each have a claim called home")
    void theSameNameIsFreeForEverybody() {
        claim("home", ada, 0);
        claim("home", grace, 100);
        claim("home", linus, 200);

        assertThat(registry.allByName("home")).hasSize(3);
        assertThat(registry.nameTaken("home", ada)).isTrue();
        // Not taken for somebody who does not have one yet, however many others do.
        assertThat(registry.nameTaken("home", UUID.randomUUID())).isFalse();
    }

    @Test
    @DisplayName("one owner still cannot have the same name twice")
    void oneOwnerCannotRepeatAName() {
        claim("home", ada, 0);
        assertThat(registry.nameTaken("home", ada)).isTrue();
        assertThat(names.available("home", ada)).isFalse();
        assertThat(names.available("home", grace)).isTrue();
    }

    @Test
    @DisplayName("a name only one claim holds resolves for anybody")
    void anUnsharedNameJustResolves() {
        Claim workshop = claim("workshop", ada, 0);

        assertThat(names.resolve("workshop", grace).claim()).contains(workshop);
        assertThat(names.resolve("WORKSHOP", null).claim()).contains(workshop);
    }

    @Test
    @DisplayName("a bare shared name means the asker's own")
    void yourOwnWins() {
        Claim adasHome = claim("home", ada, 0);
        Claim gracesHome = claim("home", grace, 100);

        assertThat(names.resolve("home", ada).claim()).contains(adasHome);
        assertThat(names.resolve("home", grace).claim()).contains(gracesHome);
    }

    @Test
    @DisplayName("a shared name from a bystander is ambiguous, not a guess")
    void abystanderGetsAskedWhichOne() {
        claim("home", ada, 0);
        claim("home", grace, 100);

        var resolution = names.resolve("home", linus);
        assertThat(resolution.claim()).isEmpty();
        assertThat(resolution.isAmbiguous()).isTrue();
        assertThat(names.describeCandidates(resolution.candidates()))
                .contains("Ada/home").contains("Grace/home");
    }

    @Test
    @DisplayName("owner/name picks exactly one, whoever is asking")
    void qualifyingResolvesOutright() {
        Claim adasHome = claim("home", ada, 0);
        Claim gracesHome = claim("home", grace, 100);

        assertThat(names.resolve("Ada/home", linus).claim()).contains(adasHome);
        assertThat(names.resolve("grace/HOME", ada).claim()).contains(gracesHome);
    }

    @Test
    @DisplayName("an unknown name or owner is unknown, not ambiguous")
    void nonsenseIsUnknown() {
        claim("home", ada, 0);
        claim("home", grace, 100);

        assertThat(names.resolve("shed", ada).claim()).isEmpty();
        assertThat(names.resolve("shed", ada).isAmbiguous()).isFalse();
        assertThat(names.resolve("Linus/home", ada).claim()).isEmpty();
        assertThat(names.resolve("Linus/home", ada).isAmbiguous()).isFalse();
    }

    @Test
    @DisplayName("names are written plainly unless somebody else shares them")
    void displayOnlyQualifiesWhenItHasTo() {
        Claim workshop = claim("workshop", ada, 0);
        Claim adasHome = claim("home", ada, 100);
        claim("home", grace, 200);

        assertThat(names.display(workshop, grace)).isEqualTo("workshop");
        // Your own claim reads as you named it, even when others share the word.
        assertThat(names.display(adasHome, ada)).isEqualTo("home");
        assertThat(names.display(adasHome, grace)).isEqualTo("Ada/home");
        // A list that mixes everybody's claims qualifies regardless of who reads it.
        assertThat(names.listed(adasHome)).isEqualTo("Ada/home");
        assertThat(names.listed(workshop)).isEqualTo("workshop");
    }

    @Test
    @DisplayName("renaming keeps the index honest, and frees the old name")
    void renamingReindexes() {
        Claim adasHome = claim("home", ada, 0);
        claim("home", grace, 100);

        registry.rename(adasHome, "cottage");

        assertThat(registry.allByName("home")).hasSize(1);
        assertThat(registry.allByName("cottage")).containsExactly(adasHome);
        assertThat(names.resolve("home", ada).claim()).isPresent();
        assertThat(names.listed(adasHome)).isEqualTo("cottage");
        assertThat(registry.nameTaken("home", ada)).isFalse();
    }

    @Test
    @DisplayName("removing a claim takes its name with it")
    void removingUnindexesTheName() {
        Claim adasHome = claim("home", ada, 0);
        claim("home", grace, 100);

        registry.remove(adasHome);

        assertThat(registry.allByName("home")).hasSize(1);
        assertThat(registry.byName("home")).isPresent();
        assertThat(registry.nameTaken("home", ada)).isFalse();
    }

    @Test
    @DisplayName("a co-owned claim blocks the name for each of its owners")
    void coOwnersShareTheName() {
        ClaimShape shape = ClaimShape.rectangle(0, 0, 4, 4, 0, 128);
        Claim shared = new Claim(UUID.randomUUID(), "base", WORLD, "world", shape, ada);
        shared.addOwner(grace);
        registry.add(shared);

        assertThat(registry.nameTaken("base", ada)).isTrue();
        assertThat(registry.nameTaken("base", grace)).isTrue();
        assertThat(registry.nameTaken("base", linus)).isFalse();
    }

    @Test
    @DisplayName("byName only answers when the name belongs to one claim")
    void byNameStaysUnambiguous() {
        claim("home", ada, 0);
        assertThat(registry.byName("home")).isPresent();

        claim("home", grace, 100);
        assertThat(registry.byName("home")).isEmpty();
        assertThat(registry.allByName("home")).hasSize(2);
    }

    @Test
    @DisplayName("prose gets the possessive form, always with the owner in front")
    void proseReadsPossessively() {
        Claim adasHome = claim("home", ada, 0);
        Claim workshop = claim("workshop", linus, 100);

        assertThat(names.possessive(adasHome)).isEqualTo("Ada's home");
        // A name already ending in s takes the bare apostrophe.
        assertThat(names.possessive(workshop)).isEqualTo("Linus' workshop");
        // Unlike the display form, this qualifies even when nobody shares the name.
        assertThat(names.listed(workshop)).isEqualTo("workshop");
    }

    @Test
    @DisplayName("a claim with no owner left still has something to call itself")
    void anOwnerlessClaimIsStillPrintable() {
        ClaimShape shape = ClaimShape.rectangle(0, 0, 4, 4, 0, 128);
        Claim orphan = new Claim(UUID.randomUUID(), "ruin", WORLD, "world", shape, null);
        registry.add(orphan);

        assertThat(names.primaryOwner(orphan)).isEqualTo("nobody");
        assertThat(names.allOwners(orphan)).isEqualTo("nobody");
        assertThat(names.possessive(orphan)).isEqualTo("nobody's ruin");
        assertThat(new ClaimPoint(0, 0)).isNotNull();
    }
}
