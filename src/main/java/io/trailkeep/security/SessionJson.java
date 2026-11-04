// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.security;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.net.URL;
import java.util.Collection;
import org.jspecify.annotations.Nullable;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.jackson.SecurityJacksonModules;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

/**
 * Session attributes as JSON (ADR-0008): Spring Security's Jackson modules with their allow-listing type validator,
 * extended by {@link TrailkeepOidcUser} and by {@link URL}, the type Spring's ID-token claim converter gives the
 * {@code iss} claim (a value type: reading one opens no connection). No JDK serialization: an attribute whose type
 * is not allow-listed is not read back.
 */
final class SessionJson {

    private SessionJson() {}

    /** The mapper Spring Session uses for every attribute. */
    static JsonMapper mapper() {
        ClassLoader loader = SessionJson.class.getClassLoader();
        BasicPolymorphicTypeValidator.Builder types = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType(TrailkeepOidcUser.class)
                .allowIfSubType(URL.class);
        return JsonMapper.builder()
                .addModules(SecurityJacksonModules.getModules(loader, types))
                .addMixIn(TrailkeepOidcUser.class, TrailkeepOidcUserMixin.class)
                .build();
    }

    /** The {@code springSessionConversionService}: attribute object to JSON bytes and back. */
    static GenericConversionService conversionService() {
        JsonMapper mapper = mapper();
        GenericConversionService conversions = new GenericConversionService();
        conversions.addConverter(Object.class, byte[].class, mapper::writeValueAsBytes);
        conversions.addConverter(byte[].class, Object.class, bytes -> mapper.readValue(bytes, Object.class));
        return conversions;
    }

    /** How {@link TrailkeepOidcUser} is written and read back (fields, as Spring Security's own mixins do). */
    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
    @JsonAutoDetect(
            fieldVisibility = JsonAutoDetect.Visibility.ANY,
            getterVisibility = JsonAutoDetect.Visibility.NONE,
            isGetterVisibility = JsonAutoDetect.Visibility.NONE)
    @JsonIgnoreProperties(ignoreUnknown = true)
    abstract static class TrailkeepOidcUserMixin {

        @JsonCreator
        TrailkeepOidcUserMixin(
                @JsonProperty("userId") long userId,
                @JsonProperty("displayName") String displayName,
                @JsonProperty("authorities") Collection<? extends GrantedAuthority> authorities,
                @JsonProperty("idToken") OidcIdToken idToken,
                @JsonProperty("userInfo") @Nullable OidcUserInfo userInfo) {}
    }
}
