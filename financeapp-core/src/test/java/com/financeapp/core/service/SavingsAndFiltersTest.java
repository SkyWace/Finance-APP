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
import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.*;

/** Epargne detenue (produits, valorisations, plafonds) et filtres par compte. Aujourd'hui : 30/09/2026. */
class SavingsAndFiltersTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 30);
    private final TestApp app = new TestApp(TODAY);
    private Account checking;
    private Account livretA;
    private Account ldds;
    private Account pea;
    private Account lifeInsurance;

    @BeforeEach
    void setUp() {
        checking = app.account("Compte courant", AccountType.CHECKING, "1500");
        livretA = app.account("Livret A", AccountType.LIVRET_A, "8000");
        ldds = app.account("LDDS", AccountType.LDDS, "12000");
        pea = app.account("PEA", AccountType.PEA, "5000");
        lifeInsurance = app.account("Assurance-vie", AccountType.LIFE_INSURANCE, "20000");
    }

    private void transfer(Account from, Account to, String date, String amount) {
        app.transactions.createTransfer(new TransferDraft(from.id(), to.id(), LocalDate.parse(date), "Virement",
                new BigDecimal(amount), TransactionStatus.COMPLETED, null));
    }

    @Test
    void savingsTypesAreSavingsAndExcludedFromTheAvailableByDefault() {
        for (AccountType t : AccountType.values()) {
            if (t.isSavings()) {
                assertEquals(AccountType.Group.SAVINGS, t.group(), t.name());
                assertFalse(t.includedInAvailableByDefault(), t.name());
            }
        }
        assertEquals(new BigDecimal("22950"), AccountType.LIVRET_A.depositCeiling());
        assertTrue(AccountType.PEA.marketValued());
        assertEquals(AccountType.Savings.LONG_TERM, AccountType.EMPLOYEE_SAVINGS.savings());
        assertFalse(AccountType.CHECKING.isSavings());
    }

    @Test
    void valuationReplacesTheComputedBalanceAndLaterMovementsAddUp() {
        transfer(checking, pea, "2026-09-10", "300");
        app.accounts.recordValuation(pea.id(), LocalDate.of(2026, 6, 30), new BigDecimal("5100"));
        app.accounts.recordValuation(pea.id(), LocalDate.of(2026, 9, 15), new BigDecimal("5400"));
        transfer(checking, pea, "2026-09-20", "100");

        assertEquals(Money.eur("5500"), app.accounts.balanceOf(pea.id()),
                "valeur du 15/09 + versement du 20/09 ; celui du 10/09 est deja dans la valeur");
        assertEquals(Money.eur("1100"), app.accounts.balanceOf(checking.id()), "le compte courant n'est pas concerne");

        app.accounts.recordValuation(pea.id(), LocalDate.of(2026, 9, 15), new BigDecimal("5450"));
        assertEquals(2, app.accounts.valuations(pea.id()).size(), "une seule valeur par date");
        assertEquals(Money.eur("5550"), app.accounts.balanceOf(pea.id()));

        assertThrows(BusinessException.class, () -> app.accounts.recordValuation(pea.id(), TODAY.plusDays(1), BigDecimal.TEN));
        assertThrows(BusinessException.class, () -> app.accounts.recordValuation(pea.id(), TODAY, new BigDecimal("-1")));

        app.accounts.deleteValuation(app.accounts.latestValuation(pea.id()).orElseThrow().id());
        assertEquals(Money.eur("5500"), app.accounts.balanceOf(pea.id()), "retour a la valeur du 30/06 + versements posterieurs");
    }

    @Test
    void overviewGroupsProductsAndShowsCeilings() {
        transfer(checking, livretA, "2026-09-05", "200");
        app.accounts.recordValuation(pea.id(), LocalDate.of(2026, 6, 30), new BigDecimal("5100"));
        app.accounts.recordValuation(pea.id(), LocalDate.of(2026, 9, 15), new BigDecimal("5400"));

        SavingsService.Overview o = app.savings.overview();
        assertEquals(2, o.available().size());
        assertEquals(2, o.longTerm().size());
        assertEquals(Money.eur("20200"), o.totalAvailable());
        assertEquals(Money.eur("25400"), o.totalLongTerm());
        assertEquals(Money.eur("45600"), o.total());
        assertEquals(Money.eur("46900"), o.netWorth(), "1 300 sur le compte courant");
        assertEquals(new BigDecimal("97.2"), o.shareOfNetWorth());

        SavingsService.Holding a = o.available().stream().filter(h -> h.account().id().equals(livretA.id())).findFirst().orElseThrow();
        assertEquals(Money.eur("22950"), a.ceiling());
        assertEquals(Money.eur("14750"), a.room());
        assertEquals(new BigDecimal("35.7"), a.ceilingPercent());
        SavingsService.Holding full = o.available().stream().filter(h -> h.account().id().equals(ldds.id())).findFirst().orElseThrow();
        assertEquals(Money.eur("0"), full.room(), "plafond du LDDS atteint");

        SavingsService.Holding p = o.longTerm().stream().filter(h -> h.account().id().equals(pea.id())).findFirst().orElseThrow();
        assertNull(p.ceiling(), "pas de plafond affiche pour un placement valorise");
        assertEquals(Money.eur("300"), p.changeSincePrevious());
        assertNull(o.longTerm().stream().filter(h -> h.account().id().equals(lifeInsurance.id())).findFirst()
                .orElseThrow().lastValuation());

        app.accounts.setArchived(lifeInsurance.id(), true);
        assertEquals(1, app.savings.overview().longTerm().size(), "les comptes archives ne sont pas listes");
    }

    @Test
    void statisticsCanBeRestrictedToOneAccount() {
        Account joint = app.account("Compte joint", AccountType.JOINT, "0");
        app.expense(checking, LocalDate.of(2026, 9, 10), "Courses", "50", TransactionStatus.COMPLETED);
        app.expense(joint, LocalDate.of(2026, 9, 12), "Restaurant", "30", TransactionStatus.COMPLETED);
        transfer(checking, livretA, "2026-09-15", "200");
        YearMonth month = YearMonth.of(2026, 9);

        assertEquals(Money.eur("-80"), app.statistics.report(month, month.minusMonths(1), 3).months().getLast().expenses());
        assertEquals(Money.eur("-50"), app.statistics.report(month, month.minusMonths(1), 3, checking.id())
                .months().getLast().expenses(), "le virement vers le livret n'est pas une depense");
        assertEquals(Money.eur("-30"), app.statistics.report(month, month.minusMonths(1), 3, joint.id())
                .months().getLast().expenses());
        assertEquals(1, app.statistics.merchants(month.atDay(1), month.atEndOfMonth(), 10, joint.id()).size());
        assertEquals(Money.eur("30"), app.statistics.comparePeriods(month.minusMonths(1).atDay(1),
                month.minusMonths(1).atEndOfMonth(), month.atDay(1), month.atEndOfMonth(), joint.id()).total().current());
    }

    @Test
    void calendarCanFollowOneAccount() {
        transfer(checking, livretA, "2026-09-15", "200");
        app.expense(checking, LocalDate.of(2026, 9, 20), "Courses", "50", TransactionStatus.COMPLETED);
        app.expense(checking, LocalDate.of(2026, 10, 5), "Assurance", "100", TransactionStatus.PLANNED);
        YearMonth september = YearMonth.of(2026, 9);

        var livret = app.calendar.month(september, livretA.id());
        assertEquals(Money.eur("8000"), livret.get(13).balance(), "14/09");
        assertEquals(1, livret.get(14).realized().size(), "le virement recu le 15/09");
        assertEquals(Money.eur("8200"), livret.get(14).balance());
        assertEquals(Money.eur("8200"), livret.getLast().balance());
        assertTrue(livret.getLast().projected(), "aujourd'hui");

        var current = app.calendar.month(september, checking.id());
        assertEquals(Money.eur("1300"), current.get(18).balance(), "19/09 : apres le virement");
        assertEquals(Money.eur("1250"), current.get(19).balance());
        assertTrue(current.get(14).realized().stream().allMatch(t -> t.accountId() == checking.id()));

        var october = app.calendar.month(YearMonth.of(2026, 10), checking.id());
        assertEquals(1, october.get(4).planned().size());
        assertEquals(Money.eur("1250"), october.get(3).balance());
        assertEquals(Money.eur("1150"), october.get(4).balance(), "prevision : operation prevue du 05/10");
        assertTrue(app.calendar.month(YearMonth.of(2026, 10), livretA.id()).stream().allMatch(d -> d.planned().isEmpty()));

        app.accounts.recordValuation(livretA.id(), LocalDate.of(2026, 9, 10), new BigDecimal("8050"));
        livret = app.calendar.month(september, livretA.id());
        assertNull(livret.get(8).balance(), "avant la valeur saisie, le solde n'est pas reconstituable");
        assertEquals(Money.eur("8050"), livret.get(9).balance());
        assertEquals(Money.eur("8250"), livret.get(14).balance());
    }
}
