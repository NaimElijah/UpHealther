package com.healthupgrades.auth.adapter.in.web;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import com.healthupgrades.auth.application.CommonPasswordPolicy;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * The annotated password is not on the common-password list (BR-17, ADR-071). Null is valid; pair it
 * with {@code @NotBlank} where a value is required. A value longer than
 * {@link AuthController#PASSWORD_MAX} is valid too, and left to {@code @Size} to refuse.
 *
 * <p>A constraint rather than a check in {@code AuthService}, so that a common password is refused the
 * way a short one is: a 400 whose {@code fieldErrors.password} says why, before the use case runs.
 * Whether a password is common is {@link CommonPasswordPolicy}'s decision, and this only asks it.
 */
@Documented
@Constraint(validatedBy = NotCommonPassword.Validator.class)
@Target({FIELD, METHOD, PARAMETER, ANNOTATION_TYPE})
@Retention(RUNTIME)
public @interface NotCommonPassword {

    /** Lowercase and mid-sentence in shape, like the built-in messages it is shown beside. */
    String message() default "is one of the most common passwords; choose one that is harder to guess";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /**
     * Asks the policy. Spring's constraint-validator factory builds it, injecting the policy through the
     * constructor.
     */
    class Validator implements ConstraintValidator<NotCommonPassword, CharSequence> {

        private final CommonPasswordPolicy policy;

        public Validator(CommonPasswordPolicy policy) {
            this.policy = policy;
        }

        /**
         * Valid, without normalising, for a value longer than {@link AuthController#PASSWORD_MAX}:
         * {@code @Size} refuses it anyway, and NFKC can expand one code point to eighteen, so normalising
         * it would be work spent on a password already refused.
         */
        @Override
        public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
            return value == null || value.length() > AuthController.PASSWORD_MAX || !policy.isCommon(value.toString());
        }
    }
}
