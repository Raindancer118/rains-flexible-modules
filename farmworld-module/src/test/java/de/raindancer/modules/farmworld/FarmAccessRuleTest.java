package de.raindancer.modules.farmworld;

import de.raindancer.modules.farmworld.rules.FarmAccessRule;
import de.raindancer.modules.farmworld.util.PermissionNodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who may enter which farm world.
 *
 * <p>Every case here is answered without a server, which is the point of the rule taking a predicate: "the donor
 * farm world is open to everybody" is not something to find out by asking somebody to try it.
 */
class FarmAccessRuleTest {

    private final FarmAccessRule rule = new FarmAccessRule();

    /** Somebody holding exactly these nodes and nothing else. */
    private static Predicate<String> holding(String... nodes) {
        Set<String> held = Set.of(nodes);
        return held::contains;
    }

    /** What an ordinary player has, since both nodes default to true. */
    private static Predicate<String> anOrdinaryPlayer(String farmWorld) {
        return holding(PermissionNodes.USE, PermissionNodes.forWorld(farmWorld));
    }

    @Nested
    @DisplayName("entering one")
    class Using {

        @Test
        @DisplayName("an ordinary player may enter an ordinary farm world")
        void theUsualCase() {
            assertThat(rule.mayUse("mining", anOrdinaryPlayer("mining"))).isTrue();
        }

        @Test
        @DisplayName("the per-world node closes one farm world without closing the others")
        void oneWorldClosed() {
            // The whole reason the node is per farm world. A server with a donor world and two open ones needs
            // exactly this, and a single node could only express "all of them or none".
            Predicate<String> player = holding(PermissionNodes.USE,
                    PermissionNodes.forWorld("mining"));

            assertThat(rule.mayUse("mining", player)).isTrue();
            assertThat(rule.mayUse("donor", player)).isFalse();
        }

        @Test
        @DisplayName("taking the general node away closes all of them")
        void allClosed() {
            Predicate<String> player = holding(PermissionNodes.forWorld("mining"));

            assertThat(rule.mayUse("mining", player))
                    .as("this is the node a server takes away from a group to switch farm worlds off")
                    .isFalse();
        }

        @Test
        @DisplayName("an admin reaches everything, including a farm world nothing grants")
        void anAdminGetsIn() {
            // Somebody has to be able to go and look at a farm world that is misbehaving, and an admin who
            // cannot enter the one they are fixing fixes it by regenerating it — which throws away everybody
            // else's work to answer a question they could have answered by walking around.
            Predicate<String> admin = holding(PermissionNodes.MANAGE);

            assertThat(rule.mayUse("donor", admin)).isTrue();
            assertThat(rule.mayUse("anything-at-all", admin)).isTrue();
        }

        @Test
        @DisplayName("nobody is nobody, and a nameless farm world is refused rather than opened")
        void theEdgesAreRefusals() {
            assertThat(rule.mayUse("mining", null)).isFalse();
            assertThat(rule.mayUse(null, anOrdinaryPlayer("mining"))).isFalse();
            assertThat(rule.mayUse("  ", anOrdinaryPlayer("mining"))).isFalse();
        }
    }

    @Nested
    @DisplayName("seeing one")
    class Seeing {

        @Test
        @DisplayName("a farm world somebody may not enter is still on their list")
        void nothingIsHidden() {
            // Deliberately wider than mayUse, and the opposite of the warps module. A staff warp's name is worth
            // keeping quiet; a farm world's is not — it is one of two or three places the whole server talks
            // about, so somebody who hears about the donor world every day and cannot see it on their own list
            // learns nothing except that their list is wrong.
            Predicate<String> player = holding(PermissionNodes.USE);

            assertThat(rule.maySee("donor", player)).isTrue();
            assertThat(rule.mayUse("donor", player)).isFalse();
        }

        @Test
        @DisplayName("somebody with no farm world access at all is shown none")
        void aWallIsNotAList() {
            Predicate<String> player = holding("something.else");

            assertThat(rule.maySee("mining", player)).isFalse();
        }
    }

    @Nested
    @DisplayName("why it was refused")
    class Refusals {

        @Test
        @DisplayName("nothing to say when they may go")
        void silentWhenAllowed() {
            assertThat(rule.refusalKey("mining", anOrdinaryPlayer("mining"))).isNull();
        }

        @Test
        @DisplayName("two reasons, because they are two different things to do about it")
        void twoReasonsAndNotOne() {
            // Nothing, and ask whoever hands out the ranks. A single "you may not go there" covering both is the
            // message that produces a ticket.
            assertThat(rule.refusalKey("donor", holding(PermissionNodes.USE)))
                    .isEqualTo("farmworlds.refused.this-one");
            assertThat(rule.refusalKey("mining", holding()))
                    .isEqualTo("farmworlds.refused.at-all");
        }

        @Test
        @DisplayName("the refusal and the permission never disagree")
        void oneAnswerAndNotTwo() {
            // A screen greys a button from refusalKey and the click asks mayUse. Two answers to the same
            // question is a menu that offers something and then refuses it, which is a button people press four
            // more times.
            for (Predicate<String> who : java.util.List.of(
                    holding(), holding(PermissionNodes.USE), holding(PermissionNodes.MANAGE),
                    anOrdinaryPlayer("mining"))) {
                assertThat(rule.refusalKey("mining", who) == null)
                        .as("refusalKey and mayUse have to agree for every holder")
                        .isEqualTo(rule.mayUse("mining", who));
            }
        }
    }

    @Nested
    @DisplayName("changing one")
    class Managing {

        @Test
        @DisplayName("only the managing node, and being let into a farm world is not it")
        void enteringIsNotChanging() {
            assertThat(rule.mayManage(holding(PermissionNodes.MANAGE))).isTrue();
            assertThat(rule.mayManage(anOrdinaryPlayer("mining")))
                    .as("everything behind this node deletes worlds")
                    .isFalse();
            assertThat(rule.mayManage(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("the nodes themselves")
    class Nodes {

        @Test
        @DisplayName("a farm world's node is worked out from its name and stored nowhere")
        void derivedRatherThanStored() {
            // Core's WorldSet has no field for a permission, so storing one would mean a second file of the
            // module's own beside Core's farmworlds.yml: two records of which farm worlds exist, and a farm
            // world renamed in one of them and not the other.
            assertThat(PermissionNodes.forWorld("mining"))
                    .isEqualTo("rainsfarmworlds.world.mining");
            assertThat(PermissionNodes.forWorld("MINING"))
                    .as("permissions are lower case, and a node with a capital in it is one nobody matches")
                    .isEqualTo("rainsfarmworlds.world.mining");
        }

        @Test
        @DisplayName("a name with nothing usable in it never becomes a bare prefix")
        void neverABarePrefix() {
            // A bare prefix is a node nobody can be granted, so the farm world would be reachable by nobody at
            // all and there would be nothing on screen to say why.
            assertThat(PermissionNodes.forWorld("!!!")).isEqualTo(PermissionNodes.MANAGE);
            assertThat(PermissionNodes.forWorld("")).isEqualTo(PermissionNodes.MANAGE);
            assertThat(PermissionNodes.forWorld(null)).isEqualTo(PermissionNodes.MANAGE);
        }

        @Test
        @DisplayName("every farm world's node is declared, so it shows up in a permissions plugin")
        void theNodesAreDeclared() {
            var declared = PermissionNodes.declared(java.util.List.of("mining", "donor"));

            assertThat(declared).extracting(org.bukkit.permissions.Permission::getName)
                    .contains(PermissionNodes.USE, PermissionNodes.MANAGE,
                            "rainsfarmworlds.world.mining", "rainsfarmworlds.world.donor");
        }

        @Test
        @DisplayName("both the general node and each world's default to being allowed")
        void openByDefault() {
            // Default false would be a farm world that silently exists and nobody can reach, which is reported
            // as the plugin being broken rather than as a permission being ungranted.
            var declared = PermissionNodes.declared(java.util.List.of("mining"));

            assertThat(declared).filteredOn(node -> !node.getName().equals(PermissionNodes.MANAGE))
                    .allMatch(node -> node.getDefault() == org.bukkit.permissions.PermissionDefault.TRUE);
            assertThat(declared).filteredOn(node -> node.getName().equals(PermissionNodes.MANAGE))
                    .allMatch(node -> node.getDefault() == org.bukkit.permissions.PermissionDefault.OP);
        }
    }
}
