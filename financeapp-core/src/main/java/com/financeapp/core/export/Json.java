package com.financeapp.core.export;

import java.util.Collection;
import java.util.Map;

/**
 * Lecture et ecriture JSON minimales (objets, listes, chaines, nombres entiers, booleens,
 * null), sans dependance. Les chaines sont echappees selon la RFC 8259.
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

    // ------------------------------------------------------------------ lecture

    /** Profondeur d'imbrication maximale acceptee (fichier malveillant). */
    private static final int MAX_DEPTH = 64;

    /**
     * Lit un document JSON : objet → {@code Map<String, Object>} (ordre conserve), tableau →
     * {@code List<Object>}, chaine, entier → {@code Long}, booleen, null → {@code null}.
     * Les nombres a virgule sont refuses (aucun montant n'en utilise).
     *
     * @throws IllegalArgumentException document invalide
     */
    public static Object parse(String text) {
        Reader reader = new Reader(text);
        reader.space();
        Object value = reader.value(0);
        reader.space();
        if (reader.pos != text.length()) {
            throw reader.error("contenu inattendu apres la fin du document");
        }
        return value;
    }

    private static final class Reader {
        private final String s;
        private int pos;

        Reader(String s) {
            this.s = s;
        }

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException("JSON invalide (position " + pos + ") : " + message);
        }

        void space() {
            while (pos < s.length() && " \t\r\n".indexOf(s.charAt(pos)) >= 0) {
                pos++;
            }
        }

        char peek() {
            if (pos >= s.length()) {
                throw error("fin de document inattendue");
            }
            return s.charAt(pos);
        }

        void expect(char c) {
            if (peek() != c) {
                throw error("'" + c + "' attendu");
            }
            pos++;
        }

        void word(String w) {
            if (!s.startsWith(w, pos)) {
                throw error("valeur inconnue");
            }
            pos += w.length();
        }

        Object value(int depth) {
            if (depth > MAX_DEPTH) {
                throw error("imbrication trop profonde");
            }
            char c = peek();
            switch (c) {
                case '{' -> {
                    pos++;
                    Map<String, Object> map = new java.util.LinkedHashMap<>();
                    space();
                    if (peek() == '}') {
                        pos++;
                        return map;
                    }
                    while (true) {
                        space();
                        String key = string();
                        space();
                        expect(':');
                        space();
                        map.put(key, value(depth + 1));
                        space();
                        if (peek() == ',') {
                            pos++;
                            continue;
                        }
                        expect('}');
                        return map;
                    }
                }
                case '[' -> {
                    pos++;
                    java.util.List<Object> list = new java.util.ArrayList<>();
                    space();
                    if (peek() == ']') {
                        pos++;
                        return list;
                    }
                    while (true) {
                        space();
                        list.add(value(depth + 1));
                        space();
                        if (peek() == ',') {
                            pos++;
                            continue;
                        }
                        expect(']');
                        return list;
                    }
                }
                case '"' -> {
                    return string();
                }
                case 't' -> {
                    word("true");
                    return Boolean.TRUE;
                }
                case 'f' -> {
                    word("false");
                    return Boolean.FALSE;
                }
                case 'n' -> {
                    word("null");
                    return null;
                }
                default -> {
                    return number();
                }
            }
        }

        Long number() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                pos++;
            }
            if (pos < s.length() && ".eE".indexOf(s.charAt(pos)) >= 0) {
                throw error("nombre a virgule non pris en charge");
            }
            String digits = s.substring(start, pos);
            if (digits.isEmpty() || digits.equals("-")) {
                throw error("valeur attendue");
            }
            try {
                return Long.parseLong(digits);
            } catch (NumberFormatException e) {
                throw error("nombre trop grand");
            }
        }

        String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                char c = peek();
                pos++;
                if (c == '"') {
                    return out.toString();
                }
                if (c < 0x20) {
                    throw error("caractere de controle dans une chaine");
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                char e = peek();
                pos++;
                switch (e) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (pos + 4 > s.length()) {
                            throw error("sequence \\u incomplete");
                        }
                        try {
                            out.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw error("sequence \\u invalide");
                        }
                        pos += 4;
                    }
                    default -> throw error("echappement inconnu");
                }
            }
        }
    }
}
