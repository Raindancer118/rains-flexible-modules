package de.raindancer.modules.economy.service;

import de.raindancer.modules.economy.SupplySettings;

import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

/** Copies a settings record with some components changed by name — the record has forty of them. */
final class SupplySettingsBuilder {

    private SupplySettingsBuilder() {
    }

    static SupplySettings from(SupplySettings base, UnaryOperator<Map<String, Object>> change) {
        try {
            RecordComponent[] components = SupplySettings.class.getRecordComponents();
            Map<String, Object> values = new LinkedHashMap<>();
            for (RecordComponent component : components) {
                values.put(component.getName(), component.getAccessor().invoke(base));
            }
            Map<String, Object> changed = change.apply(values);
            Class<?>[] types = new Class<?>[components.length];
            Object[] arguments = new Object[components.length];
            for (int index = 0; index < components.length; index++) {
                types[index] = components[index].getType();
                if (!changed.containsKey(components[index].getName())) {
                    throw new IllegalArgumentException("no component " + components[index].getName());
                }
                arguments[index] = changed.get(components[index].getName());
            }
            if (changed.size() != components.length) {
                throw new IllegalArgumentException("unknown component in " + changed.keySet());
            }
            return SupplySettings.class.getDeclaredConstructor(types).newInstance(arguments);
        } catch (ReflectiveOperationException broken) {
            throw new IllegalStateException(broken);
        }
    }
}
