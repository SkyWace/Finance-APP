package com.financeapp.core.category;

import java.util.Objects;

/**
 * Categorie ou sous-categorie (arbre a deux niveaux).
 *
 * @param parentId   {@code null} pour une categorie racine
 * @param systemCode code stable des categories fournies par defaut ({@code null} pour celles de l'utilisateur)
 */
public record Category(
        Long id,
        Long parentId,
        String name,
        CategoryKind kind,
        String icon,
        String color,
        boolean archived,
        int sortOrder,
        String systemCode) {

    public Category {
        Objects.requireNonNull(kind, "kind");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Le nom de la catégorie est obligatoire");
        }
        name = name.strip();
    }

    public static Category create(Long parentId, String name, CategoryKind kind) {
        return new Category(null, parentId, name, kind, null, null, false, 0, null);
    }

    public boolean isRoot() {
        return parentId == null;
    }

    public boolean acceptsExpenses() {
        return kind != CategoryKind.INCOME;
    }

    public boolean acceptsIncome() {
        return kind != CategoryKind.EXPENSE;
    }

    public Category withId(long newId) {
        return new Category(newId, parentId, name, kind, icon, color, archived, sortOrder, systemCode);
    }

    public Category withName(String newName) {
        return new Category(id, parentId, newName, kind, icon, color, archived, sortOrder, systemCode);
    }

    public Category withArchived(boolean value) {
        return new Category(id, parentId, name, kind, icon, color, value, sortOrder, systemCode);
    }
}
