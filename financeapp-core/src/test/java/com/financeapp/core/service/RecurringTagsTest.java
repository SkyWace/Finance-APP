package com.financeapp.core.service;

import com.financeapp.core.account.Account;
import com.financeapp.core.account.AccountType;
import com.financeapp.core.money.Money;
import com.financeapp.core.recurring.Frequency;
import com.financeapp.core.recurring.RecurringRule;
import com.financeapp.core.testing.TestApp;
import com.financeapp.core.transaction.Transaction;
import com.financeapp.core.transaction.TransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Etiquettes des operations recurrentes. Aujourd'hui : 10/10/2026. */
class RecurringTagsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);
    private final TestApp app = new TestApp(TODAY);
    private final Account checking = app.account("Compte courant", AccountType.CHECKING, "3000");
    private final Account savings = app.account("Livret A", AccountType.SAVINGS, "0");

    @Test
    void confirmedOccurrencesCarryTheRuleTags() {
        Set<Long> work = app.tags.resolve(List.of("travail", "remboursable"));
        RecurringRule phone = app.recurring.save(new RecurringRule(null, checking.id(), null, TransactionType.EXPENSE,
                "Forfait mobile", Money.eur("20"), null, Frequency.MONTHLY, 1, LocalDate.of(2026, 1, 5), null, null,
                false, true, null, List.of(), work));
        assertEquals(work, phone.tagIds());

        Transaction paid = app.recurring.confirm(phone.id(), LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 6),
                new BigDecimal("21.50")).getFirst();
        assertEquals(work, paid.tagIds(), "l'operation creee reprend les etiquettes de la regle");

        Transaction skipped = app.recurring.skip(phone.id(), LocalDate.of(2026, 2, 5)).getFirst();
        assertEquals(work, skipped.tagIds());
    }

    @Test
    void copiesKeepTheTagsAndTransfersHaveNone() {
        Set<Long> tags = app.tags.resolve(List.of("maison"));
        RecurringRule rule = new RecurringRule(null, checking.id(), null, TransactionType.EXPENSE, "Assurance",
                Money.eur("30"), null, Frequency.MONTHLY, 1, TODAY, null, null, false, true, null, List.of(), tags);
        assertEquals(tags, rule.withEndDate(TODAY.plusYears(1)).tagIds());
        assertEquals(tags, rule.withTrackedFrom(TODAY).tagIds());

        assertThrows(IllegalArgumentException.class, () -> new RecurringRule(null, checking.id(), savings.id(),
                TransactionType.TRANSFER, "Épargne", Money.eur("100"), null, Frequency.MONTHLY, 1, TODAY, null, null,
                false, true, null, List.of(), tags));
    }
}
