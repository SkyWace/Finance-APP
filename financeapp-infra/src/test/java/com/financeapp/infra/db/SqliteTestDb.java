package com.financeapp.infra.db;

import com.financeapp.core.recurring.RecurrenceEngine;
import com.financeapp.core.service.AccountService;
import com.financeapp.core.service.AvailableBalanceService;
import com.financeapp.core.service.CategoryService;
import com.financeapp.core.service.PlanningService;
import com.financeapp.core.service.RecurringService;
import com.financeapp.core.service.TransactionService;
import com.financeapp.core.settings.SettingsService;
import com.financeapp.infra.security.DatabaseKey;
import com.financeapp.infra.security.EncryptedDataSource;
import com.financeapp.infra.storage.AppDirectories;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

/** Base SQLite chiffree reelle dans un dossier temporaire, migree, avec les services du core branches dessus. */
public final class SqliteTestDb {

    public final AppDirectories dirs;
    public final DatabaseKey key;
    public final EncryptedDataSource dataSource;
    public final DatabaseMigrator migrator;
    public final JdbcClient jdbc;
    public final JdbcAccountRepository accountRepo;
    public final JdbcCategoryRepository categoryRepo;
    public final JdbcTransactionRepository transactionRepo;
    public final JdbcRecurringRuleRepository ruleRepo;
    public final JdbcSettingsRepository settingsRepo;
    public final Clock clock;
    public final SettingsService settings;
    public final AccountService accounts;
    public final CategoryService categories;
    public final TransactionService transactions;
    public final RecurringService recurring;
    public final PlanningService planning;
    public final AvailableBalanceService available;
    public final JdbcBudgetRepository budgetRepo;
    public final JdbcSavingsGoalRepository goalRepo;
    public final com.financeapp.core.service.BudgetService budgets;
    public final com.financeapp.core.service.SavingsGoalService goals;
    public final JdbcImportRepository importRepo;
    public final JdbcCategorizationRuleRepository categorizationRuleRepo;
    public final com.financeapp.core.service.CategorizationService categorization;
    public final com.financeapp.core.service.InboxService inbox;
    public final com.financeapp.core.service.ImportService imports;
    public final JdbcLoanRepository loanRepo;
    public final JdbcValuationRepository valuationRepo;
    public final JdbcBankSyncRepository bankSyncRepo;
    public final JdbcSimulationRepository simulationRepo;
    public final com.financeapp.core.service.LoanService loans;
    public final com.financeapp.core.service.ForecastService forecast;
    public final com.financeapp.core.service.SimulationService simulations;

    public SqliteTestDb(Path root, LocalDate today) {
        this(root, today, randomKey());
    }

    /** Base chiffree (comme en production) avec la cle donnee. */
    public SqliteTestDb(Path root, LocalDate today, byte[] rawKey) {
        dirs = new AppDirectories(root).createAll();
        key = new DatabaseKey();
        key.unlock(rawKey);
        dataSource = new EncryptedDataSource(dirs.databaseFile(), key);
        migrator = new DatabaseMigrator(dataSource);
        migrator.migrate();
        jdbc = JdbcClient.create(dataSource);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        accountRepo = new JdbcAccountRepository(jdbc);
        categoryRepo = new JdbcCategoryRepository(jdbc);
        transactionRepo = new JdbcTransactionRepository(jdbc, tx);
        ruleRepo = new JdbcRecurringRuleRepository(jdbc);
        settingsRepo = new JdbcSettingsRepository(jdbc);
        clock = Clock.fixed(today.atTime(10, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        settings = new SettingsService(settingsRepo);
        valuationRepo = new JdbcValuationRepository(jdbc);
        accounts = new AccountService(accountRepo, transactionRepo, ruleRepo, valuationRepo, clock);
        categories = new CategoryService(categoryRepo);
        transactions = new TransactionService(accountRepo, transactionRepo);
        recurring = new RecurringService(ruleRepo, transactionRepo, accountRepo, transactions, new RecurrenceEngine(), clock);
        planning = new PlanningService(transactionRepo, recurring, clock);
        budgetRepo = new JdbcBudgetRepository(jdbc);
        goalRepo = new JdbcSavingsGoalRepository(jdbc);
        budgets = new com.financeapp.core.service.BudgetService(budgetRepo, transactionRepo, planning, categories, settings);
        goals = new com.financeapp.core.service.SavingsGoalService(goalRepo, accounts, planning);
        available = new AvailableBalanceService(accounts, planning, recurring, settings, java.util.List.of(budgets, goals));
        importRepo = new JdbcImportRepository(jdbc, transactionRepo, tx);
        categorizationRuleRepo = new JdbcCategorizationRuleRepository(jdbc);
        categorization = new com.financeapp.core.service.CategorizationService(categorizationRuleRepo, transactionRepo, categories, planning);
        inbox = new com.financeapp.core.service.InboxService(transactionRepo, categorization);
        imports = new com.financeapp.core.service.ImportService(importRepo, transactionRepo, accounts, planning, categorization);
        loanRepo = new JdbcLoanRepository(jdbc);
        bankSyncRepo = new JdbcBankSyncRepository(jdbc, tx);
        simulationRepo = new JdbcSimulationRepository(jdbc, tx);
        loans = new com.financeapp.core.service.LoanService(loanRepo, recurring, accounts, clock);
        forecast = new com.financeapp.core.service.ForecastService(available, planning, transactionRepo, settings, recurring);
        simulations = new com.financeapp.core.service.SimulationService(simulationRepo, forecast, goals, planning);
    }

    public static byte[] randomKey() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return k;
    }
}
