package de.raindancer.modules.claims;

import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Topic;
import de.raindancer.modules.claims.screen.AdminMenu;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Every money setting sits in a topic the admin screen has a door to. */
class MoneyReachabilityTest {

    @Test
    @DisplayName("each door on the admin screen opens a topic that exists")
    void doorsOpenRealTopics() {
        List<String> topics = Arrays.stream(ClaimSettings.class.getAnnotation(Settings.class).topics())
                .map(Topic::path).toList();
        assertThat(topics).containsAll(AdminMenu.MONEY_TOPICS);
    }

    @Test
    @DisplayName("every upkeep, entry-fee and creation-cost setting lives under a door")
    void everyMoneySettingIsBehindADoor() {
        for (RecordComponent component : ClaimSettings.class.getRecordComponents()) {
            Key key = component.getAnnotation(Key.class);
            In in = component.getAnnotation(In.class);
            if (key == null || in == null) {
                continue;
            }
            String name = key.value();
            if (name.startsWith("upkeep.") || name.startsWith("entry-fee.") || name.startsWith("creation-cost.")) {
                assertThat(AdminMenu.MONEY_TOPICS).as(name).contains(in.value());
            }
        }
    }

    @Test
    @DisplayName("upkeep is off by default and the operators pay like everybody")
    void defaultsChangeNothing() {
        assertThat(ClaimSettings.DEFAULTS.upkeepEnabled()).isFalse();
        assertThat(ClaimSettings.DEFAULTS.upkeepOperatorsPayPercent()).isEqualTo(100.0D);
        assertThat(ClaimSettings.DEFAULTS.upkeepPerClaimAmount().isPositive()).isFalse();
    }
}
