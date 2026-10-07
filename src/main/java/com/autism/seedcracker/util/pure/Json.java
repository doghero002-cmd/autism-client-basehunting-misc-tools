package com.autism.seedcracker.util.pure;

/** Minimal JSON string escaping (pure, unit tested) for hand-built webhook payloads. */
public final class Json {
    private Json() {}

    /** Quote + escape a string for embedding in a JSON document. */
    public static String quote(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "").replace("\t", "\\t") + "\"";
    }
}
