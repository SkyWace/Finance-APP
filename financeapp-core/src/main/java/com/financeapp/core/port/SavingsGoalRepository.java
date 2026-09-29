package com.financeapp.core.port;

import com.financeapp.core.goal.SavingsGoal;

import java.util.List;
import java.util.Optional;

public interface SavingsGoalRepository {

    List<SavingsGoal> findAll();

    Optional<SavingsGoal> findById(long id);

    SavingsGoal save(SavingsGoal goal);

    void delete(long id);
}
