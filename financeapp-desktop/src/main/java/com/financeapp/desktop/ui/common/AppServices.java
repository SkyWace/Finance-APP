package com.financeapp.desktop.ui.common;

import com.financeapp.core.service.AccountService;
import com.financeapp.core.service.AvailableBalanceService;
import com.financeapp.core.service.BudgetService;
import com.financeapp.core.service.CalendarService;
import com.financeapp.core.service.CategorizationService;
import com.financeapp.core.service.ImportService;
import com.financeapp.core.service.InboxService;
import com.financeapp.core.service.LoanService;
import com.financeapp.core.service.SavingsService;
import com.financeapp.core.service.BankSyncService;
import com.financeapp.core.service.SimulationService;
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
import com.financeapp.desktop.AppProperties;
import com.financeapp.infra.backup.BackupService;
import com.financeapp.infra.storage.AppDirectories;
import org.springframework.context.ApplicationContext;

/** Services mis a disposition de l'interface. L'UI ne touche jamais directement aux depots. */
public record AppServices(
        AppProperties properties,
        AppDirectories directories,
        SettingsService settings,
        AccountService accounts,
        CategoryService categories,
        TransactionService transactions,
        RecurringService recurring,
        PlanningService planning,
        AvailableBalanceService available,
        ForecastService forecast,
        DashboardService dashboard,
        BackupService backups,
        BudgetService budgets,
        SavingsGoalService goals,
        SubscriptionService subscriptions,
        StatisticsService statistics,
        CalendarService calendar,
        CategorizationService categorization,
        InboxService inbox,
        ImportService imports,
        LoanService loans,
        SimulationService simulations,
        BankSyncService bankSync,
        SavingsService savings) {

    public static AppServices from(ApplicationContext ctx) {
        return new AppServices(
                ctx.getBean(AppProperties.class),
                ctx.getBean(AppDirectories.class),
                ctx.getBean(SettingsService.class),
                ctx.getBean(AccountService.class),
                ctx.getBean(CategoryService.class),
                ctx.getBean(TransactionService.class),
                ctx.getBean(RecurringService.class),
                ctx.getBean(PlanningService.class),
                ctx.getBean(AvailableBalanceService.class),
                ctx.getBean(ForecastService.class),
                ctx.getBean(DashboardService.class),
                ctx.getBean(BackupService.class),
                ctx.getBean(BudgetService.class),
                ctx.getBean(SavingsGoalService.class),
                ctx.getBean(SubscriptionService.class),
                ctx.getBean(StatisticsService.class),
                ctx.getBean(CalendarService.class),
                ctx.getBean(CategorizationService.class),
                ctx.getBean(InboxService.class),
                ctx.getBean(ImportService.class),
                ctx.getBean(LoanService.class),
                ctx.getBean(SimulationService.class),
                ctx.getBean(BankSyncService.class),
                ctx.getBean(SavingsService.class));
    }
}
