package de.raindancer.modules.manhunt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.DynamicTest;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every {@code with…} changes its own component and nothing else.
 *
 * <h2>Why this exists at all</h2>
 * {@link ManhuntSettings} is a record of thirty-odd components and each {@code with…} rebuilds it
 * with a full positional argument list — which is the convention this reactor's settings records all
 * share, and which has exactly one failure mode: two components of the same type swapped in one of
 * those lists. The compiler cannot see it, every existing test still passes, and the symptom is a
 * setting that quietly changes a different setting. So the invariant is checked by reflection over
 * every component rather than by trusting thirty hand-written argument lists to stay in order —
 * adding a component to the record adds its case here for free.
 */
class ManhuntSettingsContractTest {

    @Test
    @DisplayName("every component has a with… of its own")
    void everyComponentHasAWither() {
        List<String> missing = new ArrayList<>();
        for (RecordComponent component : ManhuntSettings.class.getRecordComponents()) {
            if (witherFor(component) == null) {
                missing.add(component.getName());
            }
        }

        assertThat(missing).as("components with no with… method").isEmpty();
    }

    @TestFactory
    @DisplayName("a with… changes its own component and leaves every other one alone")
    List<DynamicTest> withersAreSurgical() {
        List<DynamicTest> cases = new ArrayList<>();
        for (RecordComponent component : ManhuntSettings.class.getRecordComponents()) {
            cases.add(DynamicTest.dynamicTest(component.getName(), () -> {
                Method wither = witherFor(component);
                assertThat(wither).as("with… for %s", component.getName()).isNotNull();

                ManhuntSettings before = ManhuntSettings.DEFAULTS;
                Object changed = somethingElse(component.getType(), component.getAccessor().invoke(before));
                ManhuntSettings after = (ManhuntSettings) wither.invoke(before, changed);

                assertThat(component.getAccessor().invoke(after))
                        .as("%s itself", component.getName())
                        .isEqualTo(changed);
                for (RecordComponent other : ManhuntSettings.class.getRecordComponents()) {
                    if (other.getName().equals(component.getName())) {
                        continue;
                    }
                    assertThat(other.getAccessor().invoke(after))
                            .as("%s must not move when %s is set", other.getName(), component.getName())
                            .isEqualTo(other.getAccessor().invoke(before));
                }
            }));
        }
        return cases;
    }

    private static Method witherFor(RecordComponent component) {
        String name = "with" + Character.toUpperCase(component.getName().charAt(0))
                + component.getName().substring(1);
        for (Method method : ManhuntSettings.class.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == component.getType()) {
                return method;
            }
        }
        return null;
    }

    /** A value of {@code type} that is definitely not {@code current}, so a no-op cannot pass. */
    private static Object somethingElse(Class<?> type, Object current) {
        if (type == boolean.class) {
            return !((boolean) current);
        }
        if (type == int.class) {
            return (int) current + 1;
        }
        if (type == long.class) {
            return (long) current + 1L;
        }
        if (type == double.class) {
            return (double) current + 1.0;
        }
        if (type == String.class) {
            return current + "-changed";
        }
        if (type.isEnum()) {
            Object[] constants = type.getEnumConstants();
            return constants[0].equals(current) ? constants[1] : constants[0];
        }
        throw new IllegalStateException("no distinct value known for " + type
                + " — teach this test about it rather than skipping the component");
    }
}
