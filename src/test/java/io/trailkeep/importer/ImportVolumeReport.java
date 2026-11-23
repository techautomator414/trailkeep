// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.hibernate.stat.QueryStatistics;
import org.hibernate.stat.Statistics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The importer volume run's table (WP-30 part b): one row per command, with its time, rows per second, peak heap and
 * Hibernate's counts, and notes after the table (each command's most frequent queries, the digests). Written to {@code
 * target/perf}.
 */
final class ImportVolumeReport {

    private static final Logger LOG = LoggerFactory.getLogger(ImportVolumeReport.class);
    private static final long MIB = 1024 * 1024;

    private final String title;
    private final String fileName;
    private final List<String> rows = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();

    ImportVolumeReport(String title, String fileName) {
        this.title = title;
        this.fileName = fileName;
    }

    /**
     * One command's row, and its most frequent queries as a note.
     *
     * @param command the command
     * @param rowCount the rows it handled, or 0
     * @param runner nanoseconds from its context's start to its end: the command itself
     * @param wall nanoseconds from the call to its end, context start-up included
     * @param heap the heap while it ran
     * @param statistics its context's Hibernate statistics
     */
    void command(String command, long rowCount, long runner, long wall, Heap.Peak heap, Statistics statistics) {
        rows.add(String.format(
                Locale.ROOT,
                "| `%s` | %,d | %s | %s | %s | %,d | %,d | %,d | %,d |",
                command,
                rowCount,
                seconds(runner),
                rowCount == 0 ? "–" : String.format(Locale.ROOT, "%,d", Math.round(rowCount / (runner / 1e9))),
                seconds(wall - runner),
                heap.used() / MIB,
                heap.afterGc() / MIB,
                statistics.getPrepareStatementCount(),
                statistics.getTransactionCount()));
        notes.add("`" + command + "`, most frequent queries (executions, total ms): " + topQueries(statistics));
    }

    void note(String note) {
        notes.add(note);
    }

    void write() throws IOException {
        List<String> report = new ArrayList<>();
        report.add("# " + title);
        report.add("");
        report.add(String.format(
                Locale.ROOT,
                "Corpus: %,d people, %,d cases, %,d notes, %,d files, %,d history rows (scale %s). Java %s, %d CPUs,"
                        + " max heap %,d MiB.",
                ImportVolumeCorpus.PEOPLE,
                ImportVolumeCorpus.CASES,
                ImportVolumeCorpus.NOTES,
                ImportVolumeCorpus.FILES,
                ImportVolumeCorpus.EVENTS,
                ImportVolumeCorpus.SCALE,
                System.getProperty("java.version"),
                Runtime.getRuntime().availableProcessors(),
                Runtime.getRuntime().maxMemory() / MIB));
        report.add("");
        report.add("| Command | Rows | Command s | Rows/s | Context start s | Peak heap MiB | Peak after GC MiB"
                + " | JPA statements | JPA transactions |");
        report.add("|---|---:|---:|---:|---:|---:|---:|---:|---:|");
        report.addAll(rows);
        report.add("");
        notes.forEach(note -> report.add("- " + note));
        Path out = Path.of("target", "perf", fileName);
        Files.createDirectories(Objects.requireNonNull(out.getParent()));
        Files.write(out, report, StandardCharsets.UTF_8);
        report.forEach(line -> LOG.info("{}", line));
    }

    static String seconds(long nanos) {
        return String.format(Locale.ROOT, "%.1f", nanos / 1e9);
    }

    private static String topQueries(Statistics statistics) {
        record Counted(String query, long executions, long millis) {}
        return Arrays.stream(statistics.getQueries())
                .map(query -> {
                    QueryStatistics each = statistics.getQueryStatistics(query);
                    return new Counted(
                            query.replaceAll("\\s+", " "), each.getExecutionCount(), each.getExecutionTotalTime());
                })
                .filter(each -> each.executions() > 0)
                .sorted(Comparator.comparingLong(Counted::executions).reversed())
                .limit(5)
                .map(each -> String.format(
                        Locale.ROOT,
                        "%,d x %,d ms `%s`",
                        each.executions(),
                        each.millis(),
                        each.query().length() > 120 ? each.query().substring(0, 120) + "…" : each.query()))
                .collect(Collectors.joining("; "));
    }

    /**
     * Samples the heap every 10 ms while a command runs: the most used, and the most still used after a collection
     * (the heap pools' usage after their last collection, summed), which is nearer what the command keeps.
     */
    static final class Heap {

        /**
         * What the samples found.
         *
         * @param used the most heap used
         * @param afterGc the most heap still used after a collection
         */
        record Peak(long used, long afterGc) {}

        private final AtomicLong used = new AtomicLong();
        private final AtomicLong afterGc = new AtomicLong();
        private final Thread thread;
        private volatile boolean running = true;

        private Heap() {
            List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans().stream()
                    .filter(pool -> pool.getType() == MemoryType.HEAP)
                    .toList();
            thread = Thread.ofPlatform().daemon().name("heap-sampler").start(() -> {
                while (running) {
                    used.accumulateAndGet(
                            ManagementFactory.getMemoryMXBean()
                                    .getHeapMemoryUsage()
                                    .getUsed(),
                            Math::max);
                    long collected = pools.stream()
                            .map(MemoryPoolMXBean::getCollectionUsage)
                            .filter(Objects::nonNull)
                            .mapToLong(MemoryUsage::getUsed)
                            .sum();
                    afterGc.accumulateAndGet(collected, Math::max);
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException stop) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            });
        }

        /**
         * Collects garbage, then starts sampling.
         *
         * @return the sampler
         */
        static Heap start() {
            System.gc();
            return new Heap();
        }

        Peak stop() throws InterruptedException {
            running = false;
            thread.join();
            return new Peak(used.get(), afterGc.get());
        }
    }
}
