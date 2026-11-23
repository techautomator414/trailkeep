// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.restoredrill;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The drill's record: each timed step as a table row, and the facts checked after the restore as lines below it.
 * Written to {@code target/restore-drill}, even when the drill fails, so a failed drill still shows how far it got.
 */
final class DrillReport {

    /**
     * A step that returns something and may fail.
     *
     * @param <T> what it returns
     */
    @FunctionalInterface
    interface Step<T> {
        T run() throws Exception;
    }

    /** A step that returns nothing and may fail. */
    @FunctionalInterface
    interface Action {
        void run() throws Exception;
    }

    private static final Logger LOG = LoggerFactory.getLogger(DrillReport.class);

    private final String title;
    private final String fileName;
    private final List<String> steps = new ArrayList<>();
    private final List<String> facts = new ArrayList<>();

    DrillReport(String title, String fileName) {
        this.title = title;
        this.fileName = fileName;
    }

    /**
     * Runs and times a step, and records it as a row.
     *
     * @param what the row's name
     * @param step the step
     * @param <T> what it returns
     * @return what it returned
     * @throws Exception if it fails (the row then says so)
     */
    <T> T time(String what, Step<T> step) throws Exception {
        long started = System.nanoTime();
        try {
            T result = step.run();
            row(what, started, "");
            return result;
        } catch (Exception | AssertionError failed) {
            row(what, started, "**failed**: " + failed.getMessage());
            throw failed;
        }
    }

    /**
     * Runs and times a step that returns nothing.
     *
     * @param what the row's name
     * @param action the step
     * @return the elapsed milliseconds
     * @throws Exception if it fails
     */
    long run(String what, Action action) throws Exception {
        long started = System.nanoTime();
        time(what, () -> {
            action.run();
            return Boolean.TRUE;
        });
        return (System.nanoTime() - started) / 1_000_000;
    }

    /**
     * Adds a line below the table.
     *
     * @param line the fact
     */
    void fact(String line) {
        LOG.info("Restore drill: {}", line);
        facts.add(line);
    }

    private void row(String what, long started, String note) {
        long ms = (System.nanoTime() - started) / 1_000_000;
        LOG.info("Restore drill: {} took {} ms {}", what, ms, note);
        steps.add("| " + what + " | " + String.format(Locale.ROOT, "%,d", ms) + " | " + note + " |");
    }

    void write() throws IOException {
        Path dir = Path.of("target", "restore-drill");
        Files.createDirectories(dir);
        List<String> out = new ArrayList<>();
        out.add("# " + title);
        out.add("");
        out.add("| Step | ms | |");
        out.add("|---|---:|---|");
        out.addAll(steps);
        out.add("");
        facts.forEach(fact -> out.add("- " + fact));
        Files.write(dir.resolve(fileName), out);
    }
}
