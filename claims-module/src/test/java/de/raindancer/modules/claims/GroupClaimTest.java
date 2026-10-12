package de.raindancer.modules.claims;

import de.raindancer.core.social.team.Groups;
import de.raindancer.core.world.protection.LandAction;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimAdminPermission;
import de.raindancer.modules.claims.model.ClaimShape;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A claim a guild owns: its leader owns it, its officers manage it, its members are trusted — and leaving ends it. */
class GroupClaimTest {

    private static final UUID FOUNDER = UUID.randomUUID();
    private static final UUID LEADER = UUID.randomUUID();
    private static final UUID OFFICER = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID STRANGER = UUID.randomUUID();
    private final Set<UUID> members = new HashSet<>(Set.of(LEADER, OFFICER, MEMBER, FOUNDER));
    private Claim claim;

    @BeforeEach
    void setUp() {
        Groups.register("test", new Groups.Source() {
            @Override
            public Optional<Groups.Group> group(String id) {
                return id.equals("guild:elves") ? Optional.of(new Groups.Group("guild:elves", "The Elves", "ELF", LEADER,
                        Set.of(LEADER, OFFICER), members)) : Optional.empty();
            }

            @Override
            public List<Groups.Group> of(UUID player) {
                return group("guild:elves").filter(group -> group.isMember(player)).map(List::of).orElse(List.of());
            }
        });
        claim = new Claim(UUID.randomUUID(), "Elvenhome", UUID.randomUUID(), "world",
                ClaimShape.rectangle(0, 0, 10, 10, -64, 320), FOUNDER);
    }

    @AfterEach
    void tearDown() {
        Groups.clear();
    }

    @Test
    @DisplayName("without a group nothing changes")
    void plain() {
        assertThat(claim.isOwner(LEADER)).isFalse();
        assertThat(claim.member(MEMBER)).isEmpty();
        assertThat(claim.group()).isEmpty();
    }

    @Test
    @DisplayName("given to a guild: the leader owns it, officers manage it, members build in it, strangers do not")
    void guild() {
        claim.setGroup("guild:elves");
        assertThat(claim.isOwner(LEADER)).isTrue();
        assertThat(claim.isOwner(FOUNDER)).as("the founder still owns it too").isTrue();
        assertThat(claim.member(OFFICER)).hasValueSatisfying(officer -> {
            assertThat(officer.has(ClaimAdminPermission.MANAGE_MEMBERS)).isTrue();
            assertThat(officer.permissions()).contains(LandAction.BUILD, LandAction.CONTAINERS);
        });
        assertThat(claim.member(MEMBER)).hasValueSatisfying(member -> {
            assertThat(member.permissions()).contains(LandAction.BUILD, LandAction.BREAK, LandAction.DOORS);
            assertThat(member.has(ClaimAdminPermission.MANAGE_MEMBERS)).isFalse();
        });
        assertThat(claim.member(STRANGER)).isEmpty();
        assertThat(claim.members()).as("nobody is written into the claim's own list").isEmpty();
    }

    @Test
    @DisplayName("leaving the guild ends the rights at once; an explicit trust still wins")
    void leaving() {
        claim.setGroup("guild:elves");
        members.remove(MEMBER);
        assertThat(claim.member(MEMBER)).isEmpty();
        claim.memberOrCreate(MEMBER).permissions().add(LandAction.DOORS);
        assertThat(claim.member(MEMBER).orElseThrow().permissions()).containsExactly(LandAction.DOORS);
    }

    @Test
    @DisplayName("the guild's own permissions can be narrowed; with its plugin gone the claim is just the founder's")
    void narrowedAndGone() {
        claim.setGroup("guild:elves");
        claim.groupPermissions().clear();
        claim.groupPermissions().add(LandAction.DOORS);
        assertThat(claim.member(MEMBER).orElseThrow().permissions()).containsExactly(LandAction.DOORS);
        Groups.clear();
        assertThat(claim.isOwner(LEADER)).isFalse();
        assertThat(claim.member(MEMBER)).isEmpty();
        assertThat(claim.isOwner(FOUNDER)).isTrue();
    }
}
