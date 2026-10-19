package com.healthupgrades.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Holds every exception to the backend's dependency audit to a reason and an expiry (ADR-047, NFR-12).
 *
 * <p>OSV-Scanner fails the build on any known vulnerability, and {@code backend/osv-scanner.toml} is
 * where one is accepted. The scanner itself honours a bare {@code id}: it filters the finding "because:
 * (no reason given)", and without an {@code ignoreUntil} the finding never comes back. A
 * {@code [[PackageOverrides]]} entry silences a whole package the same way. So the rule that each
 * exception says why, and until when, is kept here rather than by the tool.
 *
 * <p>No file means nothing is accepted, and that passes. Like {@code ContainerImagesArePinnedTest}, it
 * searches upward for the repository root, so it runs the same from Maven and from an IDE.
 */
class OsvScannerExceptionsAreDatedTest {

    /** The backend's build file, relative to the repository root: how the root is recognised. */
    private static final Path BACKEND_POM = Paths.get("backend", "pom.xml");

    /** Where OSV-Scanner looks for its configuration: beside the pom it scans. */
    private static final Path SCANNER_CONFIG = Paths.get("backend", "osv-scanner.toml");

    /** The tables that silence a finding, each with the key that ends the exception. */
    private static final Map<String, String> EXPIRY_KEY_BY_TABLE =
            Map.of("IgnoredVulns", "ignoreUntil", "PackageOverrides", "effectiveUntil");

    /** Any TOML table header, {@code [x]} or {@code [[x]]}: each one ends the table before it. */
    private static final Pattern TABLE_HEADER =
            Pattern.compile("^\\s*(\\[\\[?)\\s*([\\w.]+)\\s*]]?", Pattern.MULTILINE);

    /** A non-blank {@code reason}, in either kind of TOML string. A commented-out line does not match. */
    private static final Pattern REASON =
            Pattern.compile("^\\s*reason\\s*=\\s*(\"[^\"]*\\S[^\"]*\"|'[^']*\\S[^']*')", Pattern.MULTILINE);

    /** What names an exception in the failure message: the advisory, or the package. */
    private static final Pattern SUBJECT =
            Pattern.compile("^\\s*(?:id|name)\\s*=\\s*[\"']([^\"']*)", Pattern.MULTILINE);

    @Test
    void GivenTheBackendsOsvScannerConfig_WhenReadingWhatItAccepts_ThenEveryExceptionHasAReasonAndAnExpiry() {
        Path config = repositoryRoot().resolve(SCANNER_CONFIG);
        if (!Files.exists(config)) {
            return;
        }

        assertThat(exceptionsMissingAReasonOrAnExpiry(read(config)))
                .describedAs(
                        "every [[IgnoredVulns]] in %s needs a non-blank reason and an ignoreUntil, and every "
                                + "[[PackageOverrides]] a reason and an effectiveUntil; otherwise the finding is "
                                + "silenced for good, with nothing saying why (ADR-047)",
                        SCANNER_CONFIG)
                .isEmpty();
    }

    /** One line per exception that lacks a reason or an expiry, naming what it silences. */
    private static List<String> exceptionsMissingAReasonOrAnExpiry(String toml) {
        List<String> missing = new ArrayList<>();
        for (ArrayTable table : arrayTables(toml)) {
            String expiryKey = EXPIRY_KEY_BY_TABLE.get(table.name());
            if (expiryKey == null) {
                continue;
            }
            boolean hasReason = REASON.matcher(table.body()).find();
            boolean hasExpiry = Pattern.compile("^\\s*" + expiryKey + "\\s*=\\s*\\S", Pattern.MULTILINE)
                    .matcher(table.body())
                    .find();
            if (!hasReason || !hasExpiry) {
                Matcher subject = SUBJECT.matcher(table.body());
                missing.add("[[%s]] %s:%s%s"
                        .formatted(
                                table.name(),
                                subject.find() ? subject.group(1) : "(unnamed)",
                                hasReason ? "" : " no reason",
                                hasExpiry ? "" : " no " + expiryKey));
            }
        }
        return missing;
    }

    /** One {@code [[name]]} entry, with everything up to the next table header. */
    private record ArrayTable(String name, String body) {}

    private static List<ArrayTable> arrayTables(String toml) {
        List<MatchResult> headers = TABLE_HEADER.matcher(toml).results().toList();
        List<ArrayTable> tables = new ArrayList<>();
        for (int i = 0; i < headers.size(); i++) {
            MatchResult header = headers.get(i);
            int end = i + 1 < headers.size() ? headers.get(i + 1).start() : toml.length();
            if (header.group(1).equals("[[")) {
                tables.add(new ArrayTable(header.group(2), toml.substring(header.end(), end)));
            }
        }
        return tables;
    }

    /** The repository root, found by searching upward for the backend's pom. */
    private static Path repositoryRoot() {
        Path root = Paths.get("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve(BACKEND_POM))) {
            root = root.getParent();
        }
        assertThat(root)
                .describedAs(
                        "could not locate %s from %s",
                        BACKEND_POM, Paths.get("").toAbsolutePath())
                .isNotNull();
        return root;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
    }
}
