package de.raindancer.modules.moderation.service;

import de.raindancer.modules.moderation.model.ModerationPermission;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who a report is announced to.
 *
 * <h2>The leak this exists to catch</h2>
 * A trial mod reporting a mod, or any staff member reporting another, used to name the subject in the
 * very staff-chat line meant to tell everybody <em>else</em> — because that line went to whoever held the
 * reports permission, and holding it does not stop being the subject of one. The whole point of a report
 * is that the person it is about does not find out from it.
 */
class ReportServiceTest {

    private static Player fakePlayer(UUID id, boolean holdsReportsPermission) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "hasPermission" -> holdsReportsPermission
                            && ModerationPermission.REPORTS.node().equals(args[0]);
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> id.hashCode();
                    case "toString" -> "a fake player";
                    default -> method.getReturnType().isPrimitive()
                            ? (method.getReturnType() == boolean.class ? false : 0)
                            : null;
                });
    }

    @Test
    @DisplayName("everybody holding the reports permission is told")
    void ordinaryStaffAreTold() {
        Player mod = fakePlayer(UUID.randomUUID(), true);
        Player bystander = fakePlayer(UUID.randomUUID(), false);

        List<Player> told = ReportService.whoToTell(List.of(mod, bystander), UUID.randomUUID());

        assertThat(told).containsExactly(mod);
    }

    @Test
    @DisplayName("the subject is never told, even holding the reports permission themselves")
    void theSubjectIsNeverTold() {
        UUID subjectId = UUID.randomUUID();
        Player subjectWhoIsAlsoStaff = fakePlayer(subjectId, true);
        Player otherStaff = fakePlayer(UUID.randomUUID(), true);

        List<Player> told = ReportService.whoToTell(
                List.of(subjectWhoIsAlsoStaff, otherStaff), subjectId);

        assertThat(told)
                .as("a mod reporting another mod must not out itself in the very line meant to tell "
                        + "everyone else")
                .containsExactly(otherStaff);
    }

    @Test
    @DisplayName("a report about somebody who is not even online tells everybody as normal")
    void anOfflineSubjectExcludesNobody() {
        Player mod = fakePlayer(UUID.randomUUID(), true);

        List<Player> told = ReportService.whoToTell(Set.of(mod), UUID.randomUUID());

        assertThat(told).containsExactly(mod);
    }

    @Test
    @DisplayName("nobody holding the permission means nobody is told")
    void nobodyStaffMeansNobodyTold() {
        Player bystander = fakePlayer(UUID.randomUUID(), false);

        List<Player> told = ReportService.whoToTell(List.of(bystander), UUID.randomUUID());

        assertThat(told).isEmpty();
    }
}
