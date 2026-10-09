package de.raindancer.modules.performance.store;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.performance.model.Report;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.function.IntSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reports in memory, newest first, and one text file each — {@code report-<n>.txt}, which outlives a
 * restart for whoever wants to read back what happened at night. Writing is file I/O: call {@link #add}
 * off the server thread.
 */
public final class ReportStore {

    private static final LogChannel log = Log.of("performance");
    private static final Pattern FILE = Pattern.compile("report-(\\d+)\\.txt");

    private final Path folder;
    private final IntSupplier keep;
    private final Deque<Report> reports = new ArrayDeque<>();
    private int next;

    public ReportStore(Path folder, IntSupplier keep) {
        this.folder = folder;
        this.keep = keep;
        this.next = highestOnDisk() + 1;
    }

    public synchronized int nextNumber() {
        return next++;
    }

    public synchronized void add(Report report) {
        reports.addFirst(report);
        try {
            Files.createDirectories(folder);
            Files.write(folder.resolve("report-" + report.number() + ".txt"), report.lines());
        } catch (IOException unwritable) {
            log.warn("Report #{} could not be written to {}: {}", report.number(), folder, unwritable.getMessage());
        }
        prune();
    }

    public synchronized Optional<Report> get(int number) {
        return reports.stream().filter(report -> report.number() == number).findFirst();
    }

    public synchronized List<Report> recent() {
        return List.copyOf(reports);
    }

    private void prune() {
        int kept = Math.max(1, keep.getAsInt());
        while (reports.size() > kept) {
            reports.removeLast();
        }
        try (Stream<Path> files = Files.list(folder)) {
            List<Integer> numbers = files.map(file -> FILE.matcher(file.getFileName().toString()))
                    .filter(Matcher::matches).map(found -> Integer.parseInt(found.group(1)))
                    .sorted((a, b) -> b - a).toList();
            for (int number : numbers.subList(Math.min(kept, numbers.size()), numbers.size())) {
                Files.deleteIfExists(folder.resolve("report-" + number + ".txt"));
            }
        } catch (IOException unreadable) {
            log.warn("Old reports in {} could not be tidied: {}", folder, unreadable.getMessage());
        }
    }

    private int highestOnDisk() {
        if (!Files.isDirectory(folder)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.map(file -> FILE.matcher(file.getFileName().toString())).filter(Matcher::matches)
                    .mapToInt(found -> Integer.parseInt(found.group(1))).max().orElse(0);
        } catch (IOException unreadable) {
            return 0;
        }
    }
}
