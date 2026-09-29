package com.financeapp.core.service;

import com.financeapp.core.account.Account;
import com.financeapp.core.account.AccountType;
import com.financeapp.core.money.Money;
import com.financeapp.core.planning.PlannedItem;
import com.financeapp.core.recurring.Frequency;
import com.financeapp.core.recurring.RecurringRule;
import com.financeapp.core.testing.TestApp;
import com.financeapp.core.transaction.Transaction;
import com.financeapp.core.transaction.TransactionStatus;
import com.financeapp.core.transaction.TransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecurringServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 29);
    private final TestApp app = new TestApp(TODAY);
    private final Account checking = app.account("Compte courant", AccountType.CHECKING, "1000");
    private final Account livret = app.account("Livret A", AccountType.PASSBOOK, "0");

    @Test
    void occurrencesAreVirtualUntilConfirmed() {
        RecurringRule gym = app.monthly(checking, TransactionType.EXPENSE, "Salle de sport", "30", LocalDate.of(2026, 1, 15));

        List<PlannedItem> items = app.recurring.pendingOccurrences(TODAY, TODAY.plusMonths(3));
        assertEquals(3, items.size()); // 15/10, 15/11, 15/12
        assertTrue(app.store.allTransactions().isEmpty(), "aucune ligne creee en base");

        List<Transaction> created = app.recurring.confirm(gym.id(), LocalDate.of(2026, 10, 15),
                LocalDate.of(2026, 10, 16), new BigDecimal("32.50"));

        assertEquals(Money.eur("-32.50"), created.getFirst().amount());
        assertEquals(TransactionStatus.COMPLETED, created.getFirst().status());
        assertEquals(2, app.recurring.pendingOccurrences(TODAY, TODAY.plusMonths(3)).size());
        assertThrows(BusinessException.class, () -> app.recurring.confirm(gym.id(), LocalDate.of(2026, 10, 15),
                LocalDate.of(2026, 10, 15), BigDecimal.TEN));
    }

    @Test
    void skippedOccurrenceLeavesBalanceUntouched() {
        RecurringRule streaming = app.monthly(checking, TransactionType.EXPENSE, "Streaming", "22", LocalDate.of(2026, 10, 5));

        app.recurring.skip(streaming.id(), LocalDate.of(2026, 10, 5));

        assertEquals(Money.eur("1000"), app.accounts.balanceOf(checking.id()));
        assertEquals(LocalDate.of(2026, 11, 5), app.recurring.nextOccurrence(streaming).orElseThrow());
    }

    @Test
    void rejectsDatesThatAreNotOccurrences() {
        RecurringRule r = app.monthly(checking, TransactionType.EXPENSE, "Assurance", "101", LocalDate.of(2026, 10, 5));
        assertThrows(BusinessException.class, () -> app.recurring.confirm(r.id(), LocalDate.of(2026, 10, 6),
                LocalDate.of(2026, 10, 6), BigDecimal.TEN));
    }

    @Test
    void recurringTransferProducesOneItemPerAccount() {
        app.recurring.save(new RecurringRule(null, checking.id(), livret.id(), TransactionType.TRANSFER, "Épargne",
                Money.eur("100"), null, Frequency.MONTHLY, 1, LocalDate.of(2026, 10, 1), null, null, true, true, null));

        List<PlannedItem> items = app.recurring.pendingOccurrences(TODAY, LocalDate.of(2026, 10, 31));
        assertEquals(2, items.size());
        assertEquals(1, app.planning.upcomingForDisplay(LocalDate.of(2026, 10, 31)).size());

        app.recurring.confirm(items.getFirst().recurringId(), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1),
                new BigDecimal("100"));
        assertEquals(Money.eur("900"), app.accounts.balanceOf(checking.id()));
        assertEquals(Money.eur("100"), app.accounts.balanceOf(livret.id()));
        assertTrue(app.recurring.pendingOccurrences(TODAY, LocalDate.of(2026, 10, 31)).isEmpty());
    }

    @Test
    void ruleCreatedTodayDoesNotReportPastOccurrencesAsOverdue() {
        // Salaire le 28, regle saisie le 29 : le salaire d'hier ne doit pas etre compte comme a venir.
        app.monthly(checking, TransactionType.INCOME, "Salaire", "1850", LocalDate.of(2025, 1, 28));

        assertTrue(app.planning.upcoming(TODAY).isEmpty());
        assertEquals(LocalDate.of(2026, 10, 28), app.planning.upcoming(TODAY.plusMonths(1)).getFirst().date());
    }

    @Test
    void unconfirmedOccurrencesStayOverdueForALimitedTime() {
        // Regle suivie depuis longtemps (enregistree directement, comme si elle avait ete creee il y a un an)
        app.store.rules.save(new RecurringRule(null, checking.id(), null, TransactionType.EXPENSE, "Assurance",
                Money.eur("101"), null, Frequency.MONTHLY, 1, LocalDate.of(2025, 9, 5), null,
                LocalDate.of(2025, 9, 5), true, true, null));
        app.store.rules.save(new RecurringRule(null, checking.id(), null, TransactionType.EXPENSE, "Internet",
                Money.eur("30"), null, Frequency.MONTHLY, 1, LocalDate.of(2025, 9, 20), null,
                LocalDate.of(2025, 9, 20), true, true, null));

        List<PlannedItem> upcoming = app.planning.upcoming(TODAY);

        // 05/09 est au-dela de la fenetre de retard (14 jours) ; 20/09 est en retard et toujours compte.
        assertEquals(1, upcoming.size());
        assertEquals("Internet", upcoming.getFirst().label());
        assertTrue(upcoming.getFirst().isOverdue(TODAY));
    }

    @Test
    void endingARuleStopsFutureOccurrences() {
        RecurringRule r = app.monthly(checking, TransactionType.EXPENSE, "Téléphone", "13", LocalDate.of(2026, 1, 10));
        app.recurring.end(r.id(), LocalDate.of(2026, 10, 31));
        assertEquals(1, app.recurring.pendingOccurrences(TODAY, TODAY.plusYears(1)).size());
    }
}
