package com.financeapp.core.port;

import com.financeapp.core.recurring.RecurringRule;

import java.util.List;
import java.util.Optional;

public interface RecurringRuleRepository {

    List<RecurringRule> findAll();

    Optional<RecurringRule> findById(long id);

    RecurringRule save(RecurringRule rule);

    /** Les occurrences deja validees sont conservees (lien remis a {@code null}). */
    void delete(long id);
}
