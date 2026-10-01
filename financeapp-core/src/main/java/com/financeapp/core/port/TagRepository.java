package com.financeapp.core.port;

import com.financeapp.core.tag.Tag;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface TagRepository {

    /** Toutes les etiquettes, par nom. */
    List<Tag> findAll();

    Optional<Tag> findById(long id);

    /** Recherche insensible a la casse. */
    Optional<Tag> findByName(String name);

    Tag save(Tag tag);

    /** Supprime l'etiquette et la retire de toutes les operations. */
    void delete(long id);

    /** Nombre d'operations portant chaque etiquette. */
    Map<Long, Long> usageCounts();
}
