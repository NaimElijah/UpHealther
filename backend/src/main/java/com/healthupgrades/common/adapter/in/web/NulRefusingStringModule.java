package com.healthupgrades.common.adapter.in.web;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.jdk.StringDeserializer;
import tools.jackson.databind.module.SimpleModule;

/**
 * Refuses U+0000 in any string a request body carries, as the 400 for a malformed body.
 *
 * <p>PostgreSQL refuses the character in {@code text} and {@code varchar} alike, so a body carrying one
 * passed validation, reached the flush and came back as a 500, with an ERROR line written for it: alert
 * noise anybody could make on demand, an anonymous registration included (S5.B5, ADR-065, NFR-7). Refusing it here
 * means it is refused once, for every field of every body, before it reaches a use case. No field of
 * this API has a use for it.
 *
 * <p>A module rather than a constraint on each field, because a field added later would otherwise be
 * open until somebody remembered. Boot registers every {@code JacksonModule} bean with the mapper it
 * builds, so the HTTP message converters read through this. It covers JSON bodies only; no query
 * parameter or path variable in the API is stored as text.
 */
@Component
public final class NulRefusingStringModule extends SimpleModule {

    /** Registers the refusing deserializer for every {@code String} Jackson reads. */
    public NulRefusingStringModule() {
        super(NulRefusingStringModule.class.getSimpleName());
        addDeserializer(String.class, new NulRefusingStringDeserializer());
    }

    /** Jackson's own string deserializer, which then refuses what it read if it holds a U+0000. */
    static final class NulRefusingStringDeserializer extends StringDeserializer {

        private static final char NUL = '\u0000';

        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) throws JacksonException {
            String value = super.deserialize(parser, context);
            if (value != null && value.indexOf(NUL) >= 0) {
                return context.reportInputMismatch(this, "A string must not contain U+0000");
            }
            return value;
        }
    }
}
