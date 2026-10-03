package com.financeapp.desktop;

import com.financeapp.core.port.AccountRepository;
import com.financeapp.core.port.BudgetRepository;
import com.financeapp.core.port.CategorizationRuleRepository;
import com.financeapp.core.port.ImportRepository;
import com.financeapp.core.port.SavingsGoalRepository;
import com.financeapp.core.port.CategoryRepository;
import com.financeapp.core.port.RecurringRuleRepository;
import com.financeapp.core.port.SettingsRepository;
import com.financeapp.core.port.TransactionRepository;
import com.financeapp.core.recurring.RecurrenceEngine;
import com.financeapp.core.service.AccountService;
import com.financeapp.core.service.AvailableBalanceService;
import com.financeapp.core.service.BudgetService;
import com.financeapp.core.service.CalendarService;
import com.financeapp.core.service.CategorizationService;
import com.financeapp.core.service.ImportService;
import com.financeapp.core.service.InboxService;
import com.financeapp.core.service.LoanService;
import com.financeapp.core.service.SavingsService;
import com.financeapp.core.port.ValuationRepository;
import com.financeapp.infra.db.JdbcValuationRepository;
import com.financeapp.core.service.BankSyncService;
import com.financeapp.core.port.BankSyncClientFactory;
import com.financeapp.core.port.BankSyncRepository;
import com.financeapp.banksync.EnableBankingClientFactory;
import com.financeapp.infra.db.JdbcBankSyncRepository;
import com.financeapp.core.service.SimulationService;
import com.financeapp.core.port.LoanRepository;
import com.financeapp.core.port.SimulationRepository;
import com.financeapp.infra.db.JdbcLoanRepository;
import com.financeapp.infra.db.JdbcSimulationRepository;
import com.financeapp.core.service.SavingsGoalService;
import com.financeapp.core.service.StatisticsService;
import com.financeapp.core.service.SubscriptionService;
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
import com.financeapp.infra.db.JdbcBudgetRepository;
import com.financeapp.infra.db.JdbcCategorizationRuleRepository;
import com.financeapp.infra.db.JdbcImportRepository;
import com.financeapp.infra.db.JdbcSavingsGoalRepository;
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
import java.util.List;

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
    RecurringRuleRepository recurringRuleRepository(JdbcClient jdbc, TransactionTemplate tx) {
        return new JdbcRecurringRuleRepository(jdbc, tx);
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
                                  RecurringRuleRepository rules, ValuationRepository valuations, Clock clock) {
        return new AccountService(accounts, transactions, rules, valuations, clock);
    }

    /** Derniere version publiee ; sans depot configure, aucune connexion n'est jamais faite. */
    @Bean
    com.financeapp.core.port.ReleaseFeed releaseFeed(
            @org.springframework.beans.factory.annotation.Value("${app.update-repository:}") String repository) {
        if (repository == null || repository.isBlank()) {
            return java.util.Optional::empty;
        }
        return new com.financeapp.banksync.update.GitHubReleaseFeed(repository.strip());
    }

    @Bean
    com.financeapp.core.service.UpdateService updateService(com.financeapp.core.port.ReleaseFeed feed,
                                                            SettingsService settings, AppProperties properties, Clock clock) {
        return new com.financeapp.core.service.UpdateService(feed, settings, properties.version(), clock);
    }

    @Bean
    com.financeapp.core.port.TagRepository tagRepository(JdbcClient jdbc) {
        return new com.financeapp.infra.db.JdbcTagRepository(jdbc);
    }

    @Bean
    com.financeapp.core.service.TagService tagService(com.financeapp.core.port.TagRepository tags,
                                                      TransactionRepository transactions) {
        return new com.financeapp.core.service.TagService(tags, transactions);
    }

    @Bean
    ValuationRepository valuationRepository(JdbcClient jdbc) {
        return new JdbcValuationRepository(jdbc);
    }

    @Bean
    SavingsService savingsService(AccountService accounts, SettingsService settings) {
        return new SavingsService(accounts, settings);
    }

    @Bean
    com.financeapp.core.service.NetWorthService netWorthService(AccountService accounts, TransactionRepository transactions,
                                                                SettingsService settings, Clock clock) {
        return new com.financeapp.core.service.NetWorthService(accounts, transactions, settings, clock);
    }

    @Bean
    com.financeapp.core.service.AttachmentService attachmentService(JdbcClient jdbc, TransactionRepository transactions,
                                                                    Clock clock) {
        return new com.financeapp.core.service.AttachmentService(
                new com.financeapp.infra.db.JdbcAttachmentRepository(jdbc), transactions, clock);
    }

    @Bean
    com.financeapp.core.service.WebExportService webExportService(AccountService accounts, CategoryService categories,
            TransactionRepository transactions, RecurringService recurring, com.financeapp.core.service.TagService tags,
            SettingsService settings) {
        return new com.financeapp.core.service.WebExportService(accounts, categories, transactions, recurring, tags,
                settings);
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
    BudgetRepository budgetRepository(JdbcClient jdbc) {
        return new JdbcBudgetRepository(jdbc);
    }

    @Bean
    SavingsGoalRepository savingsGoalRepository(JdbcClient jdbc) {
        return new JdbcSavingsGoalRepository(jdbc);
    }

    @Bean
    BudgetService budgetService(BudgetRepository budgets, TransactionRepository transactions, PlanningService planning,
                                CategoryService categories, SettingsService settings) {
        return new BudgetService(budgets, transactions, planning, categories, settings);
    }

    @Bean
    SavingsGoalService savingsGoalService(SavingsGoalRepository goals, AccountService accounts, PlanningService planning) {
        return new SavingsGoalService(goals, accounts, planning);
    }

    /** Le reste des budgets et l'effort des objectifs marques "a reserver" sont deduits du disponible. */
    @Bean
    AvailableBalanceService availableBalanceService(AccountService accounts, PlanningService planning,
                                                    RecurringService recurring, SettingsService settings,
                                                    BudgetService budgets, SavingsGoalService goals) {
        return new AvailableBalanceService(accounts, planning, recurring, settings, List.of(budgets, goals));
    }

    @Bean
    SubscriptionService subscriptionService(RecurringService recurring, CategoryService categories,
                                            TransactionRepository transactions, PlanningService planning,
                                            SettingsService settings) {
        return new SubscriptionService(recurring, categories, transactions, planning, settings);
    }

    @Bean
    StatisticsService statisticsService(TransactionRepository transactions, CategoryService categories,
                                        AccountService accounts, PlanningService planning, SettingsService settings) {
        return new StatisticsService(transactions, categories, accounts, planning, settings);
    }

    @Bean
    CategorizationRuleRepository categorizationRuleRepository(JdbcClient jdbc) {
        return new JdbcCategorizationRuleRepository(jdbc);
    }

    @Bean
    ImportRepository importRepository(JdbcClient jdbc, TransactionRepository transactions, TransactionTemplate tx) {
        return new JdbcImportRepository(jdbc, transactions, tx);
    }

    @Bean
    CategorizationService categorizationService(CategorizationRuleRepository rules, TransactionRepository transactions,
                                                CategoryService categories, PlanningService planning) {
        return new CategorizationService(rules, transactions, categories, planning);
    }

    @Bean
    InboxService inboxService(TransactionRepository transactions, CategorizationService categorization) {
        return new InboxService(transactions, categorization);
    }

    @Bean
    ImportService importService(ImportRepository imports, TransactionRepository transactions, AccountService accounts,
                                PlanningService planning, CategorizationService categorization) {
        return new ImportService(imports, transactions, accounts, planning, categorization);
    }

    @Bean
    CalendarService calendarService(TransactionRepository transactions, PlanningService planning, ForecastService forecast,
                                    AccountService accounts) {
        return new CalendarService(transactions, planning, forecast, accounts);
    }

    @Bean
    ForecastService forecastService(AvailableBalanceService available, PlanningService planning,
                                    TransactionRepository transactions, SettingsService settings,
                                    RecurringService recurring) {
        return new ForecastService(available, planning, transactions, settings, recurring);
    }

    @Bean
    LoanRepository loanRepository(JdbcClient jdbc) {
        return new JdbcLoanRepository(jdbc);
    }

    @Bean
    LoanService loanService(LoanRepository loans, RecurringService recurring, AccountService accounts, Clock clock) {
        return new LoanService(loans, recurring, accounts, clock);
    }

    @Bean
    SimulationRepository simulationRepository(JdbcClient jdbc, TransactionTemplate tx) {
        return new JdbcSimulationRepository(jdbc, tx);
    }

    @Bean
    SimulationService simulationService(SimulationRepository simulations, ForecastService forecast,
                                        SavingsGoalService goals, PlanningService planning) {
        return new SimulationService(simulations, forecast, goals, planning);
    }

    @Bean
    BankSyncRepository bankSyncRepository(JdbcClient jdbc, TransactionTemplate tx) {
        return new JdbcBankSyncRepository(jdbc, tx);
    }

    /**
     * Agregateur Enable Banking (production). La propriete systeme
     * {@code financeapp.banksync.api} permet de viser un serveur simule, sur la
     * boucle locale uniquement (le client refuse toute autre adresse non HTTPS).
     */
    @Bean
    BankSyncClientFactory bankSyncClientFactory(Clock clock) {
        String override = System.getProperty("financeapp.banksync.api");
        return override == null || override.isBlank()
                ? new EnableBankingClientFactory(clock)
                : new EnableBankingClientFactory(java.net.URI.create(override), clock);
    }

    @Bean
    BankSyncService bankSyncService(BankSyncRepository repository, BankSyncClientFactory factory, ImportService imports,
                                    AccountService accounts, Clock clock) {
        return new BankSyncService(repository, factory, imports, accounts, clock);
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
