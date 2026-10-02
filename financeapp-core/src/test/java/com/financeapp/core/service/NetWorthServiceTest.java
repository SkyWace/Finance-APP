package com.financeapp.core.service;

import com.financeapp.core.account.Account;
import com.financeapp.core.account.AccountType;
import com.financeapp.core.money.Money;
import com.financeapp.core.testing.TestApp;
import com.financeapp.core.transaction.TransactionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Evolution du patrimoine. Aujourd'hui : 10/10/2026. */
class NetWorthServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);
    private final TestApp app = new TestApp(TODAY);

    @BeforeEach
    void setUp() {
        // Compte courant ouvert le 1er juillet avec 1 000, livret ouvert le 15 aout avec 5 000.
        Account checking = app.accounts.save(Account.create("Courant", AccountType.CHECKING, Money.eur("1000"),
                LocalDate.of(2026, 7, 1)));
        Account livret = app.accounts.save(Account.create("Livret A", AccountType.LIVRET_A, Money.eur("5000"),
                LocalDate.of(2026, 8, 15)));
        app.expense(checking, LocalDate.of(2026, 7, 20), "Courses", "300", TransactionStatus.COMPLETED);
        app.expense(checking, LocalDate.of(2026, 9, 5), "Prévue", "999", TransactionStatus.PLANNED);
        app.income(checking, LocalDate.of(2026, 8, 28), "Salaire", "2000", TransactionStatus.COMPLETED);
        // Releve du livret au 30/09 : 5 100 (interets), puis versement de 200 le 2 octobre.
        app.accounts.recordValuation(livret.id(), LocalDate.of(2026, 9, 30), new BigDecimal("5100"));
        app.transactions.createTransfer(new TransferDraft(checking.id(), livret.id(), LocalDate.of(2026, 10, 2),
                "Épargne", new BigDecimal("200"), TransactionStatus.COMPLETED, null));
    }

    @Test
    void monthEndBalancesFromTheFirstOpeningThenToday() {
        NetWorthService.History h = app.netWorth.history(0);
        assertEquals(List.of(LocalDate.of(2026, 7, 31), LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 30), TODAY),
                h.points().stream().map(NetWorthService.Point::date).toList());
        assertEquals(List.of(Money.eur("700"), Money.eur("7700"), Money.eur("7800"), Money.eur("7800")),
                h.points().stream().map(NetWorthService.Point::total).toList(),
                "livret compte a partir de son ouverture ; valorisation reprise ; virement neutre ; prevue ignoree");
        NetWorthService.Point last = h.points().getLast();
        assertEquals(Money.eur("2500"), last.current());
        assertEquals(Money.eur("5300"), last.savings());
        assertEquals(app.dashboard.summary().netWorth(), last.total(), "dernier point = tableau de bord");
        assertEquals(Money.eur("7100"), h.change());
    }

    @Test
    void periodIsLimitedAndOtherCurrenciesAreReported() {
        app.accounts.save(Account.create("Compte suisse", AccountType.CHECKING, Money.of(new BigDecimal("500"),
                java.util.Currency.getInstance("CHF")), LocalDate.of(2026, 1, 1)));
        NetWorthService.History h = app.netWorth.history(2);
        assertEquals(LocalDate.of(2026, 8, 31), h.points().getFirst().date());
        assertEquals(3, h.points().size());
        assertEquals(1, h.otherCurrencies());
        assertEquals(Money.eur("100"), h.change());
    }

    @Test
    void withoutHistoryThereIsNothingToDraw() {
        TestApp empty = new TestApp(TODAY);
        assertTrue(empty.netWorth.history(12).isEmpty());
        empty.accounts.save(Account.create("Neuf", AccountType.CHECKING, Money.eur("10"), TODAY));
        assertTrue(empty.netWorth.history(12).isEmpty(), "un seul point : aujourd'hui");
    }
}
