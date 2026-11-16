// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Keeps {@code security/zap/openapi.yaml}, the hand-written description ZAP's API scan imports (WP-28 part d, decision
 * Q2), complete and exact without a runtime generator. It checks that:
 *
 * <ul>
 *   <li>every operation a controller maps is in it with each request parameter it binds, and so are the endpoints
 *       Spring Security and the management server serve; nothing else is;
 *   <li>every POST sends the scan session's CSRF token in {@code X-CSRF-TOKEN}, so the scan reaches the handlers
 *       rather than Spring Security's 403, except the two that must not;
 *   <li>the actuator operations are requested on the management port, 8081, and everything else on 8080.
 * </ul>
 *
 * <p>A new or changed handler fails this test until the file describes it. Constrained path variables such as
 * {@code {change:pin|unpin}} are listed as one path per alternative, as OpenAPI has no pattern syntax for paths.
 */
class OpenApiCoverageTest {

    private static final Path SPEC = Path.of("security/zap/openapi.yaml");
    private static final Pattern VARIABLE = Pattern.compile("\\{(\\w+)(?::([^}]+))?}");
    private static final String CSRF_HEADER = "X-CSRF-TOKEN";
    private static final String APPLICATION = "http://localhost:8080";
    private static final String MANAGEMENT = "http://localhost:8081";

    /** Served by Spring Security's filters and the management server, not by a controller. */
    private static final List<String> FRAMEWORK = List.of(
            "post /logout",
            "post /logout/connect/back-channel/idp",
            "get /actuator",
            "get /actuator/health",
            "get /actuator/health/liveness",
            "get /actuator/health/readiness",
            "get /actuator/info",
            "get /actuator/prometheus");

    /**
     * POSTs without the session's token: signing out would end the scan's own session (the scan's context also
     * excludes it), and the provider's back-channel logout carries no session and is exempt from CSRF.
     */
    private static final Set<String> WITHOUT_TOKEN = Set.of("post /logout", "post /logout/connect/back-channel/idp");

    private final Map<String, Object> spec = load();

    @Test
    void everyMappedOperationIsDescribedWithTheParametersItBinds() {
        Map<String, Object> paths = map(spec.get("paths"));
        List<String> missing = new ArrayList<>();
        List<Route> routes = routes();
        for (Route route : routes) {
            check(paths, route, missing);
        }
        for (String endpoint : FRAMEWORK) {
            String[] parts = endpoint.split(" ", 2);
            if (operation(paths, parts[0], parts[1]) == null) {
                missing.add(endpoint);
            }
        }
        assertThat(routes).as("mapped operations found").hasSizeGreaterThanOrEqualTo(70);
        assertThat(missing).as("missing from " + SPEC).isEmpty();
    }

    @Test
    void describesNoOperationThatNothingServes() {
        Set<String> served = new TreeSet<>(FRAMEWORK);
        routes().forEach(route -> served.add(route.key()));
        assertThat(operations(map(spec.get("paths"))).keySet())
                .as("operations in " + SPEC + " that no handler, filter or endpoint serves")
                .isSubsetOf(served);
    }

    @Test
    void everyPostCarriesTheSessionsCsrfTokenExceptTheTwoThatMustNot() {
        Map<String, Object> csrf =
                map(map(map(spec.get("components")).get("parameters")).get("csrf"));
        assertThat(csrf)
                .containsEntry("name", CSRF_HEADER)
                .containsEntry("in", "header")
                .containsEntry("required", true);
        assertThat(map(csrf.get("schema"))).containsEntry("example", "__CSRF_TOKEN__");
        List<String> wrong = new ArrayList<>();
        operations(map(spec.get("paths"))).forEach((key, operation) -> {
            boolean carries = described(operation).contains(CSRF_HEADER);
            boolean must = key.startsWith("post ") && !WITHOUT_TOKEN.contains(key);
            if (carries != must) {
                wrong.add(key + (must ? " lacks " : " must not carry ") + CSRF_HEADER);
            }
        });
        assertThat(wrong).isEmpty();
    }

    @Test
    void theActuatorIsRequestedOnTheManagementPortAndEverythingElseOnTheApplicationPort() {
        assertThat(servers(spec.get("servers"))).containsExactly(APPLICATION);
        List<String> wrong = new ArrayList<>();
        map(spec.get("paths")).forEach((path, item) -> {
            List<String> servers = servers(map(item).get("servers"));
            List<String> expected = path.startsWith("/actuator") ? List.of(MANAGEMENT) : List.of();
            if (!servers.equals(expected)) {
                wrong.add(path + " has servers " + servers + ", expected " + expected);
            }
        });
        assertThat(wrong).isEmpty();
    }

    /** One handler method at one concrete path. */
    private record Route(String verb, String path, Method method) {

        String key() {
            return verb + " " + path;
        }
    }

    /** Every operation a controller maps, one per HTTP method and concrete path. */
    private static List<Route> routes() {
        List<Route> routes = new ArrayList<>();
        for (Class<?> controller : controllers()) {
            String prefix = prefix(controller);
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                assertThat(mapping.method())
                        .as(method + " maps every HTTP method; name the one it serves")
                        .isNotEmpty();
                for (RequestMethod verb : mapping.method()) {
                    for (String pattern : patterns(prefix, mapping)) {
                        expand(pattern)
                                .forEach(path ->
                                        routes.add(new Route(verb.name().toLowerCase(Locale.ROOT), path, method)));
                    }
                }
            }
        }
        return routes;
    }

    private void check(Map<String, Object> paths, Route route, List<String> missing) {
        Map<String, Object> operation = operation(paths, route.verb(), route.path());
        if (operation == null) {
            Method method = route.method();
            missing.add(route.key() + " (" + method.getDeclaringClass().getSimpleName() + "." + method.getName()
                    + ", binding " + bound(method) + ")");
            return;
        }
        Set<String> described = described(operation);
        for (String name : bound(route.method())) {
            if (!described.contains(name)) {
                missing.add(route.key() + " parameter " + name);
            }
        }
    }

    /**
     * The request parameters a handler binds: each named {@code @RequestParam} (a whole-map one names none), and each
     * component of a form record.
     */
    private static Set<String> bound(Method method) {
        Set<String> names = new TreeSet<>();
        for (Parameter parameter : method.getParameters()) {
            RequestParam param = parameter.getAnnotation(RequestParam.class);
            if (param != null) {
                names.addAll(requestParam(param, parameter));
            } else if (isFormRecord(parameter)) {
                for (RecordComponent component : parameter.getType().getRecordComponents()) {
                    names.add(component.getName());
                }
            }
        }
        return names;
    }

    private static List<String> requestParam(RequestParam param, Parameter parameter) {
        if (Map.class.isAssignableFrom(parameter.getType())) {
            return List.of();
        }
        String name = param.name().isEmpty() ? param.value() : param.name();
        return List.of(name.isEmpty() ? parameter.getName() : name);
    }

    private static boolean isFormRecord(Parameter parameter) {
        Class<?> type = parameter.getType();
        return type.isRecord()
                && parameter.getAnnotation(PathVariable.class) == null
                && !ClassUtils.isSimpleValueType(type);
    }

    /**
     * The names of an operation's query, path, header and form parameters, and of its multipart parts, with each
     * {@code $ref} resolved.
     */
    private Set<String> described(Map<String, Object> operation) {
        Set<String> names = new TreeSet<>();
        Object parameters = operation.get("parameters");
        if (parameters instanceof List<?> list) {
            list.forEach(entry -> names.add(String.valueOf(resolved(entry).get("name"))));
        }
        Object body = operation.get("requestBody");
        if (body != null) {
            for (Object media : map(resolved(body).get("content")).values()) {
                Object properties = resolved(map(media).get("schema")).get("properties");
                if (properties != null) {
                    names.addAll(map(properties).keySet());
                }
            }
        }
        return names;
    }

    /** The object a {@code #/components/<kind>/<name>} reference names, or the value itself. */
    private Map<String, Object> resolved(@Nullable Object value) {
        Object ref = map(value).get("$ref");
        if (ref == null) {
            return map(value);
        }
        String[] parts = String.valueOf(ref).split("/", -1);
        assertThat(parts).as("reference " + ref).hasSize(4).startsWith("#", "components");
        return map(map(map(spec.get("components")).get(parts[2])).get(parts[3]));
    }

    /** Every operation in the file, keyed {@code "<verb> <path>"}. */
    private static Map<String, Map<String, Object>> operations(Map<String, Object> paths) {
        Map<String, Map<String, Object>> operations = new TreeMap<>();
        paths.forEach((path, item) -> map(item).forEach((verb, operation) -> {
            if (!"servers".equals(verb) && !"parameters".equals(verb)) {
                operations.put(verb + " " + path, map(operation));
            }
        }));
        return operations;
    }

    private static @Nullable Map<String, Object> operation(Map<String, Object> paths, String verb, String path) {
        Object item = paths.get(path);
        return item == null || map(item).get(verb) == null
                ? null
                : map(map(item).get(verb));
    }

    private static List<String> servers(@Nullable Object servers) {
        return servers instanceof List<?> list
                ? list.stream()
                        .map(server -> String.valueOf(map(server).get("url")))
                        .toList()
                : List.of();
    }

    /**
     * Every {@code @Controller} of the application, whatever its conditions: the scanner's own candidate check would
     * evaluate them without a web application and drop, for one, every {@code @ConditionalOnWebApplication}
     * controller. The test-only controllers on this test's class path are not the application's.
     */
    private static List<Class<?>> controllers() {
        AnnotationTypeFilter controller = new AnnotationTypeFilter(Controller.class);
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(MetadataReader reader) throws IOException {
                return controller.match(reader, getMetadataReaderFactory())
                        && !reader.getResource().getURI().toString().contains("/test-classes/");
            }
        };
        List<Class<?>> types = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("io.trailkeep")) {
            types.add(ClassUtils.resolveClassName(
                    Objects.requireNonNull(definition.getBeanClassName()), OpenApiCoverageTest.class.getClassLoader()));
        }
        assertThat(types).as("controllers found").hasSizeGreaterThan(20);
        return types;
    }

    private static String prefix(Class<?> controller) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
        return mapping == null || mapping.path().length == 0 ? "" : mapping.path()[0];
    }

    private static List<String> patterns(String prefix, RequestMapping mapping) {
        if (mapping.path().length == 0) {
            return List.of(prefix);
        }
        return Arrays.stream(mapping.path()).map(path -> prefix + path).toList();
    }

    /** One path per alternative of each constrained variable, the others kept as {@code {name}}. */
    static List<String> expand(String pattern) {
        List<String> paths = List.of("");
        Matcher matcher = VARIABLE.matcher(pattern);
        int last = 0;
        while (matcher.find()) {
            String literal = pattern.substring(last, matcher.start());
            List<String> choices = matcher.group(2) == null
                    ? List.of("{" + matcher.group(1) + "}")
                    : List.of(matcher.group(2).split("\\|", -1));
            List<String> next = new ArrayList<>();
            for (String path : paths) {
                for (String choice : choices) {
                    next.add(path + literal + choice);
                }
            }
            paths = next;
            last = matcher.end();
        }
        String tail = pattern.substring(last);
        return paths.stream().map(path -> path + tail).toList();
    }

    private static Map<String, Object> load() {
        try (Reader reader = Files.newBufferedReader(SPEC)) {
            return map(new Yaml(new SafeConstructor(new LoaderOptions())).load(reader));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked") // SnakeYAML's safe constructor yields maps and lists of plain values
    private static Map<String, Object> map(@Nullable Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) Objects.requireNonNull(value);
    }
}
