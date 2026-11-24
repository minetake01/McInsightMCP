package dev.mcinsight.introspect;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcinsight.server.JsonUtil;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.CodeSource;

public final class ClassIntrospector {

    private ClassIntrospector() {}

    public static JsonObject inspect(String className) {
        if (className == null || className.isBlank()) {
            return JsonUtil.errorObject("INVALID_CLASS", "Query parameter 'name' is required.");
        }
        try {
            Class<?> clazz = Class.forName(className.trim(), false, ClassIntrospector.class.getClassLoader());
            JsonObject out = new JsonObject();
            out.addProperty("ok", true);
            out.addProperty("name", clazz.getName());
            if (clazz.getSuperclass() != null) {
                out.addProperty("superclass", clazz.getSuperclass().getName());
            }

            JsonArray ifaces = new JsonArray();
            for (Class<?> iface : clazz.getInterfaces()) {
                ifaces.add(iface.getName());
            }
            out.add("interfaces", ifaces);

            try {
                CodeSource cs = clazz.getProtectionDomain().getCodeSource();
                if (cs != null && cs.getLocation() != null) {
                    out.addProperty("codeSource", cs.getLocation().toString());
                }
            } catch (Exception ignored) {}

            JsonArray fields = new JsonArray();
            for (Field f : clazz.getDeclaredFields()) {
                if (f.isSynthetic()) continue;
                JsonObject fo = new JsonObject();
                fo.addProperty("name", f.getName());
                fo.addProperty("type", f.getType().getName());
                fo.addProperty("modifiers", Modifier.toString(f.getModifiers()));
                fields.add(fo);
            }
            out.add("declaredFields", fields);

            JsonArray methods = new JsonArray();
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.isSynthetic()) continue;
                JsonObject mo = new JsonObject();
                mo.addProperty("name", m.getName());
                mo.addProperty("returnType", m.getReturnType().getName());
                mo.addProperty("modifiers", Modifier.toString(m.getModifiers()));
                JsonArray params = new JsonArray();
                for (Class<?> pt : m.getParameterTypes()) {
                    params.add(pt.getName());
                }
                mo.add("parameterTypes", params);
                methods.add(mo);
            }
            out.add("declaredMethods", methods);
            return out;
        } catch (ClassNotFoundException e) {
            return JsonUtil.errorObject("CLASS_NOT_FOUND", "Class not loaded: " + className);
        } catch (Throwable t) {
            return JsonUtil.errorObject("INTROSPECT_ERROR", t.getMessage());
        }
    }
}
