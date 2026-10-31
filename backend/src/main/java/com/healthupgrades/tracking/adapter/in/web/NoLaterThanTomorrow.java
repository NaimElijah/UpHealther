package com.healthupgrades.tracking.adapter.in.web;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import com.healthupgrades.common.time.ServerZone;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.time.Clock;
import java.time.LocalDate;

/**
 * The annotated date is no later than the server's tomorrow (FR-18). Null is valid: an entry with no date
 * is logged for today.
 *
 * <p>The bound is the server's today plus {@link #DAYS_AHEAD}, not today, because the date a user logs is
 * their own calendar day and the server keeps UTC (ADR-020): a user up to fourteen hours ahead of it is
 * already on the next day (#100). A day further than that is no zone's today, and a run of entries dated
 * in the coming week would announce a streak milestone that has not happened.
 *
 * <p>A constraint, so a date too far ahead is refused as the other bounds are: a 400 whose
 * {@code fieldErrors.date} says why, before the use case runs.
 */
@Documented
@Constraint(validatedBy = NoLaterThanTomorrow.Validator.class)
@Target({FIELD, METHOD, PARAMETER, ANNOTATION_TYPE})
@Retention(RUNTIME)
public @interface NoLaterThanTomorrow {

    /** How many days past the server's today a progress entry may be dated. */
    int DAYS_AHEAD = 1;

    /**
     * Lowercase and mid-sentence in shape, like the built-in messages it is shown beside. It names no
     * "tomorrow": the SPA shows it as it is, and a user far from UTC would read that as their own.
     */
    String message() default "must not be more than a day ahead";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /**
     * Compares with the server's today. Spring's constraint-validator factory builds it, injecting the
     * application's clock through the constructor, so a test fixes the day by fixing the clock.
     */
    class Validator implements ConstraintValidator<NoLaterThanTomorrow, LocalDate> {

        private final Clock clock;

        /** @param clock the application's clock; its day is read in {@link ServerZone}, whatever its own zone */
        public Validator(Clock clock) {
            this.clock = clock;
        }

        @Override
        public boolean isValid(LocalDate value, ConstraintValidatorContext context) {
            LocalDate latest =
                    LocalDate.ofInstant(clock.instant(), ServerZone.ZONE).plusDays(DAYS_AHEAD);
            return value == null || !value.isAfter(latest);
        }
    }
}
