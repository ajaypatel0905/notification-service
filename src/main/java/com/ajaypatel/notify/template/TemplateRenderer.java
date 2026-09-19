package com.ajaypatel.notify.template;

import com.ajaypatel.notify.common.error.ValidationException;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mustache-style {@code {{variable}}} substitution with dotted paths into nested maps.
 * Strict by default: a placeholder with no value fails the request instead of sending "{{name}}" to a customer.
 */
@Component
public class TemplateRenderer {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_.]*)\\s*}}");

    public String render(String template, Map<String, Object> variables) {
        if (template == null) {
            return null;
        }
        Map<String, Object> vars = variables == null ? Collections.emptyMap() : variables;
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder(template.length() + 32);
        Set<String> missing = new LinkedHashSet<>();
        while (m.find()) {
            Object value = resolve(vars, m.group(1));
            if (value == null) {
                missing.add(m.group(1));
                m.appendReplacement(out, "");
            } else {
                m.appendReplacement(out, Matcher.quoteReplacement(String.valueOf(value)));
            }
        }
        m.appendTail(out);
        if (!missing.isEmpty()) {
            throw new ValidationException("Missing template variables: " + missing);
        }
        return out.toString();
    }

    public Set<String> placeholders(String template) {
        Set<String> names = new LinkedHashSet<>();
        if (template == null) {
            return names;
        }
        Matcher m = PLACEHOLDER.matcher(template);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    private static Object resolve(Map<String, Object> vars, String path) {
        Object cur = vars;
        for (String part : path.split("\\.")) {
            if (!(cur instanceof Map<?, ?> map)) {
                return null;
            }
            cur = map.get(part);
            if (cur == null) {
                return null;
            }
        }
        return cur;
    }
}
