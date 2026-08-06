package de.raindancer.modules.hungergames;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One settings value changed, for a test that cares about exactly one.
 *
 * <h2>Why this exists rather than more {@code withX} methods</h2>
 * {@link HungerGamesSettings} is a record with ninety-eight components, and each {@code withX} on it is a
 * ninety-eight-argument call written out by hand. Five of them earn their place because production code
 * uses them. Adding seven more so that a test can say "the same settings but with a silly deathmatch
 * target" would put four hundred lines of positional arguments into the shipped jar to serve the test
 * suite — and every one of those is a place where two adjacent {@code int}s can be swapped and nothing
 * will ever notice.
 *
 * <p>So the tests build their variants by name instead. Slower, entirely, and it runs in microseconds on
 * a record that is constructed a few hundred times in the whole suite.
 *
 * <h2>Why it cannot silently do nothing</h2>
 * The obvious failure of a by-name helper is a typo'd component name producing the unmodified defaults —
 * a test that then passes while checking nothing. So an unknown name throws, loudly, naming what it was
 * given.
 */
public final class Tweak {

    private Tweak() {
    }

    /**
     * The settings with the named components replaced.
     *
     * @param from       what to start from, usually {@code HungerGamesSettings.DEFAULTS}
     * @param nameValues component name, value, component name, value, …
     */
    public static HungerGamesSettings of(HungerGamesSettings from, Object... nameValues) {
        if (nameValues.length % 2 != 0) {
            throw new IllegalArgumentException("names and values have to come in pairs");
        }
        Map<String, Object> changes = new LinkedHashMap<>();
        for (int at = 0; at < nameValues.length; at += 2) {
            changes.put(String.valueOf(nameValues[at]), nameValues[at + 1]);
        }

        RecordComponent[] components = HungerGamesSettings.class.getRecordComponents();
        Object[] arguments = new Object[components.length];
        Class<?>[] types = new Class<?>[components.length];

        for (int at = 0; at < components.length; at++) {
            RecordComponent component = components[at];
            types[at] = component.getType();
            arguments[at] = changes.containsKey(component.getName())
                    ? changes.remove(component.getName())
                    : read(component, from);
        }

        if (!changes.isEmpty()) {
            // A typo here would otherwise produce the unmodified defaults, and the test asserting that
            // something is reported would fail for a reason nowhere near the mistake.
            throw new IllegalArgumentException("HungerGamesSettings has no component(s) named "
                    + changes.keySet() + " — it has " + components.length + ", and the name is the "
                    + "record component's, not the config key's");
        }

        try {
            return HungerGamesSettings.class.getDeclaredConstructor(types).newInstance(arguments);
        } catch (NoSuchMethodException | InstantiationException | IllegalAccessException notBuildable) {
            throw new AssertionError("could not rebuild HungerGamesSettings", notBuildable);
        } catch (InvocationTargetException refused) {
            // The record's own compact constructor said no — a clamp or a validation. That is a real
            // answer to the test's question, not a fault in this helper.
            throw new IllegalArgumentException(refused.getCause().getMessage(), refused.getCause());
        }
    }

    private static Object read(RecordComponent component, HungerGamesSettings from) {
        try {
            return component.getAccessor().invoke(from);
        } catch (IllegalAccessException | InvocationTargetException unreadable) {
            throw new AssertionError("could not read " + component.getName(), unreadable);
        }
    }
}
