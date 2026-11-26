package net.minetake.mcinsight.reflect;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.CodeSource;
import java.util.concurrent.ConcurrentHashMap;
import net.minetake.mcinsight.api.InsightError;
import net.minetake.mcinsight.api.InsightException;
import net.minetake.mcinsight.api.Params;
import net.minetake.mcinsight.api.RequestContext;
import net.minetake.mcinsight.metrics.PerfMetrics;

/**
 * Answers {@code introspect_class}: the declared shape of a loaded Java class.
 *
 * <p>This is the escape hatch for "the JSON does not explain what this object is", so it reports
 * the class the JVM actually loaded rather than what a descriptor claims, and it caches per class
 * name because a class's declared members do not change while it is loaded.
 */
public final class ClassIntrospector {

    private static final int MAX_CACHE_ENTRIES = 512;
    private static final ConcurrentHashMap<String, JsonObject> CACHE = new ConcurrentHashMap<>();

    private ClassIntrospector() {}

    public static JsonObject inspect(RequestContext ctx, Params p) throws Exception {
        String name = p.required("name", InsightError.INVALID_CLASS).trim();
        if (name.isEmpty()) {
            throw new InsightException(InsightError.INVALID_CLASS, "Parameter 'name' must be a class name.");
        }
        JsonObject cached = CACHE.get(name);
        if (cached != null) {
            PerfMetrics.markCacheHit();
            return cached.deepCopy();
        }
        JsonObject described = describe(name);
        if (CACHE.size() < MAX_CACHE_ENTRIES) {
            CACHE.putIfAbsent(name, described);
        }
        return described.deepCopy();
    }

    private static JsonObject describe(String name) {
        Class<?> type;
        try {
            type = Class.forName(name, false, ClassIntrospector.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new InsightException(
                InsightError.CLASS_NOT_FOUND,
                "Class is not loaded: " + name + ". Use mc_inspect_code with action=\"search\" to verify the exact class name."
            );
        } catch (LinkageError | SecurityException e) {
            throw new InsightException(
                InsightError.CLASS_NOT_FOUND,
                "Class '" + name + "' could not be resolved: " + e.getMessage()
            );
        }

        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("name", type.getName());
        if (type.isInterface()) {
            out.addProperty("isInterface", true);
        }
        if (type.isEnum()) {
            out.addProperty("isEnum", true);
        }
        if (Modifier.isAbstract(type.getModifiers())) {
            out.addProperty("isAbstract", true);
        }
        Class<?> superclass = type.getSuperclass();
        if (superclass != null) {
            out.addProperty("superclass", superclass.getName());
        }

        JsonArray interfaces = new JsonArray();
        for (Class<?> implemented : type.getInterfaces()) {
            interfaces.add(implemented.getName());
        }
        out.add("interfaces", interfaces);

        JsonObject source = codeSource(type);
        if (source != null) {
            out.add("codeSource", source);
        }

        JsonArray fields = new JsonArray();
        for (Field field : type.getDeclaredFields()) {
            if (field.isSynthetic()) {
                continue;
            }
            JsonObject described = new JsonObject();
            described.addProperty("name", field.getName());
            described.addProperty("type", field.getType().getName());
            described.addProperty("modifiers", Modifier.toString(field.getModifiers()));
            fields.add(described);
        }
        out.add("declaredFields", fields);

        JsonArray methods = new JsonArray();
        for (Method method : type.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            JsonObject described = new JsonObject();
            described.addProperty("name", method.getName());
            described.addProperty("returnType", method.getReturnType().getName());
            described.addProperty("modifiers", Modifier.toString(method.getModifiers()));
            JsonArray parameters = new JsonArray();
            for (Class<?> parameter : method.getParameterTypes()) {
                parameters.add(parameter.getName());
            }
            described.add("parameterTypes", parameters);
            methods.add(described);
        }
        out.add("declaredMethods", methods);
        return out;
    }

    /**
     * Where the class was loaded from.
     *
     * <p>A class can have no protection domain (bootstrap classes) or a domain with no code source;
     * both are normal, and reporting the absence is more useful than omitting the section because a
     * caller comparing two classes needs to know they came from different places.
     */
    private static JsonObject codeSource(Class<?> type) {
        JsonObject out = new JsonObject();
        try {
            CodeSource source = type.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                out.addProperty("available", false);
                return out;
            }
            out.addProperty("available", true);
            out.addProperty("location", source.getLocation().toString());
        } catch (SecurityException e) {
            out.addProperty("available", false);
        }
        return out;
    }
}
