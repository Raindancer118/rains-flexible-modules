package de.raindancer.modules.performance.service;

import de.raindancer.modules.performance.model.ClassIndex;
import org.bukkit.plugin.Plugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Fills a {@link ClassIndex} from the jars the server's plugins were loaded from. File I/O: off the server thread. */
public final class ClassIndexLoader {

    private ClassIndexLoader() {
    }

    /** @return how many plugins' jars could be read */
    public static int fill(ClassIndex index, Plugin[] plugins) {
        int read = 0;
        for (Plugin plugin : plugins) {
            try {
                Path jar = Path.of(plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
                if (!Files.isRegularFile(jar)) {
                    continue;
                }
                List<String> entries = new ArrayList<>();
                try (ZipFile zip = new ZipFile(jar.toFile())) {
                    zip.stream().map(ZipEntry::getName).forEach(entries::add);
                }
                index.add(plugin.getName(), entries);
                read++;
            } catch (Exception unreadable) {
                // A plugin from somewhere other than a jar (a dev setup): its time counts as the game's.
            }
        }
        return read;
    }
}
