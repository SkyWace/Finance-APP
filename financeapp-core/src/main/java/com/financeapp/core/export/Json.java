package com.financeapp.core.export;

import java.util.Collection;
import java.util.Map;

/**
 * Ecriture JSON minimale (objets, listes, chaines, nombres entiers, booleens, null),
 * sans dependance. Les chaines sont echappees selon la RFC 8259.
 */
public final class Json {

    /** Valeur ecrite "null" ; une entree d'objet valant null (Java) est, elle, omise. */
    public static final Object NULL = new Object();

    private Json() {
    }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        if (value == NULL) {
            out.append("null");
            return;
        }
        switch (value) {
            case null -> out.append("null");
            case String s -> string(s, out);
            case Boolean b -> out.append(b);
            case Integer i -> out.append(i);
            case Long l -> out.append(l);
            case Map<?, ?> map -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (e.getValue() == null) {
                        continue; // champ absent plutot que null : comme les donnees de la version web
                    }
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    string(String.valueOf(e.getKey()), out);
                    out.append(':');
                    write(e.getValue(), out);
                }
                out.append('}');
            }
            case Collection<?> list -> {
                out.append('[');
                boolean first = true;
                for (Object item : list) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    write(item, out);
                }
                out.append(']');
            }
            default -> throw new IllegalArgumentException("Type non pris en charge : " + value.getClass().getSimpleName());
        }
    }

    private static void string(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20 || c == ' ' || c == ' ') {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
