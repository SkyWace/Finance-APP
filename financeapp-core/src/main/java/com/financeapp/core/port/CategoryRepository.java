package com.financeapp.core.port;

import com.financeapp.core.category.Category;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository {

    List<Category> findAll();

    Optional<Category> findById(long id);

    Category save(Category category);

    void delete(long id);

    /** Nombre de transactions, regles recurrentes et sous-categories rattachees. */
    long countUsages(long id);
}
