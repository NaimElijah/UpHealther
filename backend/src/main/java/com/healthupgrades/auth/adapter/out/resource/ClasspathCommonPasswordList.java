package com.healthupgrades.auth.adapter.out.resource;

import com.healthupgrades.auth.domain.port.out.CommonPasswordListPort;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Outbound adapter: reads the common-password list bundled in the jar, one entry per line (ADR-071).
 *
 * <p>The file is {@code scripts/derive_common_passwords.py}'s output, and its notice sits beside it. A
 * missing file, an empty one and one of only blank lines are refused here, because a jar without the
 * list would otherwise accept every password and nothing would say so.
 */
@Component
public class ClasspathCommonPasswordList implements CommonPasswordListPort {

    /** Where the list is on the classpath, under {@code src/main/resources}. */
    static final String LOCATION = "auth/common-passwords.txt";

    private final String location;

    /** Reads the list the jar ships, at {@link #LOCATION}. */
    public ClasspathCommonPasswordList() {
        this(LOCATION);
    }

    /** Reads a list from elsewhere on the classpath, so a test can point it at a missing or empty one. */
    ClasspathCommonPasswordList(String location) {
        this.location = location;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalStateException if nothing is at the location, or it holds no entries but blank lines
     * @throws UncheckedIOException  if the resource exists and cannot be read
     */
    @Override
    public List<String> entries() {
        ClassPathResource resource = new ClassPathResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("The common-password list is not on the classpath at " + location);
        }
        List<String> entries = read(resource);
        if (entries.isEmpty()) {
            throw new IllegalStateException("The common-password list at " + location + " holds no entries");
        }
        return entries;
    }

    /**
     * The non-blank lines. A blank entry could never match, because {@code @NotBlank} refuses a blank
     * password first, so a file of nothing but line breaks counts as empty rather than as a list.
     */
    private List<String> read(ClassPathResource resource) {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .filter(line -> !line.isBlank())
                    .toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("The common-password list at " + location + " cannot be read", unreadable);
        }
    }
}
