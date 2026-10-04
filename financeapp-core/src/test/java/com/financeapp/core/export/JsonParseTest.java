package com.financeapp.core.export;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonParseTest {

    @Test
    void readsWhatItWrites() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("texte", "Courses \"Bio\"\nligne 2 · é €  ");
        value.put("montant", -12345L);
        value.put("ok", true);
        value.put("vide", Json.NULL);
        value.put("liste", List.of(1L, "deux", false, Map.of()));
        Object parsed = Json.parse(Json.write(value));
        Map<String, Object> expected = new LinkedHashMap<>(value);
        expected.put("vide", null);
        assertEquals(expected, parsed);
        assertEquals(List.of("a", "é/\t"), Json.parse(" [ \"a\" , \"\\u00e9\\/\\t\" ] "));
    }

    @Test
    void rejectsInvalidDocuments() {
        for (String bad : List.of("", "{", "[1,]", "{\"a\" 1}", "1.5", "tru", "\"x", "{} {}", "\"\u0001\"",
                "99999999999999999999")) {
            assertThrows(IllegalArgumentException.class, () -> Json.parse(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[".repeat(100) + "]".repeat(100)),
                "imbrication trop profonde");
    }
}
