package com.healthupgrades.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.healthupgrades.support.RepositoryVersion;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Holds the frontend's wire types to the request and response records the API's controllers declare
 * (NFR-10, ADR-053).
 *
 * <p>The frontend's copies of those shapes used to be written by hand, and only their enums were
 * checked. A shape drifted silently whenever one side changed. #117 showed every entry without a rating
 * as "⭐ /5", because the type said {@code rating?: number} while the API sent {@code null}, and a guard
 * against {@code undefined} let the null through. #88's Plan button posted no body, because the frontend
 * had no type for the request at all. #19 was the enum half of the same problem.
 *
 * <p>{@code frontend/src/types/api.generated.ts} is now rendered from the records themselves by
 * {@link FrontendWireTypes}. This test fails when the committed file differs from what the API declares
 * today, and {@code npx tsc} then fails wherever the frontend misuses the shape it was given. It reads
 * and writes the frontend's source directly, so it is coupled to that file's location on purpose, as
 * {@code FrontendEnumContractTest} was: any indirection would be one more thing to keep in step.
 *
 * <p>Run with {@code -Dwire-types.write=true}, which {@code npm run generate:wire-types} passes, it
 * writes the file instead of only comparing it.
 *
 * <p>{@code frontend/src/types/index.ts} gives the generated types the names the frontend uses. It is
 * checked too, because a shape written there by hand would be exactly the drift this class ends.
 */
class FrontendWireTypesContractTest {

    /** The generated declarations, relative to the repository root. */
    private static final Path GENERATED = Paths.get("frontend", "src", "types", "api.generated.ts");

    /** Set to {@code true} to write {@link #GENERATED} rather than only compare it. */
    private static final String WRITE_PROPERTY = "wire-types.write";

    private static final String REGENERATE = "npm run generate:wire-types";

    /** The frontend's shared types, which name the generated ones, relative to the repository root. */
    private static final Path SHARED = Paths.get("frontend", "src", "types", "index.ts");

    /** A union of screaming-caps string literals: how a backend enum is spelled when it is copied. */
    private static final Pattern COPIED_ENUM =
            Pattern.compile("export type (\\w+)\\s*=\\s*(?:\\s*\\|?\\s*'[A-Z_]+')+\\s*;", Pattern.DOTALL);

    /** An interface declaration: how a response or request shape is spelled when it is copied. */
    private static final Pattern COPIED_SHAPE = Pattern.compile("^\\s*export interface (\\w+)", Pattern.MULTILINE);

    @Test
    void GivenTheApisControllers_WhenTheirBodiesAreRendered_ThenTheCommittedFrontendFileIsTheResult()
            throws IOException {
        List<Class<?>> controllers = FrontendWireTypes.apiControllers();
        assertThat(controllers)
                .describedAs(
                        "no @RestController was found under %s; an empty scan renders an empty file, which"
                                + " would pass against an empty one",
                        FrontendWireTypes.API_PACKAGE)
                .isNotEmpty();
        String rendered = FrontendWireTypes.render(controllers);

        Path file = RepositoryVersion.repositoryRoot().resolve(GENERATED);
        if (Boolean.getBoolean(WRITE_PROPERTY)) {
            Files.writeString(file, rendered);
        }
        assertThat(file)
                .describedAs("%s is missing. Generate it with `%s` from the repository root", GENERATED, REGENERATE)
                .exists();

        // A Windows checkout may hold the file with CRLF endings although the index has LF.
        String committed = Files.readString(file).replace("\r\n", "\n");
        assertThat(committed)
                .describedAs(
                        "%s no longer matches the API's request and response records. Regenerate it with `%s`"
                                + " from the repository root and commit it; `npx tsc` in frontend/ then shows"
                                + " every place the change reaches",
                        GENERATED, REGENERATE)
                .isEqualTo(rendered);
    }

    @Test
    void GivenTheFrontendsSharedTypes_WhenRead_ThenNoBackendEnumIsCopiedByHand() throws IOException {
        assertThat(declarations(COPIED_ENUM))
                .describedAs(
                        "%s spells out these unions of constant names by hand. Alias the union %s generates"
                                + " from the backend enum instead, which cannot drift from it",
                        SHARED, GENERATED)
                .isEmpty();
    }

    @Test
    void GivenTheFrontendsSharedTypes_WhenRead_ThenNoWireShapeIsWrittenByHand() throws IOException {
        assertThat(declarations(COPIED_SHAPE))
                .describedAs(
                        "%s declares these interfaces by hand. Alias the type %s generates from the API's"
                                + " record, and add only what the API does not send, as an intersection with it",
                        SHARED, GENERATED)
                .isEmpty();
    }

    @Test
    void GivenEveryMappingRule_WhenRendered_ThenResponseKeysAreAlwaysPresentAndOnlyNullableRequestKeysMayBeAbsent() {
        String rendered = FrontendWireTypes.render(List.of(EveryRule.class));

        assertThat(rendered)
                .startsWith("// Generated by FrontendWireTypesContractTest")
                .endsWith("}\n")
                .doesNotContain("\r")
                .doesNotContain("Optional")
                .containsSubsequence(
                        "/** com.healthupgrades.contract.FrontendWireTypesContractTest.Shade */\n"
                                + "export type Shade = 'LIGHT' | 'DARK';\n",
                        "/** Request body: com.healthupgrades.contract.FrontendWireTypesContractTest"
                                + ".EveryRuleRequest */\n"
                                + "export interface EveryRuleRequest {\n"
                                + "  title: string;\n"
                                + "  remark?: string | null;\n"
                                + "  urgent: boolean;\n"
                                + "}\n",
                        "/** Response body: com.healthupgrades.contract.FrontendWireTypesContractTest"
                                + ".EveryRuleResponse */\n"
                                + "export interface EveryRuleResponse {\n"
                                + "  id: string;\n"
                                + "  note: string | null;\n"
                                + "  count: number;\n"
                                + "  shades: Shade[];\n"
                                + "  tallies: Record<string, number>;\n"
                                + "  part: Part | null;\n"
                                + "}\n",
                        "/** Response body: com.healthupgrades.contract.FrontendWireTypesContractTest.Part */\n"
                                + "export interface Part {\n"
                                + "  day: string;\n"
                                + "}\n");
    }

    @Test
    void GivenAComponentOfATypeOutsideTheMapping_WhenRendered_ThenItFailsNamingWhereItWasReached() {
        assertThatThrownBy(() -> FrontendWireTypes.render(List.of(ReturnsAnOptional.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WithAnOptional.nickname")
                .hasMessageContaining("java.util.Optional<java.lang.String>");
    }

    @Test
    void GivenARecordUsedAsBothARequestAndAResponse_WhenRendered_ThenItFails() {
        assertThatThrownBy(() -> FrontendWireTypes.render(List.of(EchoesItsBody.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Echo is both a request body and a response body");
    }

    @Test
    void GivenTwoWireTypesWithOneSimpleName_WhenRendered_ThenItFailsNamingBoth() {
        assertThatThrownBy(() -> FrontendWireTypes.render(List.of(ReturnsTwins.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("two wire types are named Twin")
                .hasMessageContaining("FirstHolder.Twin")
                .hasMessageContaining("SecondHolder.Twin");
    }

    @Test
    void GivenAJacksonAnnotationOnAComponent_WhenRendered_ThenItFailsRatherThanRenderTheWrongKey() {
        assertThatThrownBy(() -> FrontendWireTypes.render(List.of(ReturnsARenamedKey.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Renamed.name")
                .hasMessageContaining(JsonProperty.class.getName());
    }

    @Test
    void GivenAMapKeyedByANumber_WhenRendered_ThenItFailsBecauseJsonKeysAreStrings() {
        assertThatThrownBy(() -> FrontendWireTypes.render(List.of(ReturnsANumberKeyedMap.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ByNumber.names")
                .hasMessageContaining("keyed by java.lang.Integer");
    }

    @Test
    void GivenARequestComponentNeitherNullableNorConstrained_WhenRendered_ThenItFailsRatherThanTypeItOnTrust() {
        assertThatThrownBy(() -> FrontendWireTypes.render(List.of(AcceptsAnUnconstrainedBody.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "Unconstrained.title is a request component that nothing keeps from being" + " null");
    }

    /** The names of every declaration in {@link #SHARED} the pattern matches. */
    private static List<String> declarations(Pattern declaration) throws IOException {
        Matcher matches = declaration.matcher(
                Files.readString(RepositoryVersion.repositoryRoot().resolve(SHARED)));
        List<String> names = new ArrayList<>();
        while (matches.find()) {
            names.add(matches.group(1));
        }
        return names;
    }

    /** An enum reached through a response. */
    enum Shade {
        LIGHT,
        DARK
    }

    /** A record reached only through another record. */
    record Part(LocalDate day) {}

    /** A request with a required, an optional and a primitive component. */
    record EveryRuleRequest(
            @NotBlank String title, @Nullable String remark, boolean urgent) {}

    /** A response touching each mapping rule once. */
    record EveryRuleResponse(
            UUID id,
            @Nullable String note,
            int count,
            List<Shade> shades,
            Map<UUID, Integer> tallies,
            @Nullable Part part) {}

    /**
     * Endpoints in the shape a controller declares them. Not a {@code @RestController}: a fixture that was
     * one would be picked up by every web slice under {@code com.healthupgrades}.
     */
    static class EveryRule {

        @PostMapping
        ResponseEntity<EveryRuleResponse> create(@RequestBody EveryRuleRequest request) {
            return ResponseEntity.ok().build();
        }

        @GetMapping
        List<EveryRuleResponse> list() {
            return List.of();
        }

        @DeleteMapping
        ResponseEntity<Void> delete() {
            return ResponseEntity.noContent().build();
        }

        /** Not mapped, so its body is not part of the API, and its unmappable type must not be visited. */
        void helper(@RequestBody Optional<String> ignored) {}
    }

    record WithAnOptional(Optional<String> nickname) {}

    static class ReturnsAnOptional {
        @GetMapping
        WithAnOptional get() {
            return new WithAnOptional(Optional.empty());
        }
    }

    record Echo(@NotNull String text) {}

    static class EchoesItsBody {
        @PutMapping
        Echo put(@RequestBody Echo echo) {
            return echo;
        }
    }

    static class FirstHolder {
        record Twin(String left) {}
    }

    static class SecondHolder {
        record Twin(String right) {}
    }

    record BothTwins(FirstHolder.Twin first, SecondHolder.Twin second) {}

    static class ReturnsTwins {
        @GetMapping
        BothTwins get() {
            return new BothTwins(new FirstHolder.Twin("l"), new SecondHolder.Twin("r"));
        }
    }

    record Renamed(@JsonProperty("display_name") String name) {}

    static class ReturnsARenamedKey {
        @GetMapping
        Renamed get() {
            return new Renamed("n");
        }
    }

    record ByNumber(Map<Integer, String> names) {}

    static class ReturnsANumberKeyedMap {
        @GetMapping
        ByNumber get() {
            return new ByNumber(Map.of());
        }
    }

    record Unconstrained(String title) {}

    static class AcceptsAnUnconstrainedBody {
        @PostMapping
        ResponseEntity<Void> post(@RequestBody Unconstrained body) {
            return ResponseEntity.noContent().build();
        }
    }
}
