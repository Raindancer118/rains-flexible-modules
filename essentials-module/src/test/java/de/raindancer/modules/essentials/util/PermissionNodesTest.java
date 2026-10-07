package de.raindancer.modules.essentials.util;

import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionNodesTest {

    private static Map<String, PermissionDefault> declared() {
        return PermissionNodes.declared().stream()
                .collect(Collectors.toMap(Permission::getName, Permission::getDefault));
    }

    @Test
    @DisplayName("everything that changes items or other people's names is operators' by default")
    void powerfulNodesAreOperators() {
        assertThat(declared())
                .containsEntry(PermissionNodes.ENCHANT, PermissionDefault.OP)
                .containsEntry(PermissionNodes.ENCHANT_BEYOND_MAX, PermissionDefault.OP)
                .containsEntry(PermissionNodes.ENCHANT_ANY_ITEM, PermissionDefault.OP)
                .containsEntry(PermissionNodes.ENCHANT_OTHERS, PermissionDefault.OP)
                .containsEntry(PermissionNodes.REPAIR, PermissionDefault.OP)
                .containsEntry(PermissionNodes.REPAIR_ALL, PermissionDefault.OP)
                .containsEntry(PermissionNodes.REPAIR_OTHERS, PermissionDefault.OP)
                .containsEntry(PermissionNodes.NICK_OTHERS, PermissionDefault.OP)
                .containsEntry(PermissionNodes.NICK_BYPASS_BLOCKLIST, PermissionDefault.OP);
    }

    @Test
    @DisplayName("nothing is FALSE, which would mean nobody at all")
    void nothingIsFalse() {
        assertThat(declared()).doesNotContainValue(PermissionDefault.FALSE);
    }

    @Test
    @DisplayName("every constant is declared, so none is left to default to operators by accident")
    void everyConstantIsDeclared() throws IllegalAccessException {
        for (var field : PermissionNodes.class.getFields()) {
            if (field.getType() == String.class) {
                assertThat(declared()).containsKey((String) field.get(null));
            }
        }
    }
}
