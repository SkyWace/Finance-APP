package com.financeapp.desktop;

import com.financeapp.core.port.AccountRepository;
import com.financeapp.core.port.CategoryRepository;
import com.financeapp.core.port.RecurringRuleRepository;
import com.financeapp.core.port.SettingsRepository;
import com.financeapp.core.port.TransactionRepository;
import com.financeapp.core.recurring.RecurrenceEngine;
import com.financeapp.core.service.AccountService;
import com.financeapp.core.service.AvailableBalanceService;
import com.financeapp.core.service.CategoryService;
import com.financeapp.core.service.DashboardService;
import com.financeapp.core.service.ForecastService;
import com.financeapp.core.service.PlanningService;
import com.financeapp.core.service.RecurringService;
import com.financeapp.core.service.TransactionService;
import com.financeapp.core.settings.SettingsService;
import com.financeapp.infra.backup.BackupService;
import com.financeapp.infra.db.DatabaseMigrator;
import com.financeapp.infra.db.JdbcAccountRepository;
import com.financeapp.infra.db.JdbcCategoryRepository;
import com.financeapp.infra.db.JdbcRecurringRuleRepository;
import com.financeapp.infra.db.JdbcSettingsRepository;
import com.financeapp.infra.db.JdbcTransactionRepository;
import com.financeapp.infra.security.DatabaseKey;
import com.financeapp.infra.security.EncryptedDataSource;
import com.financeapp.infra.storage.AppDirectories;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;

/**
 * Assemblage explicite des composants : les classes du core et de l'infra
 * ne portent aucune annotation Spring, tout est cable ici.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AppProperties.class)
public class AppConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemDefaultZone();
    }

    /**
     * Base chiffree : les connexions ne sont accordees que si la cle a ete
     * deverrouillee ({@link DatabaseKey}, fournie au demarrage du contexte).
     */
    @Bean
    DataSource dataSource(AppDirectories directories, DatabaseKey databaseKey) {
        return new EncryptedDataSource(directories.databaseFile(), databaseKey);
    }

    @Bean
    DatabaseMigrator databaseMigrator(DataSource dataSource) {
        DatabaseMigrator migrator = new DatabaseMigrator(dataSource);
        migrator.migrate();
        return migrator;
    }

    @Bean
    JdbcClient jdbcClient(DataSource dataSource, DatabaseMigrator migratedFirst) {
        return JdbcClient.create(dataSource);
    }

    @Bean
    TransactionTemplate transactionTemplate(DataSource dataSource) {
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Bean
    AccountRepository accountRepository(JdbcClient jdbc) {
        return new JdbcAccountRepository(jdbc);
    }

    @Bean
    CategoryRepository categoryRepository(JdbcClient jdbc) {
        return new JdbcCategoryRepository(jdbc);
    }

    @Bean
    TransactionRepository transactionRepository(JdbcClient jdbc, TransactionTemplate tx) {
        return new JdbcTransactionRepository(jdbc, tx);
    }

    @Bean
    RecurringRuleRepository recurringRuleRepository(JdbcClient jdbc) {
        return new JdbcRecurringRuleRepository(jdbc);
    }

    @Bean
    SettingsRepository settingsRepository(JdbcClient jdbc) {
        return new JdbcSettingsRepository(jdbc);
    }

    @Bean
    SettingsService settingsService(SettingsRepository repository) {
        return new SettingsService(repository);
    }

    @Bean
    AccountService accountService(AccountRepository accounts, TransactionRepository transactions,
                                  RecurringRuleRepository rules) {
        return new AccountService(accounts, transactions, rules);
    }

    @Bean
    CategoryService categoryService(CategoryRepository categories) {
        return new CategoryService(categories);
    }

    @Bean
    TransactionService transactionService(AccountRepository accounts, TransactionRepository transactions) {
        return new TransactionService(accounts, transactions);
    }

    @Bean
    RecurringService recurringService(RecurringRuleRepository rules, TransactionRepository transactions,
                                      AccountRepository accounts, TransactionService transactionService, Clock clock) {
        return new RecurringService(rules, transactions, accounts, transactionService, new RecurrenceEngine(), clock);
    }

    @Bean
    PlanningService planningService(TransactionRepository transactions, RecurringService recurring, Clock clock) {
        return new PlanningService(transactions, recurring, clock);
    }

    @Bean
    AvailableBalanceService availableBalanceService(AccountService accounts, PlanningService planning,
                                                    RecurringService recurring, SettingsService settings) {
        return new AvailableBalanceService(accounts, planning, recurring, settings);
    }

    @Bean
    ForecastService forecastService(AvailableBalanceService available, PlanningService planning,
                                    TransactionRepository transactions, SettingsService settings) {
        return new ForecastService(available, planning, transactions, settings);
    }

    @Bean
    DashboardService dashboardService(AccountService accounts, TransactionRepository transactions,
                                      PlanningService planning, AvailableBalanceService available,
                                      SettingsService settings) {
        return new DashboardService(accounts, transactions, planning, available, settings);
    }

    @Bean
    BackupService backupService(DataSource dataSource, AppDirectories directories, DatabaseKey databaseKey,
                                DatabaseMigrator migrator, Clock clock) {
        return new BackupService(dataSource, directories, databaseKey, migrator.latestKnownVersion(), clock);
    }
}
