package com.financeapp.core.service;

import com.financeapp.core.category.Category;
import com.financeapp.core.category.CategoryKind;
import com.financeapp.core.port.CategoryRepository;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/** Arbre de categories a deux niveaux (categorie > sous-categorie). */
public final class CategoryService {

    private final CategoryRepository categories;

    public CategoryService(CategoryRepository categories) {
        this.categories = categories;
    }

    public List<Category> findAll() {
        return categories.findAll();
    }

    public Optional<Category> find(Long id) {
        return id == null ? Optional.empty() : categories.findById(id);
    }

    /** Categories racines non archivees et leurs sous-categories non archivees, triees. */
    public Map<Category, List<Category>> activeTree() {
        List<Category> all = categories.findAll().stream().filter(c -> !c.archived()).toList();
        Comparator<Category> order = Comparator.comparingInt(Category::sortOrder).thenComparing(Category::name);
        Map<Long, List<Category>> children = all.stream().filter(c -> !c.isRoot())
                .collect(Collectors.groupingBy(Category::parentId));
        Map<Category, List<Category>> tree = new LinkedHashMap<>();
        all.stream().filter(Category::isRoot).sorted(order).forEach(root ->
                tree.put(root, children.getOrDefault(root.id(), List.of()).stream().sorted(order).toList()));
        return tree;
    }

    /** Libelle complet "Categorie > Sous-categorie". */
    public String fullName(Long categoryId) {
        if (categoryId == null) {
            return "";
        }
        return categories.findById(categoryId).map(c -> c.isRoot() ? c.name()
                : categories.findById(c.parentId()).map(p -> p.name() + " › " + c.name()).orElse(c.name()))
                .orElse("");
    }

    /** Libelles complets de toutes les categories (archivees comprises), en une seule lecture. */
    public Map<Long, String> fullNames() {
        List<Category> all = categories.findAll();
        Map<Long, String> roots = all.stream().filter(Category::isRoot)
                .collect(Collectors.toMap(Category::id, Category::name));
        Map<Long, String> names = new java.util.HashMap<>();
        for (Category c : all) {
            names.put(c.id(), c.isRoot() ? c.name() : roots.getOrDefault(c.parentId(), "?") + " › " + c.name());
        }
        return names;
    }

    /** La categorie et ses sous-categories (un budget ou un filtre sur "Alimentation" couvre "Courses"). */
    public java.util.Set<Long> selfAndChildren(long id) {
        java.util.Set<Long> ids = new java.util.HashSet<>();
        ids.add(id);
        categories.findAll().stream().filter(c -> Objects.equals(c.parentId(), id)).forEach(c -> ids.add(c.id()));
        return ids;
    }

    /** Categorie racine de chaque categorie (une racine est sa propre racine). */
    public Map<Long, Long> rootIndex() {
        Map<Long, Long> roots = new java.util.HashMap<>();
        for (Category c : categories.findAll()) {
            roots.put(c.id(), c.isRoot() ? c.id() : c.parentId());
        }
        return roots;
    }

    public Optional<Category> findBySystemCode(String code) {
        return categories.findAll().stream().filter(c -> code.equals(c.systemCode())).findFirst();
    }

    public Category create(Long parentId, String name, CategoryKind kind) {
        CategoryKind effectiveKind = kind;
        if (parentId != null) {
            Category parent = categories.findById(parentId)
                    .orElseThrow(() -> new BusinessException("Catégorie parente introuvable"));
            if (!parent.isRoot()) {
                throw new BusinessException("Une sous-catégorie ne peut pas avoir elle-même de sous-catégorie");
            }
            effectiveKind = kind == null ? parent.kind() : kind;
        }
        requireUniqueName(parentId, name, null);
        return categories.save(Category.create(parentId, name, Objects.requireNonNull(effectiveKind, "kind")));
    }

    public Category rename(long id, String newName) {
        Category c = get(id);
        requireUniqueName(c.parentId(), newName, id);
        return categories.save(c.withName(newName));
    }

    public Category setArchived(long id, boolean archived) {
        return categories.save(get(id).withArchived(archived));
    }

    /** Suppression definitive uniquement si la categorie n'est utilisee nulle part ; sinon archiver. */
    public void delete(long id) {
        get(id);
        if (categories.countUsages(id) > 0) {
            throw new BusinessException("Cette catégorie est utilisée : archivez-la plutôt que de la supprimer.");
        }
        categories.delete(id);
    }

    private Category get(long id) {
        return categories.findById(id).orElseThrow(() -> new BusinessException("Catégorie introuvable"));
    }

    private void requireUniqueName(Long parentId, String name, Long excludedId) {
        if (name == null || name.isBlank()) {
            throw new BusinessException("Le nom de la catégorie est obligatoire");
        }
        boolean duplicate = categories.findAll().stream()
                .filter(c -> Objects.equals(c.parentId(), parentId))
                .filter(c -> !Objects.equals(c.id(), excludedId))
                .anyMatch(c -> c.name().equalsIgnoreCase(name.strip()));
        if (duplicate) {
            throw new BusinessException("Une catégorie porte déjà ce nom à cet endroit");
        }
    }
}
