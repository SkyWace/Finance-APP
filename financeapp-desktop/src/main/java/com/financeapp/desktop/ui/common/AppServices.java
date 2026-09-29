package com.financeapp.desktop.ui.common;

import com.financeapp.core.service.AccountService;
import com.financeapp.core.service.AvailableBalanceService;
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
        BackupService backups) {

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
                ctx.getBean(BackupService.class));
    }
}
