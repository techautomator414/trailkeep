// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.importer.SourceQueryJdbcDao.Source;
import io.trailkeep.importer.SourceQueryValidator.Vendor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.core.env.Environment;
import tools.jackson.databind.JsonNode;

/**
 * Where a JDBC mapping's source is and who reads it (design §4.1, §10), for {@code validate-mapping}'s test fetch and
 * the extract alike. {@code source.url} and {@code source.user} are resolved from {@code import.source.*} names only
 * ({@link MappingValidator} has already refused any other placeholder); the password is {@code
 * import.source.password}, a Secret file read through {@code configtree}, never named in the mapping. The URL must pass
 * {@link SourceUrl} before anything connects: a driver URL can name classes to load and files to write.
 */
final class SourceAccount {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{(import\\.source\\.[a-z][a-z0-9.-]*)}");

    private SourceAccount() {}

    /**
     * The source a mapping names, resolved.
     *
     * @param mapping the validated mapping
     * @param vendor its vendor
     * @param environment where the {@code import.source.*} settings are
     * @return the source
     * @throws SourceException {@code SOURCE_SETTING_MISSING} if a placeholder or the password is not configured,
     *     {@code SOURCE_URL_NOT_ALLOWED} if the URL fails {@link SourceUrl}
     */
    static Source of(JsonNode mapping, Vendor vendor, Environment environment) {
        String url = resolve(mapping.at("/source/url").asString(), environment);
        String user = resolve(mapping.at("/source/user").asString(), environment);
        String password = environment.getProperty("import.source.password");
        if (url == null || user == null || password == null) {
            throw new SourceException("SOURCE_SETTING_MISSING", null);
        }
        if (!SourceUrl.allowed(vendor, url)) {
            throw new SourceException("SOURCE_URL_NOT_ALLOWED", null);
        }
        return new Source(url, user, password, vendor);
    }

    /** The text with each {@code import.source.*} placeholder replaced; {@code null} if one is not configured. */
    private static @Nullable String resolve(String text, Environment environment) {
        Matcher placeholders = PLACEHOLDER.matcher(text);
        StringBuilder resolved = new StringBuilder();
        while (placeholders.find()) {
            String value = environment.getProperty(placeholders.group(1));
            if (value == null) {
                return null;
            }
            placeholders.appendReplacement(resolved, Matcher.quoteReplacement(value));
        }
        placeholders.appendTail(resolved);
        return resolved.toString();
    }
}
