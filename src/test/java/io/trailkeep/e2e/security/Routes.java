// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.e2e.security;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Every route the application maps, read from Spring MVC's own handler mapping, so a sweep over them covers a new
 * controller without being told about it. Only Trailkeep's handlers count: Spring Boot's error controller is not a
 * route anyone wrote.
 */
final class Routes {

    private static final Pattern VARIABLE = Pattern.compile("\\{(\\w+)(?::([^}]+))?}");
    private static final Set<RequestMethod> MUTATING =
            Set.of(RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE);

    private Routes() {}

    /**
     * One mapped method and pattern.
     *
     * @param method the HTTP method
     * @param pattern the path pattern as written in the controller, such as {@code /cases/{number}/edit}
     * @param handler the controller class and method, for messages
     */
    record Route(RequestMethod method, String pattern, String handler) {

        boolean mutating() {
            return MUTATING.contains(method);
        }

        boolean caseRooted() {
            return pattern.contains("{number}");
        }

        /** The variable names in the pattern, in order. */
        List<String> variables() {
            List<String> names = new ArrayList<>();
            Matcher matcher = VARIABLE.matcher(pattern);
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
            return names;
        }

        /**
         * The concrete paths for this pattern: each variable replaced by the value {@code values} gives for its name,
         * and a variable constrained to alternatives ({@code {change:pin|unpin}}) expanded to one path per alternative.
         *
         * @param values the value of each variable by name; it throws for a name it cannot fill
         * @return the paths, at least one
         */
        List<String> expand(Function<String, String> values) {
            List<String> paths = List.of("");
            Matcher matcher = VARIABLE.matcher(pattern);
            int last = 0;
            while (matcher.find()) {
                String literal = pattern.substring(last, matcher.start());
                String constraint = matcher.group(2);
                List<String> choices = constraint == null
                        ? List.of(values.apply(matcher.group(1)))
                        : List.of(constraint.split("\\|", -1));
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
    }

    /**
     * Every Trailkeep route of the mapping, sorted by pattern then method. A mapping with no method condition would
     * answer every method; there is none today, and one would be listed under each mutating method.
     *
     * @param mapping the application's request mapping
     * @return the routes
     */
    static List<Route> of(RequestMappingHandlerMapping mapping) {
        List<Route> routes = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry :
                mapping.getHandlerMethods().entrySet()) {
            HandlerMethod handler = entry.getValue();
            if (!handler.getBeanType().getName().startsWith("io.trailkeep.")) {
                continue;
            }
            Set<RequestMethod> methods = entry.getKey().getMethodsCondition().getMethods();
            Set<RequestMethod> effective = methods.isEmpty() ? MUTATING : methods;
            String name = handler.getBeanType().getSimpleName() + "."
                    + handler.getMethod().getName();
            for (String pattern : entry.getKey().getPatternValues()) {
                for (RequestMethod method : effective) {
                    routes.add(new Route(method, pattern, name));
                }
            }
        }
        routes.sort(Comparator.comparing(Route::pattern).thenComparing(Route::method));
        return List.copyOf(routes);
    }
}
