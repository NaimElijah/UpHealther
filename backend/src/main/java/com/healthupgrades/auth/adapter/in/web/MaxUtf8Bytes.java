package com.healthupgrades.auth.adapter.in.web;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;

/**
 * The annotated text, encoded as UTF-8, is at most {@link #value()} bytes. Null is valid; pair it with
 * {@code @NotBlank} where a value is required.
 *
 * <p>{@code @Size} counts UTF-16 code units, which is the wrong unit wherever the limit downstream is a
 * byte count. BCrypt's is (BR-17): an "é" is one code unit and two bytes, so a password of 72 or fewer
 * characters can still be past what BCrypt accepts.
 */
@Documented
@Constraint(validatedBy = MaxUtf8Bytes.Validator.class)
@Target({FIELD, METHOD, PARAMETER, ANNOTATION_TYPE})
@Retention(RUNTIME)
public @interface MaxUtf8Bytes {

    /** The most bytes the UTF-8 encoding may take. */
    int value();

    /** Lowercase and mid-sentence in shape, like the built-in messages it is shown beside. */
    String message() default "must be at most {value} bytes; an accented letter takes two and an emoji four";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** Measures the text the way BCrypt will read it. */
    class Validator implements ConstraintValidator<MaxUtf8Bytes, CharSequence> {

        private int maxBytes;

        @Override
        public void initialize(MaxUtf8Bytes constraint) {
            maxBytes = constraint.value();
        }

        @Override
        public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
            return value == null || value.toString().getBytes(StandardCharsets.UTF_8).length <= maxBytes;
        }
    }
}
