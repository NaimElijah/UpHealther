package com.healthupgrades.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.healthupgrades.support.RepositoryVersion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Holds every field that names the repository's version to the one {@code backend/pom.xml} declares,
 * and the changelog to that version (ADR-050, ADR-051, NFR-54).
 *
 * <p>The API and the interface ship together and are never released apart, so they carry one version.
 * Four fields spell it: the pom's, {@code frontend/package.json}'s, and the two its lockfile repeats.
 * Nothing else connects them. Maven reads none of the frontend's, and {@code npm ci} does not compare a
 * lockfile's own version with its manifest's. A release sets them with two commands (the README's
 * Releasing section), and this is what fails when one of them was not run.
 *
 * <p>{@code CHANGELOG.md} is generated, one section per release, by a command a release can forget or
 * run wrongly, so its shape is checked too.
 *
 * <p>It reads files and never asks git: CI checks out a shallow clone without tags, and a release's tag
 * exists only after its promotion has merged.
 */
class RepositoryVersionContractTest {

    private static final Path FRONTEND_MANIFEST = Paths.get("frontend", "package.json");

    private static final Path FRONTEND_LOCKFILE = Paths.get("frontend", "package-lock.json");

    private static final Path CHANGELOG = Paths.get("CHANGELOG.md");

    /** A release's heading, as {@code cliff.toml} writes it: {@code ## [0.1.0] - 2026-10-21}. */
    private static final Pattern SECTION_HEADING = Pattern.compile("^## \\[([^]]+)]", Pattern.MULTILINE);

    /**
     * The short id that ends each commit's line, as {@code cliff.toml} writes it. A template that writes it
     * any other way, as a link for instance, needs this pattern changed with it.
     */
    private static final Pattern COMMIT_ID = Pattern.compile("\\(([0-9a-f]{7})\\)$", Pattern.MULTILINE);

    /** The start of each commit's line: one per commit, whatever the template writes after it. */
    private static final Pattern ENTRY = Pattern.compile("^- ", Pattern.MULTILINE);

    /** Three numbers and nothing after them: a version that names a release, never {@code -SNAPSHOT}. */
    private static final String RELEASE_VERSION = "\\d+\\.\\d+\\.\\d+";

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void GivenThePom_WhenItsVersionIsRead_ThenItNamesARelease() {
        assertThat(RepositoryVersion.declaredByThePom())
                .describedAs("one version names the repository, and it is MAJOR.MINOR.PATCH (ADR-050)")
                .matches(RELEASE_VERSION);
    }

    @Test
    void GivenTheFrontendsManifestAndLockfile_WhenTheirVersionsAreRead_ThenEachIsThePomsVersion() {
        String version = RepositoryVersion.declaredByThePom();
        JsonNode manifest = readJson(FRONTEND_MANIFEST);
        JsonNode lockfile = readJson(FRONTEND_LOCKFILE);

        Map<String, String> versionByField = new LinkedHashMap<>();
        versionByField.put(
                "frontend/package.json version", manifest.path("version").asString());
        versionByField.put(
                "frontend/package-lock.json version", lockfile.path("version").asString());
        versionByField.put(
                "frontend/package-lock.json packages[\"\"].version",
                lockfile.path("packages").path("").path("version").asString());

        assertThat(versionByField)
                .describedAs(
                        "every field must name backend/pom.xml's version, %s; the README's Releasing section "
                                + "sets them together",
                        version)
                .allSatisfy((field, value) -> assertThat(value).as(field).isEqualTo(version));
    }

    /**
     * Catches a version bumped with no section written for it, and a section written without
     * {@code --tag}, whose heading is "Unreleased".
     */
    @Test
    void GivenTheChangelog_WhenItsNewestSectionIsRead_ThenItIsThePomsVersion() {
        String version = RepositoryVersion.declaredByThePom();

        assertThat(matches(SECTION_HEADING, read(CHANGELOG)))
                .describedAs(
                        "the sections of %s, newest first: `npm run changelog -- --tag v%s` writes the "
                                + "newest (the README's Releasing section)",
                        CHANGELOG, version)
                .startsWith(version);
    }

    /**
     * Catches {@code --prepend} run twice, which repeats a release, and a run without the tags fetched,
     * which writes the whole history again under the new version.
     *
     * <p>Every entry must yield an id before the ids can be compared. Otherwise a template that wrote them
     * differently would leave nothing to compare, and the check would pass on any changelog.
     */
    @Test
    void GivenTheChangelog_WhenItsSectionsAreRead_ThenNoReleaseAndNoCommitIsListedTwice() {
        String changelog = read(CHANGELOG);

        assertThat(matches(SECTION_HEADING, changelog))
                .describedAs("the releases in %s", CHANGELOG)
                .isNotEmpty()
                .doesNotHaveDuplicates();
        assertThat(matches(COMMIT_ID, changelog))
                .describedAs(
                        "the commit ids in %s, one per entry; if cliff.toml's template changed how it writes "
                                + "them, COMMIT_ID has to change with it",
                        CHANGELOG)
                .isNotEmpty()
                .hasSize((int) ENTRY.matcher(changelog).results().count());
        assertThat(matches(COMMIT_ID, changelog))
                .describedAs(
                        "the commits in %s; one listed twice means a section was generated from the whole "
                                + "history, so the tags were not fetched first",
                        CHANGELOG)
                .doesNotHaveDuplicates();
    }

    private static List<String> matches(Pattern pattern, String text) {
        return pattern.matcher(text).results().map(match -> match.group(1)).toList();
    }

    private JsonNode readJson(Path relative) {
        return json.readTree(read(relative));
    }

    private static String read(Path relative) {
        Path file = RepositoryVersion.repositoryRoot().resolve(relative);
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
    }
}
