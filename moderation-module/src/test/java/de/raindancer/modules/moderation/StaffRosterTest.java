package de.raindancer.modules.moderation;

import de.raindancer.core.platform.permission.Grants;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.model.StaffRank;
import de.raindancer.modules.moderation.store.StaffRoster;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who is staff, at what rank, and whether their permissions still match it.
 *
 * <h2>Why the rank is stored when the permissions are the real power</h2>
 * Because the rank is the thing a person says out loud — "she's a moderator" — and the nodes are the
 * thing the server acts on. Keeping only the nodes means nobody can answer "what rank is she?" without
 * comparing thirteen booleans against four presets; keeping only the rank means the per-person toggles
 * cannot exist.
 *
 * <p>So both, with the roster owning the label and Core's {@link Grants} owning the power — and this
 * class able to say when the two have drifted apart, which is exactly what a moderator with one extra
 * node is.
 */
class StaffRosterTest {

    private final UUID ayla = UUID.randomUUID();
    private final UUID bram = UUID.randomUUID();

    private StaffRoster rosterIn(Path folder) {
        return new StaffRoster(folder, new Grants(folder));
    }

    @Nested
    @DisplayName("promoting")
    class Promoting {

        @Test
        @DisplayName("promoting somebody records the rank and grants its nodes")
        void promoted(@TempDir Path folder) {
            Grants grants = new Grants(folder);
            StaffRoster roster = new StaffRoster(folder, grants);

            roster.promote(ayla, StaffRank.HELPER);

            assertThat(roster.rankOf(ayla)).contains(StaffRank.HELPER);
            assertThat(roster.isStaff(ayla)).isTrue();
            assertThat(grants.has(ayla, ModerationPermission.MUTE.node())).isTrue();
            assertThat(grants.has(ayla, ModerationPermission.BAN.node())).isFalse();
        }

        @Test
        @DisplayName("promoting again replaces the old nodes rather than adding to them")
        void promotingReplaces(@TempDir Path folder) {
            // A demotion is a promotion downwards, and the version that only added nodes would have
            // made every demotion a no-op — the dangerous direction, since nobody notices.
            Grants grants = new Grants(folder);
            StaffRoster roster = new StaffRoster(folder, grants);
            roster.promote(ayla, StaffRank.MODERATOR);

            roster.promote(ayla, StaffRank.TRIAL);

            assertThat(roster.rankOf(ayla)).contains(StaffRank.TRIAL);
            assertThat(grants.has(ayla, ModerationPermission.BAN.node())).isFalse();
            assertThat(grants.has(ayla, ModerationPermission.WARN.node())).isTrue();
        }

        @Test
        @DisplayName("an admin gets the claim bypasses; a moderator does not")
        void theClaimsHalf(@TempDir Path folder) {
            Grants grants = new Grants(folder);
            StaffRoster roster = new StaffRoster(folder, grants);

            roster.promote(ayla, StaffRank.ADMIN);
            roster.promote(bram, StaffRank.MODERATOR);

            assertThat(grants.has(ayla, "rec.admin.nolimit")).isTrue();
            assertThat(grants.has(bram, "rec.admin.nolimit")).isFalse();
            assertThat(grants.has(bram, "rec.admin"))
                    .as("a moderator still needs to act on a claim while moderating")
                    .isTrue();
        }

        @Test
        @DisplayName("a null player or rank changes nothing")
        void nulls(@TempDir Path folder) {
            StaffRoster roster = rosterIn(folder);

            assertThat(roster.promote(null, StaffRank.HELPER)).isFalse();
            assertThat(roster.promote(ayla, null)).isFalse();
            assertThat(roster.size()).isZero();
        }
    }

    @Nested
    @DisplayName("demoting")
    class Demoting {

        @Test
        @DisplayName("demoting takes the rank and every node with it")
        void demoted(@TempDir Path folder) {
            Grants grants = new Grants(folder);
            StaffRoster roster = new StaffRoster(folder, grants);
            roster.promote(ayla, StaffRank.ADMIN);

            assertThat(roster.demote(ayla)).isTrue();

            assertThat(roster.rankOf(ayla)).isEmpty();
            assertThat(roster.isStaff(ayla)).isFalse();
            assertThat(grants.nodesFor(ayla))
                    .as("a demoted admin who keeps immunity is an account nobody can act on and "
                            + "nobody meant to protect")
                    .isEmpty();
        }

        @Test
        @DisplayName("demoting somebody who is not staff says so rather than pretending")
        void notStaff(@TempDir Path folder) {
            StaffRoster roster = rosterIn(folder);

            assertThat(roster.demote(ayla)).isFalse();
            assertThat(roster.demote(null)).isFalse();
        }

        @Test
        @DisplayName("demoting one person leaves everybody else alone")
        void onlyThem(@TempDir Path folder) {
            Grants grants = new Grants(folder);
            StaffRoster roster = new StaffRoster(folder, grants);
            roster.promote(ayla, StaffRank.HELPER);
            roster.promote(bram, StaffRank.HELPER);

            roster.demote(ayla);

            assertThat(roster.isStaff(bram)).isTrue();
            assertThat(grants.has(bram, ModerationPermission.MUTE.node())).isTrue();
        }
    }

    @Nested
    @DisplayName("individual toggles")
    class Toggles {

        @Test
        @DisplayName("one node can be given without changing the rank")
        void givenOne(@TempDir Path folder) {
            // The exception the presets exist to make rare, not impossible: one helper who is trusted
            // to ban, without inventing a fifth tier for her.
            Grants grants = new Grants(folder);
            StaffRoster roster = new StaffRoster(folder, grants);
            roster.promote(ayla, StaffRank.HELPER);

            assertThat(roster.toggle(ayla, ModerationPermission.BAN.node())).isTrue();

            assertThat(grants.has(ayla, ModerationPermission.BAN.node())).isTrue();
            assertThat(roster.rankOf(ayla)).contains(StaffRank.HELPER);
        }

        @Test
        @DisplayName("one node can be taken away without changing the rank")
        void takenOne(@TempDir Path folder) {
            Grants grants = new Grants(folder);
            StaffRoster roster = new StaffRoster(folder, grants);
            roster.promote(ayla, StaffRank.MODERATOR);

            assertThat(roster.toggle(ayla, ModerationPermission.BAN.node())).isFalse();

            assertThat(grants.has(ayla, ModerationPermission.BAN.node())).isFalse();
            assertThat(roster.rankOf(ayla)).contains(StaffRank.MODERATOR);
        }

        @Test
        @DisplayName("a toggle shows up as drift from the preset")
        void drift(@TempDir Path folder) {
            // What the screen puts on the button: "Moderator, and one extra". Without it, somebody
            // hand-granted a node three months ago is indistinguishable from a plain moderator.
            Grants grants = new Grants(folder);
            StaffRoster roster = new StaffRoster(folder, grants);
            roster.promote(ayla, StaffRank.HELPER);

            assertThat(roster.matchesPreset(ayla)).isTrue();
            assertThat(roster.extraNodes(ayla)).isEmpty();
            assertThat(roster.missingNodes(ayla)).isEmpty();

            roster.toggle(ayla, ModerationPermission.BAN.node());

            assertThat(roster.matchesPreset(ayla)).isFalse();
            assertThat(roster.extraNodes(ayla)).containsExactly(ModerationPermission.BAN.node());
            assertThat(roster.missingNodes(ayla)).isEmpty();
        }

        @Test
        @DisplayName("a node taken away shows up as missing")
        void missing(@TempDir Path folder) {
            Grants grants = new Grants(folder);
            StaffRoster roster = new StaffRoster(folder, grants);
            roster.promote(ayla, StaffRank.MODERATOR);

            roster.toggle(ayla, ModerationPermission.BAN.node());

            assertThat(roster.missingNodes(ayla)).containsExactly(ModerationPermission.BAN.node());
            assertThat(roster.extraNodes(ayla)).isEmpty();
        }

        @Test
        @DisplayName("re-applying the preset puts a drifted moderator back")
        void reapplied(@TempDir Path folder) {
            Grants grants = new Grants(folder);
            StaffRoster roster = new StaffRoster(folder, grants);
            roster.promote(ayla, StaffRank.HELPER);
            roster.toggle(ayla, ModerationPermission.BAN.node());

            roster.reapplyPreset(ayla);

            assertThat(roster.matchesPreset(ayla)).isTrue();
            assertThat(grants.has(ayla, ModerationPermission.BAN.node())).isFalse();
        }

        @Test
        @DisplayName("somebody who is not staff cannot be toggled")
        void notStaff(@TempDir Path folder) {
            // Otherwise a node lands on a player with no rank, and nothing in the GUI shows it.
            Grants grants = new Grants(folder);
            StaffRoster roster = new StaffRoster(folder, grants);

            assertThat(roster.toggle(ayla, ModerationPermission.BAN.node())).isFalse();
            assertThat(grants.has(ayla, ModerationPermission.BAN.node())).isFalse();
        }

        @Test
        @DisplayName("a node no rank grants is refused, so nothing can be scattered by a typo")
        void onlyGrantableNodes(@TempDir Path folder) {
            Grants grants = new Grants(folder);
            StaffRoster roster = new StaffRoster(folder, grants);
            roster.promote(ayla, StaffRank.HELPER);

            assertThat(roster.toggle(ayla, "minecraft.command.op")).isFalse();
            assertThat(grants.has(ayla, "minecraft.command.op")).isFalse();
        }
    }

    @Nested
    @DisplayName("across a restart")
    class Persisting {

        @Test
        @DisplayName("the roster and the grants both survive")
        void aRoundTrip(@TempDir Path folder) {
            Grants grants = new Grants(folder);
            StaffRoster first = new StaffRoster(folder, grants);
            first.promote(ayla, StaffRank.MODERATOR);
            first.flush();
            grants.flush();

            Grants freshGrants = new Grants(folder);
            freshGrants.load();
            StaffRoster afterRestart = new StaffRoster(folder, freshGrants);
            afterRestart.load();

            assertThat(afterRestart.rankOf(ayla)).contains(StaffRank.MODERATOR);
            assertThat(freshGrants.has(ayla, ModerationPermission.BAN.node())).isTrue();
        }

        @Test
        @DisplayName("a demotion does not come back")
        void demotionPersists(@TempDir Path folder) {
            Grants grants = new Grants(folder);
            StaffRoster first = new StaffRoster(folder, grants);
            first.promote(ayla, StaffRank.ADMIN);
            first.flush();
            first.demote(ayla);
            first.flush();

            StaffRoster afterRestart = new StaffRoster(folder, grants);
            afterRestart.load();

            assertThat(afterRestart.isStaff(ayla)).isFalse();
        }

        @Test
        @DisplayName("nothing on disk is nobody staff rather than a failure")
        void nothingYet(@TempDir Path folder) {
            StaffRoster roster = rosterIn(folder);
            roster.load();

            assertThat(roster.size()).isZero();
        }

        @Test
        @DisplayName("an unknown rank name is skipped rather than throwing the whole roster away")
        void anUnknownRank(@TempDir Path folder) throws Exception {
            StaffRoster first = rosterIn(folder);
            first.promote(ayla, StaffRank.HELPER);
            first.flush();

            String yaml = java.nio.file.Files.readString(first.file());
            java.nio.file.Files.writeString(first.file(),
                    yaml + System.lineSeparator() + "  " + bram + ": archduke" + System.lineSeparator());

            StaffRoster afterRestart = rosterIn(folder);
            afterRestart.load();

            assertThat(afterRestart.isStaff(ayla)).isTrue();
            assertThat(afterRestart.isStaff(bram)).isFalse();
        }
    }

    @Test
    @DisplayName("everybody of a rank can be listed, worst-first for a staff page")
    void listing(@TempDir Path folder) {
        StaffRoster roster = rosterIn(folder);
        roster.promote(ayla, StaffRank.ADMIN);
        roster.promote(bram, StaffRank.TRIAL);

        assertThat(roster.everybody()).containsExactlyInAnyOrder(ayla, bram);
        assertThat(roster.ofRank(StaffRank.ADMIN)).containsExactly(ayla);
        assertThat(roster.ofRank(StaffRank.TRIAL)).containsExactly(bram);
        assertThat(roster.size()).isEqualTo(2);
    }
}
