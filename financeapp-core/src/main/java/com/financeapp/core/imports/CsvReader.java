package com.financeapp.core.imports;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Lecteur CSV tolerant aux exports bancaires :
 * <ul>
 *   <li>encodage : UTF-8 (avec ou sans BOM), sinon Windows-1252 (exports Excel francais) ;</li>
 *   <li>separateur detecte parmi {@code ;} {@code ,} tabulation {@code |} (le plus regulier) ;</li>
 *   <li>champs entre guillemets, guillemets doubles echappes, retours a la ligne dans un champ ;</li>
 *   <li>lignes vides ignorees.</li>
 * </ul>
 */
public final class CsvReader {

    private static final char[] CANDIDATES = {';', ',', '\t', '|'};

    public CsvTable read(byte[] content) {
        String charset = "UTF-8";
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException e) {
            charset = "windows-1252";
            text = new String(content, Charset.forName(charset));
        }
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        char delimiter = detectDelimiter(text);
        return new CsvTable(parse(text, delimiter), delimiter, charset);
    }

    static char detectDelimiter(String text) {
        List<String> lines = text.lines().filter(l -> !l.isBlank()).limit(20).toList();
        char best = ';';
        double bestScore = -1;
        for (char c : CANDIDATES) {
            List<Integer> counts = lines.stream().map(l -> countOutsideQuotes(l, c)).toList();
            if (counts.isEmpty() || counts.stream().allMatch(n -> n == 0)) {
                continue;
            }
            // Score : nombre de colonnes, penalise par l'irregularite entre lignes.
            int mode = counts.stream().max(Integer::compare).orElse(0);
            long regular = counts.stream().filter(n -> n == mode).count();
            double score = mode * ((double) regular / counts.size());
            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }
        return best;
    }

    private static int countOutsideQuotes(String line, char c) {
        int n = 0;
        boolean quoted = false;
        for (char ch : line.toCharArray()) {
            if (ch == '"') {
                quoted = !quoted;
            } else if (ch == c && !quoted) {
                n++;
            }
        }
        return n;
    }

    static List<List<String>> parse(String text, char delimiter) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(ch);
                }
            } else if (ch == '"' && field.isEmpty()) {
                quoted = true;
            } else if (ch == delimiter) {
                row.add(field.toString().strip());
                field.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(field.toString().strip());
                field.setLength(0);
                addIfNotEmpty(rows, row);
                row = new ArrayList<>();
            } else {
                field.append(ch);
            }
        }
        row.add(field.toString().strip());
        addIfNotEmpty(rows, row);
        return rows;
    }

    private static void addIfNotEmpty(List<List<String>> rows, List<String> row) {
        if (row.stream().anyMatch(f -> !f.isBlank())) {
            rows.add(row);
        }
    }
}
