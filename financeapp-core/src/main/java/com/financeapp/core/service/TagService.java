package com.financeapp.core.service;

import com.financeapp.core.port.SearchTotals;
import com.financeapp.core.port.TagRepository;
import com.financeapp.core.port.TransactionQuery;
import com.financeapp.core.port.TransactionRepository;
import com.financeapp.core.tag.Tag;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Currency;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Etiquettes : creation a la volee depuis la saisie, renommage, suppression, totaux. */
public final class TagService {

    public static final int MAX_NAME_LENGTH = 30;

    /** Usage d'une etiquette : nombre d'operations et totaux (hors annulees et virements internes). */
    public record Usage(Tag tag, long count, SearchTotals totals) {
    }

    private final TagRepository tags;
    private final TransactionRepository transactions;

    public TagService(TagRepository tags, TransactionRepository transactions) {
        this.tags = tags;
        this.transactions = transactions;
    }

    public List<Tag> findAll() {
        return tags.findAll();
    }

    /** Nom de chaque etiquette par identifiant. */
    public Map<Long, String> names() {
        return tags.findAll().stream().collect(Collectors.toMap(Tag::id, Tag::name));
    }

    /**
     * Identifiants des etiquettes nommees, creees si besoin (saisie libre
     * "vacances, travaux"). Doublons et espaces superflus ignores.
     */
    public Set<Long> resolve(Collection<String> names) {
        Set<Long> ids = new LinkedHashSet<>();
        for (String raw : names) {
            String name = clean(raw);
            if (name.isEmpty()) {
                continue;
            }
            ids.add(tags.findByName(name).orElseGet(() -> tags.save(new Tag(null, validName(name, null)))).id());
        }
        return ids;
    }

    public Tag create(String name) {
        String clean = validName(name, null);
        return tags.save(new Tag(null, clean));
    }

    public Tag rename(long id, String name) {
        Tag tag = get(id);
        return tags.save(tag.withName(validName(name, id)));
    }

    /** Supprime l'etiquette : les operations sont conservees, elles ne la portent simplement plus. */
    public void delete(long id) {
        get(id);
        tags.delete(id);
    }

    /** Usage de chaque etiquette, les plus utilisees d'abord. */
    public List<Usage> usage(Currency currency) {
        Map<Long, Long> counts = tags.usageCounts();
        List<Usage> result = new ArrayList<>();
        for (Tag tag : tags.findAll()) {
            SearchTotals totals = transactions.summarize(TransactionQuery.all().withTag(tag.id()), currency);
            result.add(new Usage(tag, counts.getOrDefault(tag.id(), 0L), totals));
        }
        result.sort((a, b) -> Long.compare(b.count(), a.count()) != 0 ? Long.compare(b.count(), a.count())
                : a.tag().name().compareToIgnoreCase(b.tag().name()));
        return result;
    }

    private Tag get(long id) {
        return tags.findById(id).orElseThrow(() -> new BusinessException("Étiquette introuvable"));
    }

    private static String clean(String name) {
        return name == null ? "" : name.strip().replaceAll("\\s+", " ");
    }

    private String validName(String name, Long exceptId) {
        String clean = clean(name);
        if (clean.isEmpty()) {
            throw new BusinessException("Le nom de l'étiquette est obligatoire");
        }
        if (clean.length() > MAX_NAME_LENGTH) {
            throw new BusinessException("Nom d'étiquette trop long (" + MAX_NAME_LENGTH + " caractères maximum)");
        }
        if (clean.contains(",")) {
            throw new BusinessException("Une étiquette ne peut pas contenir de virgule");
        }
        tags.findByName(clean).filter(t -> !t.id().equals(exceptId)).ifPresent(t -> {
            throw new BusinessException("L'étiquette « " + t.name() + " » existe déjà");
        });
        return clean;
    }
}
