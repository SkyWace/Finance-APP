package com.financeapp.core.imports;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Decodage des fichiers bancaires : UTF-8 strict, sinon Windows-1252 ; BOM retire. */
public final class TextDecoding {

    private TextDecoding() {
    }

    public static String decode(byte[] content) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException e) {
            text = new String(content, Charset.forName("windows-1252"));
        }
        return text.startsWith("﻿") ? text.substring(1) : text;
    }
}
