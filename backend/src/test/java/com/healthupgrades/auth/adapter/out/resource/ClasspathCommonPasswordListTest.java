package com.healthupgrades.auth.adapter.out.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.healthupgrades.auth.application.CommonPasswordPolicy;
import com.healthupgrades.auth.domain.port.out.CommonPasswordListPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Reading the common-password list from the classpath, and refusing to start without one (ADR-071).
 *
 * <p>A deployment whose jar lost the list would otherwise accept every password, and nothing would
 * say so. The context tests start only the two beans involved, so they need no database.
 */
class ClasspathCommonPasswordListTest {

    /** Not on the classpath. */
    private static final String MISSING = "auth/no-such-common-passwords.txt";

    /** On the test classpath, and empty. */
    private static final String EMPTY = "auth/empty-common-passwords.txt";

    /** On the test classpath, and nothing but line breaks. */
    private static final String BLANK = "auth/blank-common-passwords.txt";

    @Test
    void GivenTheShippedResource_WhenItIsRead_ThenEveryEntryComesBackInOrder() {
        assertThat(new ClasspathCommonPasswordList().entries())
                .hasSize(CommonPasswordListTest.ENTRIES)
                .startsWith("123456789", "password", "12345678");
    }

    @Test
    void GivenNoResourceAtTheLocation_WhenItIsRead_ThenItFailsNamingTheLocation() {
        assertThatThrownBy(() -> new ClasspathCommonPasswordList(MISSING).entries())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(MISSING);
    }

    @Test
    void GivenAnEmptyResource_WhenItIsRead_ThenItFailsNamingTheLocation() {
        assertThatThrownBy(() -> new ClasspathCommonPasswordList(EMPTY).entries())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(EMPTY);
    }

    @Test
    void GivenAResourceOfOnlyLineBreaks_WhenItIsRead_ThenItFailsNamingTheLocation() {
        // Read line by line, it is a list of blank entries, and a blank password never reaches the list:
        // @NotBlank refuses it first. A list of nothing else would refuse nothing.
        assertThatThrownBy(() -> new ClasspathCommonPasswordList(BLANK).entries())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(BLANK);
    }

    @Test
    void GivenNoResourceAtTheLocation_WhenTheApplicationStarts_ThenItFailsToStart() {
        contextReading(MISSING)
                .run(context ->
                        assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining(MISSING));
    }

    @Test
    void GivenAnEmptyResource_WhenTheApplicationStarts_ThenItFailsToStart() {
        contextReading(EMPTY)
                .run(context ->
                        assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining(EMPTY));
    }

    @Test
    void GivenAResourceOfOnlyLineBreaks_WhenTheApplicationStarts_ThenItFailsToStart() {
        contextReading(BLANK)
                .run(context ->
                        assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining(BLANK));
    }

    /** The policy and the list it loads at startup, with the list read from {@code location}. */
    private static ApplicationContextRunner contextReading(String location) {
        return new ApplicationContextRunner()
                .withBean(CommonPasswordListPort.class, () -> new ClasspathCommonPasswordList(location))
                .withBean(CommonPasswordPolicy.class);
    }
}
