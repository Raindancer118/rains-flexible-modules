package de.raindancer.modules.performance.store;

import de.raindancer.core.data.store.DocumentFolder;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.performance.model.Report;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.function.IntSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reports in memory, newest first, and one text document each — {@code report-<n>.txt}, which outlives a
 * restart for whoever wants to read back what happened at night. Writing is database I/O: call {@link #add}
 * off the server thread.
 */
public final class ReportStore {

    private static final LogChannel log = Log.of("performance");
    private static final Pattern FILE = Pattern.compile("report-(\\d+)\\.txt");

    private final DocumentFolder folder;
    private final IntSupplier keep;
    private final Deque<Report> reports = new ArrayDeque<>();
    private int next;

    public ReportStore(Path folder, IntSupplier keep) {
        this(DocumentFolder.of(folder), keep);
    }

    public ReportStore(DocumentFolder folder, IntSupplier keep) {
        this.folder = folder;
        this.keep = keep;
        this.next = numbersStored().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
    }

    public synchronized int nextNumber() {
        return next++;
    }

    public synchronized void add(Report report) {
        reports.addFirst(report);
        if (!folder.store(nameOf(report.number())).writeText(String.join("\n", report.lines()) + "\n")) {
            log.warn("Report #{} could not be stored.", report.number());
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
        List<Integer> numbers = numbersStored();
        for (int number : numbers.subList(0, Math.max(0, numbers.size() - kept))) {
            folder.delete(nameOf(number));
        }
    }

    private static String nameOf(int number) {
        return "report-" + number + ".txt";
    }

    /** The numbers of the stored reports, lowest first. */
    private List<Integer> numbersStored() {
        return folder.names(".txt").stream().map(name -> FILE.matcher(name)).filter(Matcher::matches)
                .map(found -> Integer.parseInt(found.group(1))).sorted().toList();
    }
}
