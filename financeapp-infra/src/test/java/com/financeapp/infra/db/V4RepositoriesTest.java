package com.financeapp.infra.db;

import com.financeapp.core.account.Account;
import com.financeapp.core.account.AccountType;
import com.financeapp.core.loan.Loan;
import com.financeapp.core.money.Money;
import com.financeapp.core.simulation.Simulation;
import com.financeapp.core.simulation.SimulationItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class V4RepositoriesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 30);

    @TempDir
    Path dir;
    private SqliteTestDb db;
    private Account checking;

    @BeforeEach
    void setUp() {
        db = new SqliteTestDb(dir, TODAY);
        checking = db.accounts.save(Account.create("Compte courant", AccountType.CHECKING, Money.eur("2000"), TODAY));
    }

    @Test
    void loanRoundTripKeepsTheExactRate() {
        Loan saved = db.loans.save(new Loan(null, "Crédit auto", Money.eur("11000"), new BigDecimal("4.35"), 48,
                LocalDate.of(2026, 11, 10), null, Money.eur("9.90"), checking.id(), null, null, false, "Concession"), true);
        Loan read = db.loanRepo.findById(saved.id()).orElseThrow();
        assertEquals(saved, read);
        assertEquals(new BigDecimal("4.35"), read.annualRate());
        assertNotNull(read.recurringId());

        Loan unknownRate = db.loans.save(new Loan(null, "Prêt familial", Money.eur("3000"), null, 30,
                LocalDate.of(2026, 10, 5), Money.eur("100"), null, null, null, null, false, null), false);
        assertNull(db.loanRepo.findById(unknownRate.id()).orElseThrow().annualRate());
        assertEquals(2, db.loanRepo.findAll().size());
    }

    @Test
    void oneRecurringPaymentCannotBackTwoLoans() {
        Loan a = db.loans.save(new Loan(null, "A", Money.eur("1000"), BigDecimal.ONE, 10, LocalDate.of(2026, 10, 5),
                null, null, checking.id(), null, null, false, null), true);
        assertThrows(RuntimeException.class, () -> db.loanRepo.save(new Loan(null, "B", Money.eur("1000"), BigDecimal.ONE,
                10, LocalDate.of(2026, 10, 5), null, null, checking.id(), a.recurringId(), null, false, null)));
    }

    @Test
    void simulationItemsAreReplacedAtomically() {
        Simulation s = db.simulations.save(new Simulation(null, "Achat voiture", 24, List.of(
                new SimulationItem(null, SimulationItem.Kind.ONE_TIME, "Apport", Money.eur("-4000"),
                        LocalDate.of(2026, 10, 15), null, null, null, null),
                new SimulationItem(null, SimulationItem.Kind.LOAN, "Crédit", Money.eur("11000"),
                        LocalDate.of(2026, 11, 10), 48, null, Money.eur("250"), null),
                new SimulationItem(null, SimulationItem.Kind.STOP_RECURRING, "Netflix", null,
                        LocalDate.of(2026, 11, 1), null, null, null, 999L))));
        Simulation read = db.simulationRepo.findById(s.id()).orElseThrow();
        assertEquals(3, read.items().size());
        assertEquals(Money.eur("250"), read.items().get(1).payment());
        assertNull(read.items().get(1).annualRate());
        assertNull(read.items().get(2).amount());

        db.simulations.save(read.withItems(read.items().subList(0, 1)));
        assertEquals(1, db.simulationRepo.findById(s.id()).orElseThrow().items().size());
        db.simulations.delete(s.id());
        assertTrue(db.simulationRepo.findAll().isEmpty());
        assertEquals(0L, db.jdbc.sql("SELECT COUNT(*) FROM simulation_items").query(Long.class).single(), "cascade");
    }

    @Test
    void runningASimulationWritesNothingButTheScenario() {
        db.loans.save(new Loan(null, "Crédit auto", Money.eur("11000"), new BigDecimal("4.5"), 48,
                LocalDate.of(2026, 11, 10), null, null, checking.id(), null, null, false, null), true);
        long transactions = db.jdbc.sql("SELECT COUNT(*) FROM transactions").query(Long.class).single();
        long rules = db.jdbc.sql("SELECT COUNT(*) FROM recurring_transactions").query(Long.class).single();

        var result = db.simulations.run(new Simulation(null, "Test", 48, List.of(
                new SimulationItem(null, SimulationItem.Kind.MONTHLY, "Assurance", Money.eur("-100"),
                        LocalDate.of(2026, 10, 1), null, null, null, null))));
        assertEquals(Money.eur("100"), result.after().fixedCharges().minus(result.before().fixedCharges()));

        assertEquals(transactions, db.jdbc.sql("SELECT COUNT(*) FROM transactions").query(Long.class).single());
        assertEquals(rules, db.jdbc.sql("SELECT COUNT(*) FROM recurring_transactions").query(Long.class).single());
    }
}
