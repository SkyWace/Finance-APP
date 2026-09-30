package com.financeapp.core.port;

import com.financeapp.core.categorization.CategorizationRule;

import java.util.List;
import java.util.Optional;

public interface CategorizationRuleRepository {

    List<CategorizationRule> findAll();

    Optional<CategorizationRule> findById(long id);

    CategorizationRule save(CategorizationRule rule);

    void delete(long id);
}
