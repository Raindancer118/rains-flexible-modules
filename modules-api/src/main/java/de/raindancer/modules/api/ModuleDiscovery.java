package de.raindancer.modules.api;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Finds the modules a jar brought with it.
 *
 * <p>{@code ServiceLoader}, so a module is added to a build by adding a dependency and nothing else: no
 * list of class names to keep in step, and no host that has to be edited every time a module appears.
 * A module declares itself in {@code META-INF/services/de.raindancer.modules.api.FlexModule}.
 *
 * <h2>Why the loop looks like that</h2>
 * A bad service entry throws {@link ServiceConfigurationError} from {@code hasNext()}, not from
 * {@code next()}, so the ordinary for-each over a {@code ServiceLoader} cannot survive one — the first
 * broken module ends discovery and every module listed after it silently disappears. Catching around both
 * calls and continuing is the only way to load five modules when the sixth is broken.
 *
 * <p>Two ways an entry goes bad on a real server, both of which are collected rather than thrown: a class
 * name left behind after a rename, and a module whose constructor throws.
 */
public final class ModuleDiscovery {

    /**
     * A hard cap on iterations. Continuing after an error relies on the loader having consumed the bad
     * entry, which it does — but a loop that depends on someone else's internals gets a backstop rather
     * than trust, because the failure mode without one is a server that hangs at startup.
     */
    private static final int MOST_ENTRIES_WORTH_READING = 1_000;

    private ModuleDiscovery() {
    }

    /**
     * @param modules  the ones that could be built, in the order the classpath lists them
     * @param problems one line per entry that could not be
     */
    public record Discovered(List<FlexModule> modules, List<String> problems) {
        public Discovered {
            modules = List.copyOf(modules);
            problems = List.copyOf(problems);
        }
    }

    public static Discovered onClasspath(ClassLoader loader) {
        List<FlexModule> found = new ArrayList<>();
        List<String> problems = new ArrayList<>();

        Iterator<FlexModule> modules = ServiceLoader.load(FlexModule.class, loader).iterator();
        for (int read = 0; read < MOST_ENTRIES_WORTH_READING; read++) {
            try {
                if (!modules.hasNext()) {
                    break;
                }
                found.add(modules.next());
            } catch (ServiceConfigurationError broken) {
                problems.add("a module on the classpath could not be loaded: " + message(broken));
            } catch (Throwable caught) {
                problems.add("a module on the classpath threw while being built: " + message(caught));
            }
        }
        return new Discovered(found, problems);
    }

    /** The cause carries the useful half when a constructor threw, so both are reported. */
    private static String message(Throwable caught) {
        String own = caught.getMessage() == null ? caught.getClass().getName() : caught.getMessage();
        Throwable cause = caught.getCause();
        return cause == null ? own : own + " (" + cause + ")";
    }
}
