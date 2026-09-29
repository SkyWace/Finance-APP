package com.financeapp.core.port;

import com.financeapp.core.budget.Budget;

import java.util.List;
import java.util.Optional;

public interface BudgetRepository {

    List<Budget> findAll();

    Optional<Budget> findById(long id);

    Budget save(Budget budget);

    void delete(long id);
}
