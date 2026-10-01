package com.financeapp.core.tag;

/** Etiquette libre ("vacances 2026", "travaux"...) qui s'ajoute a la categorie d'une operation. */
public record Tag(Long id, String name) {

    public Tag withId(long newId) {
        return new Tag(newId, name);
    }

    public Tag withName(String newName) {
        return new Tag(id, newName);
    }
}
