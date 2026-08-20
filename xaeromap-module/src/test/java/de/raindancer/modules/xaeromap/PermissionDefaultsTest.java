package de.raindancer.modules.xaeromap;

import de.raindancer.modules.xaeromap.util.PermissionNodes;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That each node defaults to what it is meant to.
 *
 * <p>Worth pinning because the failure is silent in both directions: a node meant for staff that
 * defaults to everybody hands the server's whole claim picture to anyone who asks for it, and a node
 * meant for everybody that defaults to operator is a command that quietly does nothing for the people
 * it exists for.
 */
class PermissionDefaultsTest {

    private final Map<String, Permission> declared = PermissionNodes.declared().stream()
            .collect(Collectors.toMap(Permission::getName, Function.identity()));

    @Test
    @DisplayName("refreshing your own map is for everybody")
    void refreshingIsOpen() {
        assertThat(declared.get(PermissionNodes.REFRESH).getDefault())
                .isEqualTo(PermissionDefault.TRUE);
    }

    @Test
    @DisplayName("the status page and resyncing everybody are for staff")
    void theAdminHalfIsNot() {
        assertThat(declared.get(PermissionNodes.ADMIN).getDefault())
                .isEqualTo(PermissionDefault.OP);
    }

    @Test
    @DisplayName("every node is under this plugin's own prefix")
    void nodesAreNamespaced() {
        assertThat(declared.keySet()).allMatch(node -> node.startsWith("rainsxaeromap."));
    }

    @Test
    @DisplayName("every declared node says what it is for")
    void nodesAreDescribed() {
        assertThat(declared.values()).allMatch(node -> !node.getDescription().isBlank());
    }
}
