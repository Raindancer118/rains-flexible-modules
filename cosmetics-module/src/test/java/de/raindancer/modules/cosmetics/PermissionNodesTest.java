package de.raindancer.modules.cosmetics;

import de.raindancer.core.ui.text.NameStyle;
import de.raindancer.modules.cosmetics.model.Preset;
import de.raindancer.modules.cosmetics.util.PermissionNodes;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The defaults, asserted: an unregistered node is operators-only, and FALSE means nobody at all —
 * neither mistake shows until a real player tries.
 */
class PermissionNodesTest {

    private static Map<String, PermissionDefault> defaults(List<Permission> nodes) {
        return nodes.stream().collect(Collectors.toMap(Permission::getName, Permission::getDefault));
    }

    @Test
    @DisplayName("everybody may colour their name; obfuscated, every preset and staff tools are operators'")
    void defaults() {
        Map<String, PermissionDefault> declared = defaults(PermissionNodes.declared());

        assertThat(declared).containsEntry(PermissionNodes.USE, PermissionDefault.TRUE)
                .containsEntry(PermissionNodes.NAME_COLOUR, PermissionDefault.TRUE)
                .containsEntry(PermissionNodes.NAME_GRADIENT, PermissionDefault.TRUE)
                .containsEntry(PermissionNodes.NAME_ANY_COLOUR, PermissionDefault.TRUE)
                .containsEntry(PermissionNodes.NAME_ANIMATED, PermissionDefault.TRUE)
                .containsEntry("rainscosmetics.name.decoration.bold", PermissionDefault.TRUE)
                .containsEntry("rainscosmetics.name.decoration.obfuscated", PermissionDefault.OP)
                .containsEntry(PermissionNodes.PRESET_ALL, PermissionDefault.OP)
                .containsEntry(PermissionNodes.PARTICLES, PermissionDefault.TRUE)
                .containsEntry(PermissionNodes.CLEAR, PermissionDefault.TRUE)
                .containsEntry(PermissionNodes.CLEAR_OTHERS, PermissionDefault.OP)
                .containsEntry(PermissionNodes.ADMIN, PermissionDefault.OP);
        assertThat(declared).doesNotContainValue(PermissionDefault.FALSE);
    }

    @Test
    @DisplayName("only a restricted preset gets a node")
    void presetNodes() {
        List<Permission> nodes = PermissionNodes.declaredFor(List.of(
                new Preset("open", "Open", NameStyle.NONE, false),
                new Preset("vip", "VIP", NameStyle.NONE, true)));
        assertThat(defaults(nodes)).containsOnlyKeys("rainscosmetics.preset.vip")
                .containsEntry("rainscosmetics.preset.vip", PermissionDefault.OP);
    }
}
