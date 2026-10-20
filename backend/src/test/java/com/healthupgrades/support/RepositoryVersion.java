package com.healthupgrades.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

/**
 * The repository's version, as {@code backend/pom.xml} declares it, read from the file rather than from
 * the build.
 *
 * <p>One version names the whole repository (ADR-050), and the pom is the field every other one is held
 * to: {@code RepositoryVersionContractTest} compares the frontend's fields and the changelog with it, and
 * {@code ActuatorEndpointsIT} compares what a running instance reports. Like
 * {@code ContainerImagesArePinnedTest}, it searches upward for the repository root, so it runs the same
 * from Maven and from an IDE.
 */
public final class RepositoryVersion {

    /** The backend's build file, relative to the repository root: how the root is recognised. */
    private static final Path BACKEND_POM = Paths.get("backend", "pom.xml");

    /**
     * The project's own version. The parent's {@code <version>} comes first in the file, which is why this
     * is a path through the document and not a search through its text.
     */
    private static final String PROJECT_VERSION = "/project/version";

    private RepositoryVersion() {}

    /**
     * The version {@code backend/pom.xml} declares for the project.
     *
     * @return the version, exactly as written
     * @throws IllegalStateException if the pom cannot be read or parsed
     */
    public static String declaredByThePom() {
        Path pom = repositoryRoot().resolve(BACKEND_POM);
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document document = factory.newDocumentBuilder().parse(pom.toFile());
            String version = XPathFactory.newInstance().newXPath().evaluate(PROJECT_VERSION, document);
            assertThat(version)
                    .describedAs("%s declares no %s", BACKEND_POM, PROJECT_VERSION)
                    .isNotBlank();
            return version;
        } catch (IOException | ParserConfigurationException | SAXException | XPathExpressionException e) {
            throw new IllegalStateException("could not read the project version from " + pom, e);
        }
    }

    /**
     * The repository root, found by searching upward for the backend's pom.
     *
     * @return the directory holding {@code backend/} and {@code frontend/}
     */
    public static Path repositoryRoot() {
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
}
