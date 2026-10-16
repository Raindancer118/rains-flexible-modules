package de.raindancer.modules.ctf;

import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Range;
import de.raindancer.modules.ctf.model.Mutator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Capture the Flag's settings")
class CtfSettingsTest {

    private final CtfSettings defaults = CtfSettings.DEFAULTS;

    @Test
    @DisplayName("every number's default lies inside its own range")
    void defaultsInRange() throws ReflectiveOperationException {
        List<String> outside = new ArrayList<>();
        for (RecordComponent component : CtfSettings.class.getRecordComponents()) {
            Range range = component.getAnnotation(Range.class);
            if (range == null) {
                continue;
            }
            Object value = component.getAccessor().invoke(defaults);
            double number = ((Number) value).doubleValue();
            if (number < range.min() || number > range.max()) {
                outside.add(component.getName() + " = " + value);
            }
        }
        assertThat(outside).isEmpty();
    }

    @Test
    @DisplayName("every key is unique and spelled in the file's way")
    void keys() {
        Set<String> seen = new HashSet<>();
        for (RecordComponent component : CtfSettings.class.getRecordComponents()) {
            Key key = component.getAnnotation(Key.class);
            assertThat(key).as(component.getName()).isNotNull();
            assertThat(key.value()).matches("[a-z0-9-]+");
            assertThat(seen.add(key.value())).as("duplicate " + key.value()).isTrue();
        }
    }

    @Test
    @DisplayName("the twists come up by name; nonsense in the list is left out")
    void mutators() {
        assertThat(defaults.allowedMutators()).hasSize(Mutator.values().length - 1).doesNotContain(Mutator.NONE);
    }

    @Test
    @DisplayName("sides are two to four, whatever the file says")
    void sides() {
        assertThat(defaults.sidesClamped()).isEqualTo(2);
        assertThat(defaults.maxPlayersClamped()).isGreaterThanOrEqualTo(defaults.minPlayers());
    }

    @Test
    @DisplayName("the defaults: a classic round, three captures, twelve minutes, building off")
    void spelledOut() {
        assertThat(defaults.capturesToWin()).isEqualTo(3);
        assertThat(defaults.timeLimit()).isEqualTo(12);
        assertThat(defaults.building()).isEqualTo(de.raindancer.modules.ctf.model.Building.NONE);
        assertThat(defaults.returnSeconds()).isEqualTo(30);
        assertThat(defaults.lobbyOnJoin()).as("never touches a shared server's players by default").isFalse();
        assertThat(defaults.entryFee()).isZero();
    }
}
