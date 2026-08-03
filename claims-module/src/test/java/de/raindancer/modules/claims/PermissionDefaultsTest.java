package de.raindancer.modules.claims;

import de.raindancer.modules.claims.util.ClaimPermissions;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That an ordinary player can still use {@code /claim}.
 *
 * <h2>The defect this exists because of</h2>
 * The plugin this replaced declared its permissions in {@code paper-plugin.yml}, with
 * {@code rec.use: default: true}. The rebuild is a <em>module</em> — it has no descriptor of its own,
 * because it may be hosted inside somebody else's plugin — and nothing registered them instead.
 *
 * <p>Bukkit resolves an unregistered permission against {@code Permission.DEFAULT_PERMISSION}, which is
 * {@code OP}. So {@code hasPermission("rec.use")} answered <b>false</b> for every ordinary player, and
 * {@code /claim} silently became an operator command. It builds, it starts, it logs nothing, and the only
 * person who can test it is the one person for whom it works.
 *
 * <p>Which is why this is a test and not a line in a descriptor: the defaults have to live somewhere the
 * compiler and the suite can see, and "hosted or standalone" must not change the answer.
 */
class PermissionDefaultsTest {

    private final List<Permission> declared = ClaimPermissions.declared();

    @Test
    @DisplayName("the list is not empty, so this cannot pass by declaring nothing")
    void theScanIsNotVacuous() {
        assertThat(declared).isNotEmpty();
    }

    @Test
    @DisplayName("an ordinary player may use /claim")
    void everybodyMayClaim() {
        // The whole point. `default: true` in the old descriptor, and the reason a player could make a
        // claim on a server where they were not an operator.
        assertThat(defaultOf("rec.use"))
                .as("without this /claim is an operator command and nothing says so")
                .isEqualTo(PermissionDefault.TRUE);
    }

    @Test
    @DisplayName("administration is for operators")
    void administrationIsForOperators() {
        assertThat(defaultOf("rec.admin")).isEqualTo(PermissionDefault.OP);
        assertThat(defaultOf("rec.bypass")).isEqualTo(PermissionDefault.OP);
        assertThat(defaultOf("rec.admin.nolimit")).isEqualTo(PermissionDefault.OP);
        assertThat(defaultOf("rec.admin.nocost")).isEqualTo(PermissionDefault.OP);
        assertThat(defaultOf("rec.admin.zonebypass")).isEqualTo(PermissionDefault.OP);
    }

    @Test
    @DisplayName("entry fees are not waived for anybody by default")
    void feesAreNotWaived() {
        // Carried over deliberately, with the reasoning from the old descriptor: an admin walking around
        // the server should pay a claim's toll like everybody else, or switch the protection bypass on
        // for as long as they are actually working. Granting it silently to operators is how an owner
        // stops noticing that their own entry fees do nothing.
        assertThat(defaultOf("rec.admin.nofee")).isEqualTo(PermissionDefault.FALSE);
        assertThat(defaultOf("rec.maxclaims.unlimited")).isEqualTo(PermissionDefault.FALSE);
    }

    @Test
    @DisplayName("rec.admin implies the everyday nodes, as it did before")
    void adminImpliesTheRest() {
        // A child list, because the old descriptor had one: granting rec.admin alone should not leave
        // somebody unable to run /claim.
        Map<String, Boolean> children = declared.stream()
                .filter(permission -> permission.getName().equals("rec.admin"))
                .findFirst().orElseThrow()
                .getChildren();

        assertThat(children).containsKeys("rec.use", "rec.bypass", "rec.admin.nolimit",
                "rec.admin.nocost", "rec.admin.zonebypass");
        assertThat(children.values()).containsOnly(true);
        assertThat(children)
                .as("nofee was deliberately not a child of rec.admin, and still is not")
                .doesNotContainKey("rec.admin.nofee");
    }

    @Test
    @DisplayName("every node the commands actually check is declared")
    void nothingTheCodeUsesIsMissing() {
        // The failure this catches is the original one, one node at a time: something gates on a string
        // that nothing declares, and it reads as false for every player.
        List<String> names = declared.stream().map(Permission::getName).toList();

        assertThat(names).contains("rec.use", "rec.admin", "rec.admin.nolimit", "rec.admin.nocost",
                "rec.admin.nofee", "rec.admin.zonebypass", "rec.maxclaims.unlimited");
    }

    @Test
    @DisplayName("no node is declared twice")
    void noDuplicates() {
        List<String> names = new ArrayList<>(declared.stream().map(Permission::getName).toList());

        assertThat(names).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("every node says what it is for")
    void everyNodeIsDescribed() {
        assertThat(declared).allSatisfy(permission ->
                assertThat(permission.getDescription()).isNotBlank());
    }

    private PermissionDefault defaultOf(String node) {
        return declared.stream()
                .filter(permission -> permission.getName().equals(node))
                .findFirst()
                .map(Permission::getDefault)
                .orElseThrow(() -> new AssertionError(node + " is not declared at all, so it reads as "
                        + "false for every player who is not an operator"));
    }

    @Test
    @DisplayName("the descriptions read as sentences somebody could put in a wiki")
    void theDescriptionsAreUseful() {
        assertThat(declared.stream()
                .map(Permission::getDescription)
                .collect(Collectors.joining(" ")))
                .contains("claim");
    }
}
