package com.financeapp.core.available;

import com.financeapp.core.available.AvailableBalanceResult.SectionKind;
import com.financeapp.core.money.Money;
import com.financeapp.core.planning.PlannedItem;
import com.financeapp.core.transaction.TransactionType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AvailableBalanceEngineTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    private static final LocalDate END = LocalDate.of(2026, 10, 31);
    private static final long CHECKING = 1;
    private static final long JOINT = 2;
    private static final long SAVINGS = 3;

    private final AvailableBalanceEngine engine = new AvailableBalanceEngine();

    private static PlannedItem item(long account, int day, String label, String amount, TransactionType type) {
        return new PlannedItem(TODAY.withDayOfMonth(day), account, label, Money.eur(amount), type, null,
                PlannedItem.Source.RECURRING, null, 1L, null, true);
    }

    private static PlannedItem transfer(long account, long other, int day, String amount) {
        return new PlannedItem(TODAY.withDayOfMonth(day), account, "Virement", Money.eur(amount),
                TransactionType.TRANSFER, null, PlannedItem.Source.RECURRING, null, 1L, other, true);
    }

    private static AvailableBalanceInput input(List<AccountBalance> balances, List<PlannedItem> items,
                                               List<Reservation> reservations, boolean includeIncome) {
        return new AvailableBalanceInput(Money.EUR, TODAY, END, balances, items, reservations, includeIncome);
    }

    private static List<AccountBalance> checking(String balance) {
        return List.of(new AccountBalance(CHECKING, "Compte courant", Money.eur(balance)));
    }

    /** Scenario du cahier des charges : 1000 + 1800 - 700 - 200 - 300 = 1600. */
    @Test
    void realisticMonthWithSalaryChargesLoanAndBudget() {
        List<PlannedItem> items = List.of(
                item(CHECKING, 5, "Loyer + charges", "-700", TransactionType.EXPENSE),
                item(CHECKING, 10, "Crédit auto", "-200", TransactionType.EXPENSE),
                item(CHECKING, 28, "Salaire", "1800", TransactionType.INCOME));
        List<Reservation> budgets = List.of(new Reservation("Courses", Money.eur("300"), Reservation.Kind.BUDGET));

        AvailableBalanceResult withIncome = engine.compute(input(checking("1000"), items, budgets, true));
        assertEquals(Money.eur("1600"), withIncome.available());
        assertEquals(Money.eur("1900"), withIncome.availableBeforeReservations());
        assertEquals(Money.eur("-900"), withIncome.total(SectionKind.PLANNED_EXPENSES));
        assertEquals(Money.eur("-300"), withIncome.total(SectionKind.RESERVATIONS));
        assertEquals(Money.eur("1800"), withIncome.total(SectionKind.EXPECTED_INCOME));

        AvailableBalanceResult withoutIncome = engine.compute(input(checking("1000"), items, budgets, false));
        assertEquals(Money.eur("-200"), withoutIncome.available());
        // Le salaire reste visible, mais dans une section non comptee
        AvailableBalanceResult.Section excluded = withoutIncome.section(SectionKind.EXCLUDED_INCOME).orElseThrow();
        assertFalse(excluded.counted());
        assertEquals(Money.eur("1800"), excluded.total());
    }

    /** Exemple du brief : 1420 - 934 - 300 - 100 = 86. */
    @Test
    void briefExampleWithSavingsTransfer() {
        List<PlannedItem> items = List.of(
                item(CHECKING, 3, "Loyer", "-650", TransactionType.EXPENSE),
                item(CHECKING, 5, "Assurance", "-90", TransactionType.EXPENSE),
                item(CHECKING, 10, "Crédit", "-194", TransactionType.EXPENSE),
                transfer(CHECKING, SAVINGS, 2, "-100"));
        List<Reservation> budgets = List.of(
                new Reservation("Carburant", Money.eur("200"), Reservation.Kind.BUDGET),
                new Reservation("Courses", Money.eur("100"), Reservation.Kind.BUDGET));

        AvailableBalanceResult r = engine.compute(input(checking("1420"), items, budgets, true));

        assertEquals(Money.eur("86"), r.available());
        assertEquals(Money.eur("-934"), r.total(SectionKind.PLANNED_EXPENSES));
        assertEquals(Money.eur("-100"), r.total(SectionKind.SAVINGS_TRANSFERS));
    }

    @Test
    void transferBetweenTwoAccountsInScopeIsNeutral() {
        List<AccountBalance> balances = List.of(
                new AccountBalance(CHECKING, "Courant", Money.eur("500")),
                new AccountBalance(JOINT, "Joint", Money.eur("300")));
        List<PlannedItem> items = List.of(transfer(CHECKING, JOINT, 3, "-200"), transfer(JOINT, CHECKING, 3, "200"));

        AvailableBalanceResult r = engine.compute(input(balances, items, List.of(), true));

        assertEquals(Money.eur("800"), r.available());
        assertTrue(r.section(SectionKind.SAVINGS_TRANSFERS).isEmpty());
    }

    @Test
    void ignoresItemsOutOfScopeOrAfterHorizon() {
        List<PlannedItem> items = new ArrayList<>();
        items.add(item(SAVINGS, 5, "Hors périmètre", "-50", TransactionType.EXPENSE));
        items.add(new PlannedItem(END.plusDays(1), CHECKING, "Après échéance", Money.eur("-70"),
                TransactionType.EXPENSE, null, PlannedItem.Source.RECURRING, null, 1L, null, true));
        items.add(new PlannedItem(END, CHECKING, "Dernier jour", Money.eur("-30"),
                TransactionType.EXPENSE, null, PlannedItem.Source.RECURRING, null, 1L, null, true));

        assertEquals(Money.eur("70"), engine.compute(input(checking("100"), items, List.of(), true)).available());
    }

    @Test
    void overdueItemsAreDeductedAndFlagged() {
        PlannedItem overdue = new PlannedItem(TODAY.minusDays(2), CHECKING, "Facture en retard", Money.eur("-40"),
                TransactionType.EXPENSE, null, PlannedItem.Source.PLANNED_TRANSACTION, 9L, null, null, true);

        AvailableBalanceResult r = engine.compute(input(checking("100"), List.of(overdue), List.of(), true));

        assertEquals(Money.eur("60"), r.available());
        assertTrue(r.section(SectionKind.PLANNED_EXPENSES).orElseThrow().lines().getFirst().overdue());
    }

    @Test
    void uncertainIncomeIsNeverCounted() {
        PlannedItem bonus = new PlannedItem(TODAY.withDayOfMonth(15), CHECKING, "Prime éventuelle", Money.eur("500"),
                TransactionType.INCOME, null, PlannedItem.Source.RECURRING, null, 1L, null, false);

        AvailableBalanceResult r = engine.compute(input(checking("100"), List.of(bonus), List.of(), true));

        assertEquals(Money.eur("100"), r.available());
        assertEquals(Money.eur("500"), r.total(SectionKind.EXCLUDED_INCOME));
    }

    @Test
    void countedSectionsAlwaysAddUpToTheResult() {
        List<PlannedItem> items = List.of(
                item(CHECKING, 3, "A", "-12.34", TransactionType.EXPENSE),
                item(CHECKING, 4, "B", "56.78", TransactionType.INCOME),
                transfer(CHECKING, SAVINGS, 6, "-10.01"));
        AvailableBalanceResult r = engine.compute(input(checking("999.99"), items,
                List.of(new Reservation("R", Money.eur("0.99"), Reservation.Kind.OTHER)), true));

        Money sum = r.sections().stream().filter(AvailableBalanceResult.Section::counted)
                .map(AvailableBalanceResult.Section::total).reduce(Money.zero(Money.EUR), Money::plus);
        assertEquals(r.available(), sum);
        assertEquals(Money.eur("1033.43"), r.available());
    }
}
