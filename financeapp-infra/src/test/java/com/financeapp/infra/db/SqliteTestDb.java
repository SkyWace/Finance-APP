package com.financeapp.infra.db;

import com.financeapp.core.recurring.RecurrenceEngine;
import com.financeapp.core.service.AccountService;
import com.financeapp.core.service.AvailableBalanceService;
import com.financeapp.core.service.CategoryService;
import com.financeapp.core.service.PlanningService;
import com.financeapp.core.service.RecurringService;
import com.financeapp.core.service.TransactionService;
import com.financeapp.core.settings.SettingsService;
import com.financeapp.infra.storage.AppDirectories;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

/** Base SQLite reelle dans un dossier temporaire, migree, avec les services du core branches dessus. */
public final class SqliteTestDb {

    public final AppDirectories dirs;
    public final SQLiteDataSource dataSource;
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

    public SqliteTestDb(Path root, LocalDate today) {
        dirs = new AppDirectories(root).createAll();
        dataSource = SqliteDataSourceFactory.create(dirs.databaseFile());
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
        accounts = new AccountService(accountRepo, transactionRepo, ruleRepo);
        categories = new CategoryService(categoryRepo);
        transactions = new TransactionService(accountRepo, transactionRepo);
        recurring = new RecurringService(ruleRepo, transactionRepo, accountRepo, transactions, new RecurrenceEngine(), clock);
        planning = new PlanningService(transactionRepo, recurring, clock);
        available = new AvailableBalanceService(accounts, planning, recurring, settings);
    }
}
