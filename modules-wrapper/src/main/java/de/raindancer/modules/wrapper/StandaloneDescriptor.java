package de.raindancer.modules.wrapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The {@code paper-plugin.yml} a module needs to ship as a plugin of its own.
 *
 * <h2>Why this is generated rather than written</h2>
 * Because every way of getting it wrong fails at load time with a message naming something the author did not
 * write. Two have already happened in this workspace:
 *
 * <ul>
 *   <li>{@code depend: [RainsCore]} — the legacy {@code plugin.yml} spelling. In a {@code paper-plugin.yml} it
 *       declares nothing at all and is <b>silently ignored</b>: the plugin may load before RainsCore, and dies
 *       with a {@code NoClassDefFoundError} naming a class its author has never heard of.</li>
 *   <li>a missing {@code join-classpath} — the dependency is declared, the load order is right, and the classes
 *       still are not there, because Paper gives every plugin an isolated classloader.</li>
 * </ul>
 *
 * <p>Neither is visible by reading the file. So the wrapper writes it, and a test checks what it writes.
 */
public final class StandaloneDescriptor {

    /** Paper's own rule for a plugin name: no spaces, and nothing that would confuse a file path. */
    private static final Pattern PLUGIN_NAME = Pattern.compile("[A-Za-z0-9_.-]+");

    /** The oldest game this is known to work against. Not the version it is built with. */
    private static final String API_VERSION = "1.21";

    private final String name;
    private final String version;
    private final Map<String, Boolean> dependencies = new LinkedHashMap<>();

    private String description = "";
    private String author = "";

    private StandaloneDescriptor(String name, String version) {
        this.name = required(name, "name");
        this.version = required(version, "version");
        if (!PLUGIN_NAME.matcher(this.name).matches()) {
            throw new IllegalArgumentException(
                    "a plugin name is letters, digits, dots, dashes and underscores — '" + name + "' is not");
        }
        // Always, and first: a module without RainsCore is a module whose every class is missing.
        dependencies.put("RainsCore", true);
    }

    public static StandaloneDescriptor forPlugin(String name, String version) {
        return new StandaloneDescriptor(name, version);
    }

    public StandaloneDescriptor describedAs(String description) {
        this.description = description == null ? "" : description.strip();
        return this;
    }

    public StandaloneDescriptor by(String author) {
        this.author = author == null ? "" : author.strip();
        return this;
    }

    /**
     * Another plugin this one needs.
     *
     * <p>RainsCore is already declared and asking for it again changes nothing — rather than declaring it twice,
     * which produces a file Paper reads as one of them silently winning.
     */
    public StandaloneDescriptor dependingOn(String plugin, boolean required) {
        if (plugin != null && !plugin.isBlank()) {
            dependencies.putIfAbsent(plugin.strip(), required);
        }
        return this;
    }

    /** The file, as text. */
    public String render() {
        StringBuilder yaml = new StringBuilder();
        yaml.append("name: ").append(name).append('\n');
        yaml.append("version: '").append(version).append("'\n");
        yaml.append("main: ").append(ModulePlugin.class.getName()).append('\n');
        // Not optional. Paper fires the COMMANDS lifecycle event during bootstrap, so a plugin with no
        // bootstrapper never gets the chance to register one — silently, and the command simply does not exist.
        yaml.append("bootstrapper: ").append(ModuleBootstrap.class.getName()).append('\n');
        yaml.append("api-version: '").append(API_VERSION).append("'\n");
        if (!description.isEmpty()) {
            yaml.append("description: '").append(description.replace("'", "''")).append("'\n");
        }
        if (!author.isEmpty()) {
            yaml.append("author: ").append(author).append('\n');
        }

        yaml.append('\n');
        yaml.append("dependencies:\n");

        // Both phases, and the bootstrap one is not belt-and-braces. Paper runs bootstrap with its own
        // dependency tree and its own classpath, and the bootstrapper is where modules are first discovered —
        // it has to look, because commands are registered during bootstrap or they never exist at all. A
        // dependency declared only under `server:` is not on the classpath yet at that point, so every module
        // fails to link with a ClassNotFoundException naming a class the author never wrote, and the plugin
        // reports that it contains no modules. That is exactly what happened the first time this shipped.
        yaml.append("  bootstrap:\n");
        dependencies.forEach((plugin, required) -> declare(yaml, plugin, required));
        yaml.append("  server:\n");
        dependencies.forEach((plugin, required) -> declare(yaml, plugin, required));
        return yaml.toString();
    }

    private static void declare(StringBuilder yaml, String plugin, boolean required) {
        yaml.append("    ").append(plugin).append(":\n");
        yaml.append("      load: BEFORE\n");
        yaml.append("      required: ").append(required).append('\n');
        // The line that actually puts the classes on the classpath. Without it everything above is right
        // and the plugin still dies the moment it touches one of them.
        yaml.append("      join-classpath: true\n");
    }

    private static String required(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("a plugin needs a " + what);
        }
        return value.strip();
    }
}
