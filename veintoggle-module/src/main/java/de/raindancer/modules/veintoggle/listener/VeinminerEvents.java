package de.raindancer.modules.veintoggle.listener;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads Veinminer's own events through their public getters, by name — the module does not compile
 * against Veinminer (see {@code VeinRule}). Anything that is not there reads as null or empty, so a
 * Veinminer that renamed a getter costs the undo, never the break.
 */
final class VeinminerEvents {

    private static final Map<String, Method> GETTERS = new ConcurrentHashMap<>();
    private static final Method MISSING;

    static {
        try {
            MISSING = Object.class.getMethod("hashCode");
        } catch (NoSuchMethodException impossible) {
            throw new ExceptionInInitializerError(impossible);
        }
    }

    private VeinminerEvents() {
    }

    /** The block the vein started from. */
    static Location sourceOf(Event event) {
        return read(event, "getSourceLocation") instanceof Location location ? location : null;
    }

    static Player playerOf(Event event) {
        return read(event, "getPlayer") instanceof Player player ? player : null;
    }

    /** What Veinminer is about to drop for the block, as the list it will drop from. */
    static List<ItemStack> itemsOf(Event event) {
        if (!(read(event, "getItems") instanceof List<?> items)) {
            return List.of();
        }
        return items.stream()
                .filter(ItemStack.class::isInstance)
                .map(ItemStack.class::cast)
                .filter(stack -> !stack.isEmpty())
                .map(ItemStack::clone)
                .toList();
    }

    private static Object read(Event event, String getter) {
        Method method = GETTERS.computeIfAbsent(event.getClass().getName() + "#" + getter, key -> {
            try {
                return event.getClass().getMethod(getter);
            } catch (NoSuchMethodException gone) {
                return MISSING;
            }
        });
        if (method == MISSING) {
            return null;
        }
        try {
            return method.invoke(event);
        } catch (ReflectiveOperationException | RuntimeException unreadable) {
            return null;
        }
    }
}
