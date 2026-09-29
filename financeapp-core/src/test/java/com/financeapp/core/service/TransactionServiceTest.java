package com.financeapp.core.service;

import com.financeapp.core.account.Account;
import com.financeapp.core.account.AccountType;
import com.financeapp.core.money.Money;
import com.financeapp.core.testing.TestApp;
import com.financeapp.core.transaction.Transaction;
import com.financeapp.core.transaction.TransactionStatus;
import com.financeapp.core.transaction.TransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TransactionServiceTest {

    private final TestApp app = new TestApp(LocalDate.of(2026, 9, 29));
    private final Account checking = app.account("Compte courant", AccountType.CHECKING, "1000");
    private final Account livret = app.account("Livret A", AccountType.PASSBOOK, "4200");

    @Test
    void balanceCountsCompletedAndPendingOnly() {
        app.expense(checking, app.today, "Carrefour", "74.31", TransactionStatus.COMPLETED);
        app.expense(checking, app.today, "Station Total", "68.42", TransactionStatus.PENDING);
        app.expense(checking, app.today.plusDays(5), "Loyer", "650", TransactionStatus.PLANNED);
        app.expense(checking, app.today, "Annulé", "999", TransactionStatus.CANCELLED);
        app.income(checking, app.today, "Salaire", "1850", TransactionStatus.COMPLETED);

        assertEquals(Money.eur("2707.27"), app.accounts.balanceOf(checking.id()));
    }

    @Test
    void expenseIsStoredNegativeFromAPositiveInput() {
        Transaction t = app.expense(checking, app.today, "Carrefour", "74.31", TransactionStatus.COMPLETED);
        assertEquals(Money.eur("-74.31"), t.amount());
        assertThrows(BusinessException.class, () -> app.expense(checking, app.today, "x", "-5", TransactionStatus.COMPLETED));
        assertThrows(BusinessException.class, () -> app.expense(checking, app.today, "x", "0.001", TransactionStatus.COMPLETED));
    }

    @Test
    void internalTransferHasNoImpactOnNetWorthNorOnIncomeOrExpenses() {
        Money netWorthBefore = app.dashboard.summary().netWorth();

        List<Transaction> legs = app.transactions.createTransfer(new TransferDraft(checking.id(), livret.id(),
                app.today, "Épargne", new BigDecimal("500"), TransactionStatus.COMPLETED, null));

        assertEquals(2, legs.size());
        assertEquals(legs.get(0).transferGroup(), legs.get(1).transferGroup());
        assertEquals(Money.eur("500"), app.accounts.balanceOf(checking.id()));
        assertEquals(Money.eur("4700"), app.accounts.balanceOf(livret.id()));

        var summary = app.dashboard.summary();
        assertEquals(netWorthBefore, summary.netWorth());
        assertTrue(summary.monthIncome().isZero());
        assertTrue(summary.monthExpenses().isZero());
    }

    @Test
    void deletingOneLegDeletesTheWholeTransfer() {
        List<Transaction> legs = app.transactions.createTransfer(new TransferDraft(checking.id(), livret.id(),
                app.today, "Épargne", new BigDecimal("100"), TransactionStatus.COMPLETED, null));

        app.transactions.delete(legs.get(1).id());

        assertTrue(app.store.allTransactions().isEmpty());
        assertEquals(Money.eur("1000"), app.accounts.balanceOf(checking.id()));
    }

    @Test
    void updatingATransferUpdatesBothLegs() {
        List<Transaction> legs = app.transactions.createTransfer(new TransferDraft(checking.id(), livret.id(),
                app.today, "Épargne", new BigDecimal("100"), TransactionStatus.PLANNED, null));

        app.transactions.updateTransfer(legs.getFirst().transferGroup(), new TransferDraft(checking.id(), livret.id(),
                app.today, "Épargne", new BigDecimal("150"), TransactionStatus.COMPLETED, null));

        assertEquals(Money.eur("850"), app.accounts.balanceOf(checking.id()));
        assertEquals(Money.eur("4350"), app.accounts.balanceOf(livret.id()));
    }

    @Test
    void transferRequiresTwoDistinctAccounts() {
        assertThrows(BusinessException.class, () -> app.transactions.createTransfer(new TransferDraft(checking.id(),
                checking.id(), app.today, "x", BigDecimal.TEN, TransactionStatus.COMPLETED, null)));
    }

    @Test
    void accountWithOperationsCannotBeDeletedButCanBeArchived() {
        app.expense(checking, app.today, "Carrefour", "10", TransactionStatus.COMPLETED);

        assertThrows(BusinessException.class, () -> app.accounts.delete(checking.id()));
        assertTrue(app.accounts.setArchived(checking.id(), true).archived());
        assertThrows(BusinessException.class, () -> app.expense(checking, app.today, "x", "1", TransactionStatus.COMPLETED));
    }

    @Test
    void cannotEditATransferAsASimpleTransaction() {
        List<Transaction> legs = app.transactions.createTransfer(new TransferDraft(checking.id(), livret.id(),
                app.today, "Épargne", new BigDecimal("100"), TransactionStatus.COMPLETED, null));
        assertThrows(BusinessException.class, () -> app.transactions.update(legs.getFirst().id(),
                new com.financeapp.core.service.TransactionDraft(checking.id(), app.today, "x", BigDecimal.ONE,
                        TransactionType.EXPENSE, TransactionStatus.COMPLETED, null, null)));
    }
}
