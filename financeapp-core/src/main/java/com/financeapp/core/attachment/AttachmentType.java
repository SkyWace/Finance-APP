package com.financeapp.core.attachment;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

/**
 * Formats acceptes, reconnus a leur contenu (signature en debut de fichier) et non a
 * leur extension : un fichier renomme n'est pas accepte pour ce qu'il n'est pas.
 */
public enum AttachmentType {
    PDF("application/pdf", "pdf", "PDF", false),
    PNG("image/png", "png", "Image PNG", true),
    JPEG("image/jpeg", "jpg", "Image JPEG", true),
    GIF("image/gif", "gif", "Image GIF", true),
    WEBP("image/webp", "webp", "Image WebP", false),
    HEIC("image/heic", "heic", "Photo HEIC", false);

    private final String mediaType;
    private final String extension;
    private final String label;
    private final boolean previewable;

    AttachmentType(String mediaType, String extension, String label, boolean previewable) {
        this.mediaType = mediaType;
        this.extension = extension;
        this.label = label;
        this.previewable = previewable;
    }

    public String mediaType() {
        return mediaType;
    }

    /** Extension usuelle, sans point. */
    public String extension() {
        return extension;
    }

    public String label() {
        return label;
    }

    /** Affichable directement dans l'application (sans fichier temporaire). */
    public boolean previewable() {
        return previewable;
    }

    /** Format d'apres les premiers octets, vide s'il n'est pas accepte. */
    public static Optional<AttachmentType> detect(byte[] content) {
        if (content == null || content.length < 12) {
            return Optional.empty();
        }
        if (startsWith(content, 0, "%PDF-".getBytes(StandardCharsets.US_ASCII))) {
            return Optional.of(PDF);
        }
        if (startsWith(content, 0, new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A})) {
            return Optional.of(PNG);
        }
        if (startsWith(content, 0, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})) {
            return Optional.of(JPEG);
        }
        if (startsWith(content, 0, "GIF87a".getBytes(StandardCharsets.US_ASCII))
                || startsWith(content, 0, "GIF89a".getBytes(StandardCharsets.US_ASCII))) {
            return Optional.of(GIF);
        }
        if (startsWith(content, 0, "RIFF".getBytes(StandardCharsets.US_ASCII))
                && startsWith(content, 8, "WEBP".getBytes(StandardCharsets.US_ASCII))) {
            return Optional.of(WEBP);
        }
        if (startsWith(content, 4, "ftyp".getBytes(StandardCharsets.US_ASCII))) {
            String brand = new String(Arrays.copyOfRange(content, 8, 12), StandardCharsets.US_ASCII);
            if (brand.equals("heic") || brand.equals("heix") || brand.equals("mif1") || brand.equals("heim")
                    || brand.equals("heis")) {
                return Optional.of(HEIC);
            }
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] content, int offset, byte[] prefix) {
        if (content.length < offset + prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (content[offset + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
