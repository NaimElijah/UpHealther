package com.healthupgrades.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.healthupgrades.contract.FrontendWireTypes.Direction;
import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeType;

/**
 * Holds {@link FrontendWireTypes}' rendering rules to the mapper the application runs with (NFR-10,
 * ADR-053).
 *
 * <p>The generator reads a record's components, an enum's constant names and a component's
 * {@code @Nullable}, and renders the JSON it expects from them. Jackson can disagree without a line of
 * the record changing:
 * <ul>
 *   <li>Jackson 3 writes and reads an enum through its {@code toString()} by default.
 *   <li>An extra {@code getX()} on a record is written as a key.
 *   <li>A {@code @JsonValue} replaces the whole object.
 *   <li>A module or a mixin can reshape anything.
 *   <li>One {@code spring.jackson.default-property-inclusion} drops every null key that the types say is
 *       present.
 * </ul>
 * So every record the API's controllers reach is written, or read, by Boot's own mapper, and the
 * result is compared with the rule it was rendered by.
 */
@JsonTest
class FrontendWireTypesJsonTest {

    @Autowired
    private ObjectMapper mapper;

    static Stream<Class<?>> responseBodies() {
        return recordsCrossing(Direction.RESPONSE);
    }

    static Stream<Class<?>> requestBodies() {
        return recordsCrossing(Direction.REQUEST);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("responseBodies")
    void GivenAResponseWithEveryComponentSet_WhenTheMapperWritesIt_ThenItHasTheRenderedKeysKindsAndEnumNames(
            Class<?> record) {
        JsonNode json = mapper.valueToTree(sample(record, true));

        assertThat(json.propertyNames())
                .describedAs(
                        "the keys Jackson writes for %s are not its components, which are the keys the"
                                + " generated interface declares",
                        record.getSimpleName())
                .containsExactlyInAnyOrderElementsOf(componentNames(record));
        for (RecordComponent component : record.getRecordComponents()) {
            assertRendered(json.get(component.getName()), component.getGenericType(), path(record, component));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("responseBodies")
    void GivenAResponseWithItsNullableComponentsUnset_WhenTheMapperWritesIt_ThenEachIsStillAKeyHoldingNull(
            Class<?> record) {
        JsonNode json = mapper.valueToTree(sample(record, false));

        assertThat(json.propertyNames())
                .describedAs(
                        "with its nullable components unset, the keys Jackson writes for %s are not its"
                                + " components; the generated interface declares every response key present, with"
                                + " null for a value the API does not have (#117)",
                        record.getSimpleName())
                .containsExactlyInAnyOrderElementsOf(componentNames(record));
        for (RecordComponent component : record.getRecordComponents()) {
            if (isNullable(component)) {
                assertThat(json.get(component.getName()).isNull())
                        .describedAs(
                                "%s is @Nullable and was not set, so it must be written as null",
                                path(record, component))
                        .isTrue();
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("requestBodies")
    void GivenARequestSpelledAsTheGeneratedTypeSaysWithEveryKey_WhenTheMapperReadsIt_ThenEveryComponentIsBound(
            Class<?> record) {
        Object expected = sample(record, true);

        String json = mapper.writeValueAsString(asRendered(expected, record, true));

        assertThat(mapper.readValue(json, record))
                .describedAs(
                        "Jackson did not bind %s from the keys and enum names its generated interface declares: %s",
                        record.getSimpleName(), json)
                .isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("requestBodies")
    void GivenARequestWithoutTheKeysTheGeneratedTypeMarksOptional_WhenTheMapperReadsIt_ThenEachIsBoundAsNull(
            Class<?> record) {
        Object expected = sample(record, false);

        String json = mapper.writeValueAsString(asRendered(expected, record, false));

        assertThat(mapper.readValue(json, record))
                .describedAs(
                        "Jackson did not bind %s with its optional keys left out, which the generated"
                                + " interface allows: %s",
                        record.getSimpleName(), json)
                .isEqualTo(expected);
    }

    private static Stream<Class<?>> recordsCrossing(Direction direction) {
        return FrontendWireTypes.records(FrontendWireTypes.apiControllers()).entrySet().stream()
                .filter(entry -> entry.getValue() == direction)
                .map(Map.Entry::getKey)
                .sorted(Comparator.comparing(Class::getName));
    }

    /** Asserts a written value has the JSON kind its type is rendered as, and an enum its constant name. */
    private static void assertRendered(JsonNode value, Type type, String path) {
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> raw) {
            Type[] arguments = parameterized.getActualTypeArguments();
            if (Collection.class.isAssignableFrom(raw)) {
                assertKind(value, JsonNodeType.ARRAY, path);
                value.values().forEach(element -> assertRendered(element, arguments[0], path + "[]"));
                return;
            }
            if (Map.class.isAssignableFrom(raw)) {
                assertKind(value, JsonNodeType.OBJECT, path);
                value.values().forEach(entry -> assertRendered(entry, arguments[1], path + "{}"));
                return;
            }
        }
        Class<?> cls = (Class<?>) type;
        if (FrontendWireTypes.STRINGS.contains(cls)) {
            assertKind(value, JsonNodeType.STRING, path);
        } else if (FrontendWireTypes.NUMBERS.contains(cls)) {
            assertKind(value, JsonNodeType.NUMBER, path);
        } else if (FrontendWireTypes.BOOLEANS.contains(cls)) {
            assertKind(value, JsonNodeType.BOOLEAN, path);
        } else if (cls.isEnum()) {
            assertThat(value.isString() ? value.asString() : value.toString())
                    .describedAs(
                            "%s is written differently from its constant's name, which the generated union"
                                    + " spells; an enum's toString() is what Jackson 3 writes by default",
                            path)
                    .isEqualTo(((Enum<?>) cls.getEnumConstants()[0]).name());
        } else {
            // A nested record is written as an object; its own keys are checked as a body of their own.
            assertKind(value, JsonNodeType.OBJECT, path);
        }
    }

    private static void assertKind(JsonNode value, JsonNodeType expected, String path) {
        assertThat(value.getNodeType())
                .describedAs("%s is written as %s, but its generated type is a JSON %s", path, value, expected)
                .isEqualTo(expected);
    }

    /**
     * A value of the given type with every component that admits null filled in, or left null.
     *
     * <p>An enum takes its first constant, so {@link #assertRendered} knows the name to expect.
     */
    private static @Nullable Object sample(Type type, boolean fillNullable) {
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> raw) {
            Type[] arguments = parameterized.getActualTypeArguments();
            if (Set.class.isAssignableFrom(raw)) {
                return Set.of(sampleOf(arguments[0], fillNullable));
            }
            if (Collection.class.isAssignableFrom(raw)) {
                return List.of(sampleOf(arguments[0], fillNullable));
            }
            if (Map.class.isAssignableFrom(raw)) {
                return Map.of(sampleOf(arguments[0], fillNullable), sampleOf(arguments[1], fillNullable));
            }
        }
        Class<?> cls = (Class<?>) type;
        if (cls == String.class) return "text";
        if (cls == UUID.class) return UUID.fromString("00000000-0000-4000-8000-000000000001");
        if (cls == LocalDate.class) return LocalDate.of(2026, 3, 1);
        if (cls == LocalDateTime.class) return LocalDateTime.of(2026, 3, 1, 9, 30, 15);
        if (cls == LocalTime.class) return LocalTime.of(9, 30);
        if (cls == Instant.class) return Instant.parse("2026-03-01T09:30:15Z");
        if (cls == int.class || cls == Integer.class) return 1;
        if (cls == long.class || cls == Long.class) return 1L;
        if (cls == double.class || cls == Double.class) return 1.5;
        if (cls == float.class || cls == Float.class) return 1.5f;
        if (cls == short.class || cls == Short.class) return (short) 1;
        if (cls == byte.class || cls == Byte.class) return (byte) 1;
        if (cls == boolean.class || cls == Boolean.class) return true;
        if (cls.isEnum()) return cls.getEnumConstants()[0];
        if (cls.isRecord()) return instantiate(cls, fillNullable);
        throw new IllegalStateException("no sample for " + type.getTypeName() + "; the generator maps nothing else");
    }

    /** {@link #sample}, for a collection element or a map entry, which the generator never renders as null. */
    private static Object sampleOf(Type type, boolean fillNullable) {
        Object value = sample(type, fillNullable);
        if (value == null) {
            throw new IllegalStateException("no sample for " + type.getTypeName());
        }
        return value;
    }

    private static Object instantiate(Class<?> record, boolean fillNullable) {
        RecordComponent[] components = record.getRecordComponents();
        Object[] values = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            values[i] = isNullable(components[i]) && !fillNullable
                    ? null
                    : sample(components[i].getGenericType(), fillNullable);
        }
        try {
            Constructor<?> canonical = record.getDeclaredConstructor(
                    Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new));
            canonical.setAccessible(true);
            return canonical.newInstance(values);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not build a sample " + record.getName(), e);
        }
    }

    /**
     * A value as the generated types spell it, built without Jackson: a record's keys are its component
     * names, an enum is its constant's name, and every other value is its ISO-8601 or numeric text.
     *
     * @param withOptionalKeys whether a request's {@code @Nullable} components keep their keys
     */
    private static @Nullable Object asRendered(@Nullable Object value, Type type, boolean withOptionalKeys) {
        if (value == null) {
            return null;
        }
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> raw) {
            Type[] arguments = parameterized.getActualTypeArguments();
            if (Collection.class.isAssignableFrom(raw)) {
                List<@Nullable Object> elements = new ArrayList<>();
                ((Collection<?>) value)
                        .forEach(element -> elements.add(asRendered(element, arguments[0], withOptionalKeys)));
                return elements;
            }
            if (Map.class.isAssignableFrom(raw)) {
                Map<String, @Nullable Object> entries = new LinkedHashMap<>();
                ((Map<?, ?>) value)
                        .forEach((key, entry) -> entries.put(
                                key instanceof Enum<?> constant ? constant.name() : key.toString(),
                                asRendered(entry, arguments[1], withOptionalKeys)));
                return entries;
            }
        }
        if (value instanceof Enum<?> constant) {
            return constant.name();
        }
        if (value instanceof Record) {
            Map<String, @Nullable Object> keys = new LinkedHashMap<>();
            for (RecordComponent component : value.getClass().getRecordComponents()) {
                Object componentValue = read(component, value);
                if (componentValue != null || withOptionalKeys || !isNullable(component)) {
                    keys.put(
                            component.getName(),
                            asRendered(componentValue, component.getGenericType(), withOptionalKeys));
                }
            }
            return keys;
        }
        return value instanceof Number || value instanceof Boolean ? value : value.toString();
    }

    private static @Nullable Object read(RecordComponent component, Object record) {
        try {
            return component.getAccessor().invoke(record);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not read " + component.getName() + " of " + record, e);
        }
    }

    private static boolean isNullable(RecordComponent component) {
        return component.getAnnotatedType().isAnnotationPresent(Nullable.class);
    }

    private static List<String> componentNames(Class<?> record) {
        return Arrays.stream(record.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    private static String path(Class<?> record, RecordComponent component) {
        return record.getSimpleName() + "." + component.getName();
    }
}
