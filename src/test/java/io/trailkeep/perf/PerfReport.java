// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.perf;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultMatcher;

/**
 * Times requests and calls for a perf IT and writes its table to {@code target/perf}. Each scenario is sent {@value
 * #WARM_UP} times to warm up and then {@value #SAMPLES} times measured, one after another; its p50, p95 and max become
 * a row of the table. Lines that are not table rows (the seed's size, plans) follow the table.
 */
final class PerfReport {

    static final int WARM_UP = 10;
    static final int SAMPLES = 60;

    private static final Logger LOG = LoggerFactory.getLogger(PerfReport.class);

    private final String title;
    private final String fileName;
    private final List<String> lines = Collections.synchronizedList(new ArrayList<>());

    /**
     * A report.
     *
     * @param title the table's heading
     * @param fileName the file under {@code target/perf}
     */
    PerfReport(String title, String fileName) {
        this.title = title;
        this.fileName = fileName;
    }

    /**
     * Sends a request, warms up, measures, records the percentiles and returns the p95.
     *
     * @param mvc the application
     * @param scenario the row's name
     * @param request the request; every response must be 200
     * @return the p95 in whole milliseconds
     * @throws Exception if a request fails
     */
    long p95(MockMvc mvc, String scenario, RequestBuilder request) throws Exception {
        return p95(mvc, scenario, request, status().isOk());
    }

    /**
     * Sends a request, warms up, measures, records the percentiles and returns the p95.
     *
     * @param mvc the application
     * @param scenario the row's name
     * @param request the request
     * @param expected what every response must match
     * @return the p95 in whole milliseconds
     * @throws Exception if a request fails
     */
    long p95(MockMvc mvc, String scenario, RequestBuilder request, ResultMatcher expected) throws Exception {
        for (int i = 0; i < WARM_UP; i++) {
            mvc.perform(request).andExpect(expected);
        }
        List<Long> times = new ArrayList<>(SAMPLES);
        for (int i = 0; i < SAMPLES; i++) {
            long started = System.nanoTime();
            mvc.perform(request).andExpect(expected);
            times.add((System.nanoTime() - started) / 1_000);
        }
        return record(scenario, times);
    }

    /**
     * Calls, warms up, measures and records the percentiles.
     *
     * @param scenario the row's name
     * @param call what is timed
     * @return the p95 in whole milliseconds
     */
    long timed(String scenario, Runnable call) {
        for (int i = 0; i < WARM_UP; i++) {
            call.run();
        }
        List<Long> times = new ArrayList<>(SAMPLES);
        for (int i = 0; i < SAMPLES; i++) {
            long started = System.nanoTime();
            call.run();
            times.add((System.nanoTime() - started) / 1_000);
        }
        return record(scenario, times);
    }

    /**
     * Adds a line: a table row if it starts with {@code |}, otherwise a note after the table.
     *
     * @param line the line
     */
    void add(String line) {
        lines.add(line);
    }

    /**
     * Writes the report to {@code target/perf} and logs it.
     *
     * @throws IOException if the file cannot be written
     */
    void write() throws IOException {
        Path out = Path.of("target", "perf", fileName);
        Files.createDirectories(out.getParent());
        List<String> report = new ArrayList<>();
        report.add("# " + title);
        report.add("");
        report.add("| Scenario | p50 ms | p95 ms | max ms |");
        report.add("|---|---:|---:|---:|");
        synchronized (lines) {
            lines.stream().filter(line -> line.startsWith("|")).forEach(report::add);
            report.add("");
            lines.stream().filter(line -> !line.startsWith("|")).forEach(report::add);
        }
        Files.write(out, report, StandardCharsets.UTF_8);
        report.forEach(line -> LOG.info("{}", line));
    }

    /** Records p50, p95 and max of times in microseconds, as milliseconds; returns the p95 in whole milliseconds. */
    private long record(String scenario, List<Long> times) {
        List<Long> sorted = new ArrayList<>(times);
        Collections.sort(sorted);
        long p50 = sorted.get(sorted.size() / 2);
        long p95 = sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);
        lines.add(String.format(
                Locale.ROOT,
                "| %s | %.1f | %.1f | %.1f |",
                scenario,
                p50 / 1000.0,
                p95 / 1000.0,
                sorted.get(sorted.size() - 1) / 1000.0));
        return p95 / 1000;
    }
}
