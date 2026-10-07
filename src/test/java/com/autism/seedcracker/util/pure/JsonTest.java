package com.autism.seedcracker.util.pure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JsonTest {

    @Test
    void plainString() {
        assertEquals("\"hello\"", Json.quote("hello"));
    }

    @Test
    void escapesSpecials() {
        assertEquals("\"a\\\"b\"", Json.quote("a\"b"));
        assertEquals("\"a\\\\b\"", Json.quote("a\\b"));
        assertEquals("\"line1\\nline2\"", Json.quote("line1\nline2"));
        assertEquals("\"tab\\there\"", Json.quote("tab\there"));
        assertEquals("\"cr\"", Json.quote("c\rr"));
    }

    @Test
    void nullBecomesEmpty() {
        assertEquals("\"\"", Json.quote(null));
    }

    @Test
    void escapeOrderDoesNotDoubleEscape() {
        // A backslash before a quote must produce \\ then \" - not \\\" mangling.
        assertEquals("\"\\\\\\\"\"", Json.quote("\\\""));
    }
}
