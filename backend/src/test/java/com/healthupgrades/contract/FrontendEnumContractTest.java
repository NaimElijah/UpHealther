package com.healthupgrades.contract;

import com.healthupgrades.notification.domain.model.NotificationCategory;
import com.healthupgrades.notification.domain.model.NotificationType;
import com.healthupgrades.tracking.domain.model.Frequency;
import com.healthupgrades.tracking.domain.model.TrackingType;
import com.healthupgrades.upgrade.domain.model.Difficulty;
import com.healthupgrades.upgrade.domain.model.UpgradeStatus;
import com.healthupgrades.upgrade.domain.model.UpgradeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins every backend enum against the hand-written union the frontend mirrors it with.
 *
 * <p>These pairs are a contract with no compiler behind it. The frontend types are written by hand
 * rather than generated, so nothing connects the two sides: a value the frontend offers and the
 * backend lacks is bound straight from the request body and fails to deserialize — which surfaces as a
 * 500, not a validation error, because an unbindable body is not a bean-validation failure.
 *
 * <p>That is not hypothetical. The frontend offered five upgrade types the API had never accepted, and
 * a {@code CUSTOM} frequency backed by nothing, and both were selectable in the UI for as long as they
 * existed. This test is what makes the next divergence fail the build instead of reaching a user.
 *
 * <p>It reads the frontend source directly, so it is coupled to that file's location. That is
 * deliberate: any indirection would be another thing to keep in step, which is the problem being
 * solved.
 */
class FrontendEnumContractTest {

    /** The frontend module's shared type declarations, relative to the repository root. */
    private static final Path FRONTEND_TYPES = Paths.get("frontend", "src", "types", "index.ts");

    /** Matches a value inside a union declaration, e.g. the {@code HABIT} in {@code | 'HABIT'}. */
    private static final Pattern UNION_MEMBER = Pattern.compile("'([A-Z_]+)'");

    /**
     * Matches a union of screaming-caps string literals, which is how this codebase spells a mirrored
     * backend enum and nothing else.
     */
    private static final Pattern MIRRORED_UNION = Pattern.compile(
            "export type (\\w+)\\s*=\\s*((?:\\s*\\|?\\s*'[A-Z_]+')+)\\s*;", Pattern.DOTALL);

    /**
     * Every backend enum that the frontend mirrors, paired with the union that mirrors it.
     *
     * <p>A new mirrored enum belongs here; one that is not listed is not checked.
     */
    private static Stream<Arguments> mirroredEnums() {
        return Stream.of(
                Arguments.of("UpgradeType", names(UpgradeType.values())),
                Arguments.of("UpgradeStatus", names(UpgradeStatus.values())),
                Arguments.of("Difficulty", names(Difficulty.values())),
                Arguments.of("TrackingType", names(TrackingType.values())),
                Arguments.of("Frequency", names(Frequency.values())),
                Arguments.of("NotificationType", names(NotificationType.values())),
                Arguments.of("NotificationCategory", names(NotificationCategory.values())));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mirroredEnums")
    void GivenAMirroredEnum_WhenComparedWithTheFrontendUnion_ThenBothDeclareTheSameValues(
            String unionName, Set<String> backendValues) {

        Set<String> frontendValues = unionValues(unionName);

        assertThat(frontendValues)
                .describedAs("frontend union '%s' in %s must declare exactly the values of the backend enum; "
                                + "a value on only one side is a request the API cannot bind, or a row the UI "
                                + "cannot render",
                        unionName, FRONTEND_TYPES)
                .containsExactlyInAnyOrderElementsOf(backendValues);
    }

    @Test
    void GivenARegisteredUnion_WhenTheFrontendIsRead_ThenItStillExistsThere() {
        String source = readFrontendTypes();

        assertThat(registeredUnions())
                .allSatisfy(union -> assertThat(source)
                        .describedAs("union '%s' was renamed or removed; this test can no longer check it, "
                                + "which is worse than it failing", union)
                        .contains("export type " + union + " ="));
    }

    /**
     * The registration above is hand-maintained, which is the very failure this class exists to catch —
     * so it is checked too. A mirrored union the frontend declares and nobody registered would otherwise
     * be silently unguarded.
     */
    @Test
    void GivenAMirroredUnionInTheFrontend_WhenItIsNotRegisteredHere_ThenTheOmissionFailsTheBuild() {
        Matcher declarations = MIRRORED_UNION.matcher(readFrontendTypes());
        Set<String> declared = new LinkedHashSet<>();
        while (declarations.find()) {
            declared.add(declarations.group(1));
        }

        assertThat(declared)
                .describedAs("every enum-shaped union in %s must be registered in mirroredEnums(), or it is "
                                + "not checked against the backend at all. Register it, or — if it is a "
                                + "frontend-only union with no backend enum behind it — say so in a comment "
                                + "on the declaration and exclude it here deliberately",
                        FRONTEND_TYPES)
                .isSubsetOf(registeredUnions());
    }

    /** The union names this class checks. */
    private static Set<String> registeredUnions() {
        return mirroredEnums()
                .map(args -> (String) args.get()[0])
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** The values declared by one union in the frontend's shared types. */
    private static Set<String> unionValues(String unionName) {
        String source = readFrontendTypes();
        Pattern declaration = Pattern.compile(
                "export type " + Pattern.quote(unionName) + "\\s*=(.*?);", Pattern.DOTALL);

        Matcher declarationMatch = declaration.matcher(source);
        assertThat(declarationMatch.find())
                .describedAs("no 'export type %s' declaration found in %s", unionName, FRONTEND_TYPES)
                .isTrue();

        Matcher members = UNION_MEMBER.matcher(declarationMatch.group(1));
        Set<String> values = new LinkedHashSet<>();
        while (members.find()) {
            values.add(members.group(1));
        }
        return values;
    }

    /** Reads the frontend types file, searching upward for the repository root. */
    private static String readFrontendTypes() {
        Path root = Paths.get("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve(FRONTEND_TYPES))) {
            root = root.getParent();
        }
        assertThat(root)
                .describedAs("could not locate %s from %s — the frontend module is part of this contract "
                                + "and its absence is a broken checkout, not a reason to skip the check",
                        FRONTEND_TYPES, Paths.get("").toAbsolutePath())
                .isNotNull();

        try {
            return Files.readString(root.resolve(FRONTEND_TYPES));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + root.resolve(FRONTEND_TYPES), e);
        }
    }

    /** The constant names of an enum, as a set. */
    private static Set<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
