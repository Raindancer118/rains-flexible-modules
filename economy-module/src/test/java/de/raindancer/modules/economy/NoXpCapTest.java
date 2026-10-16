package de.raindancer.modules.economy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class NoXpCapTest {

    @Test
    @DisplayName("selling experience has no daily limit: no setting is left that could switch one on")
    void noDailyXpLimit() {
        assertThat(Arrays.stream(SupplySettings.class.getRecordComponents())
                .map(each -> each.getAnnotation(de.raindancer.core.data.settings.Key.class).value()))
                .noneMatch(key -> key.startsWith("xp."));
        assertThat(Arrays.stream(SupplySettings.class.getRecordComponents()).map(RecordComponent::getName))
                .doesNotContain("xpDailyLimit", "xpSellDailyMost");
    }
}
