package com.healthupgrades.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.healthupgrades.support.RepositoryVersion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Holds every field that names the repository's version to the one {@code backend/pom.xml} declares
 * (ADR-050, NFR-54).
 *
 * <p>The API and the interface ship together and are never released apart, so they carry one version.
 * Four fields spell it: the pom's, {@code frontend/package.json}'s, and the two its lockfile repeats.
 * Nothing else connects them. Maven reads none of the frontend's, and {@code npm ci} does not compare a
 * lockfile's own version with its manifest's. A release sets them with two commands (the README's
 * Releasing section), and this is what fails when one of them was not run.
 *
 * <p>It reads files and never asks git: CI checks out a shallow clone without tags, and a release's tag
 * exists only after its promotion has merged.
 */
class RepositoryVersionContractTest {

    private static final Path FRONTEND_MANIFEST = Paths.get("frontend", "package.json");

    private static final Path FRONTEND_LOCKFILE = Paths.get("frontend", "package-lock.json");

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

    private JsonNode readJson(Path relative) {
        Path file = RepositoryVersion.repositoryRoot().resolve(relative);
        try {
            return json.readTree(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
    }
}
