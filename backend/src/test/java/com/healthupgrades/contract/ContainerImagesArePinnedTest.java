package com.healthupgrades.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.healthupgrades.support.PostgresImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Holds every container image the repository builds or runs to an exact version (ADR-030, NFR-51).
 *
 * <p>A floating tag changes the shipped artifact without a commit: {@code nginx:alpine} moves over
 * nginx majors, {@code postgres:15-alpine} over every patch and Alpine release. So an image here must
 * name its full version and, when it is an Alpine image, the Alpine release too. Three numeric
 * components, except PostgreSQL: since version 10 its own versions have two (15.19), so two is already
 * exact there. A leading {@code v}, as OSV-Scanner tags its releases ({@code v2.7.0}), changes nothing:
 * the count still decides, so {@code v2} floats as surely as {@code 2} does.
 *
 * <p>It also holds compose's PostgreSQL to the one the integration tests run. The schema is
 * PostgreSQL-specific and Hibernate boots with {@code validate}, so tests against a different image
 * validate against something nobody deploys.
 *
 * <p>Like {@code FrontendWireTypesContractTest}, it reads files outside {@code backend/} and searches upward
 * for the repository root, so it runs the same from Maven and from an IDE.
 */
class ContainerImagesArePinnedTest {

    /** The Node release CI builds the frontend on, relative to the repository root. */
    private static final String NVMRC = "frontend/.nvmrc";

    /** The frontend image, which must build on that same release. */
    private static final String FRONTEND_DOCKERFILE = "frontend/Dockerfile";

    /** Directory depth searched for build files: the root and each module, never {@code node_modules}. */
    private static final int BUILD_FILE_DEPTH = 2;

    /** A file that names images: a Dockerfile, or a compose file. */
    private static final Pattern BUILD_FILE = Pattern.compile("Dockerfile|(docker-)?compose[\\w.-]*\\.ya?ml");

    /** A Dockerfile's base image, with the stage alias it may declare. */
    private static final Pattern FROM = Pattern.compile(
            "^\\s*FROM\\s+(?:--platform=\\S+\\s+)?(\\S+)(?:\\s+AS\\s+(\\S+))?",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    /** A compose service's image. */
    private static final Pattern COMPOSE_IMAGE =
            Pattern.compile("^\\s*image:\\s*['\"]?([^'\"\\s#]+)", Pattern.MULTILINE);

    /** The version a tag starts with: {@code 1.30.5}, {@code 15.19}, {@code 21.0.12.1}, {@code v2.7.0}. */
    private static final Pattern LEADING_VERSION = Pattern.compile("^v?(\\d+(?:\\.\\d+)*)");

    /** An Alpine base named with its release: {@code alpine3.24} or {@code alpine-3.24}. */
    private static final Pattern PINNED_ALPINE = Pattern.compile("alpine-?\\d+\\.\\d+");

    @Test
    void GivenEveryDockerfileAndComposeFile_WhenReadingTheImagesTheyName_ThenEachNamesAnExactVersion() {
        List<String> images = imagesNamedByBuildFiles();
        images.add(PostgresImage.NAME);

        List<String> floating = images.stream().filter(image -> !isExact(image)).toList();

        assertThat(floating)
                .describedAs("images on a floating tag; name the full version, and the Alpine release for an "
                        + "Alpine image (ADR-030)")
                .isEmpty();
    }

    @Test
    void GivenTheComposeDatabase_WhenComparedWithTheIntegrationTestDatabase_ThenBothRunTheSameImage() {
        List<String> composePostgres = imagesIn(repositoryFile("docker-compose.yml")).stream()
                .filter(image -> repository(image).equals("postgres"))
                .toList();

        assertThat(composePostgres).containsExactly(PostgresImage.NAME);
    }

    @Test
    void GivenTheNvmrc_WhenComparedWithTheFrontendImage_ThenTheImageBuildsOnTheSameNode() {
        String nvmrc = read(repositoryFile(NVMRC)).strip();
        List<String> nodeImages = imagesIn(repositoryFile(FRONTEND_DOCKERFILE)).stream()
                .filter(image -> repository(image).equals("node"))
                .toList();

        assertThat(nodeImages)
                .describedAs("frontend/Dockerfile must build on the Node release frontend/.nvmrc names, which "
                        + "CI reads; move both together (ADR-030, ADR-031)")
                .singleElement()
                .satisfies(image -> assertThat(tag(image)).startsWith(nvmrc + "-"));
    }

    /** Whether an image reference names an exact version, by the rule in the class comment. */
    private static boolean isExact(String image) {
        if (image.contains("@sha256:")) {
            return true;
        }
        String tag = tag(image);
        Matcher version = LEADING_VERSION.matcher(tag);
        if (!version.find()) {
            return false;
        }
        int components = version.group(1).split("\\.").length;
        int required = repository(image).equals("postgres") ? 2 : 3;
        boolean alpinePinned =
                !tag.contains("alpine") || PINNED_ALPINE.matcher(tag).find();
        return components >= required && alpinePinned;
    }

    /** The image's tag: {@code 15.19-alpine3.24} for {@code postgres:15.19-alpine3.24}; empty if it has none. */
    private static String tag(String image) {
        String nameAndTag = lastPathSegment(image);
        int colon = nameAndTag.indexOf(':');
        return colon < 0 ? "" : nameAndTag.substring(colon + 1);
    }

    /** The image's name without registry, tag or digest: {@code postgres} for {@code postgres:15.19}. */
    private static String repository(String image) {
        String nameAndTag = lastPathSegment(image);
        int colon = nameAndTag.indexOf(':');
        return colon < 0 ? nameAndTag : nameAndTag.substring(0, colon);
    }

    /**
     * The reference after its registry and namespace, with any digest cut off: {@code postgres:15.19}
     * for {@code docker.io/library/postgres:15.19@sha256:…}. A colon before the last slash is a registry
     * port, never a tag, which is why the slash is found first.
     */
    private static String lastPathSegment(String image) {
        int at = image.indexOf('@');
        String withoutDigest = at < 0 ? image : image.substring(0, at);
        return withoutDigest.substring(withoutDigest.lastIndexOf('/') + 1);
    }

    /** Every image named by a Dockerfile or compose file at the root or in a module. */
    private static List<String> imagesNamedByBuildFiles() {
        Path root = repositoryRoot();
        List<Path> buildFiles;
        try (Stream<Path> found = Files.find(
                root,
                BUILD_FILE_DEPTH,
                (path, attributes) -> attributes.isRegularFile()
                        && BUILD_FILE.matcher(path.getFileName().toString()).matches())) {
            buildFiles = found.toList();
        } catch (IOException e) {
            throw new UncheckedIOException("could not search " + root + " for build files", e);
        }
        assertThat(buildFiles)
                .describedAs("the backend and frontend Dockerfiles and the compose file; finding fewer means "
                        + "this test is reading nothing")
                .hasSizeGreaterThanOrEqualTo(3);

        List<String> images = new ArrayList<>();
        for (Path file : buildFiles) {
            List<String> named = imagesIn(file);
            if (isDockerfile(file)) {
                assertThat(named)
                        .describedAs(
                                "%s has no FROM this test can read; a base image it cannot parse would "
                                        + "otherwise pass unchecked",
                                root.relativize(file))
                        .isNotEmpty();
            }
            images.addAll(named);
        }
        return images;
    }

    /** The images one build file names. A {@code FROM} naming an earlier stage is not an image. */
    private static List<String> imagesIn(Path file) {
        String content = read(file);
        List<String> images = new ArrayList<>();
        if (isDockerfile(file)) {
            Set<String> stages = new HashSet<>();
            Matcher from = FROM.matcher(content);
            while (from.find()) {
                if (!stages.contains(from.group(1))) {
                    images.add(from.group(1));
                }
                if (from.group(2) != null) {
                    stages.add(from.group(2));
                }
            }
        } else {
            Matcher image = COMPOSE_IMAGE.matcher(content);
            while (image.find()) {
                images.add(image.group(1));
            }
        }
        return images;
    }

    /** A Dockerfile always names a base image; a compose file whose services are all built need not. */
    private static boolean isDockerfile(Path file) {
        return file.getFileName().toString().equals("Dockerfile");
    }

    private static Path repositoryFile(String name) {
        return repositoryRoot().resolve(name);
    }

    /** The repository root, found by searching upward for the compose file. */
    private static Path repositoryRoot() {
        Path root = Paths.get("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("docker-compose.yml"))) {
            root = root.getParent();
        }
        assertThat(root)
                .describedAs(
                        "could not locate docker-compose.yml from %s; it is part of this check, and its "
                                + "absence is a broken checkout, not a reason to skip it",
                        Paths.get("").toAbsolutePath())
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
