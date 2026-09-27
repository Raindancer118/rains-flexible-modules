package de.raindancer.modules.manhunt;

import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every {@code @EventHandler} in this module listens on an event Bukkit can actually register.
 *
 * <p>Found on a live server: a handler for the abstract {@code PlayerBucketEvent} compiles, passes every
 * unit test (they call the method directly), and makes Bukkit refuse the <em>whole listener</em> with
 * "Unable to find handler list" — the module failed to start. Bukkit looks for a static
 * {@code getHandlerList()} on the event class or a superclass; this does the same walk.
 */
@DisplayName("every event handler is registrable")
class EveryHandlerIsRegistrableTest {

    @Test
    void everyHandlerHasAHandlerList() throws IOException, ClassNotFoundException {
        Path classes = Path.of("target/classes");
        List<String> broken = new ArrayList<>();
        try (Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                String name = classes.relativize(file).toString()
                        .replace('/', '.').replaceAll("\\.class$", "");
                Class<?> type = Class.forName(name, false, getClass().getClassLoader());
                if (!Listener.class.isAssignableFrom(type)) {
                    continue;
                }
                for (Method method : type.getDeclaredMethods()) {
                    if (!method.isAnnotationPresent(EventHandler.class) || method.getParameterCount() != 1) {
                        continue;
                    }
                    Class<?> event = method.getParameterTypes()[0];
                    if (!hasHandlerList(event)) {
                        broken.add(type.getSimpleName() + "." + method.getName() + "(" + event.getSimpleName() + ")");
                    }
                }
            }
        }
        assertThat(broken).as("handlers Bukkit would refuse to register").isEmpty();
    }

    private static boolean hasHandlerList(Class<?> event) {
        for (Class<?> c = event; c != null && Event.class.isAssignableFrom(c); c = c.getSuperclass()) {
            try {
                Method list = c.getDeclaredMethod("getHandlerList");
                if (Modifier.isStatic(list.getModifiers())) {
                    return true;
                }
            } catch (NoSuchMethodException none) {
                // keep walking up, like Bukkit does
            }
        }
        return false;
    }
}
