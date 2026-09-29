package com.financeapp.core.service;

import com.financeapp.core.money.Money;
import com.financeapp.core.port.TransactionRepository;
import com.financeapp.core.recurring.RecurringRule;
import com.financeapp.core.settings.SettingsService;
import com.financeapp.core.subscription.RecurringPaymentCandidate;
import com.financeapp.core.subscription.RecurringPaymentDetector;
import com.financeapp.core.transaction.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Set;

/**
 * Abonnements : operations recurrentes de depense classees dans la categorie
 * "Abonnements" (ou ses sous-categories), et paiements reguliers detectes
 * dans l'historique mais pas encore enregistres.
 */
public final class SubscriptionService {

    public static final String SUBSCRIPTIONS_CODE = "SUBSCRIPTIONS";

    /** Abonnements enregistres et leur cout. */
    public record Overview(List<RecurringRule> subscriptions, Money monthlyTotal, Money yearlyTotal) {
    }

    private final RecurringService recurring;
    private final CategoryService categories;
    private final TransactionRepository transactions;
    private final PlanningService planning;
    private final SettingsService settings;
    private final RecurringPaymentDetector detector = new RecurringPaymentDetector();

    public SubscriptionService(RecurringService recurring, CategoryService categories, TransactionRepository transactions,
                               PlanningService planning, SettingsService settings) {
        this.recurring = recurring;
        this.categories = categories;
        this.transactions = transactions;
        this.planning = planning;
        this.settings = settings;
    }

    public Overview overview() {
        Currency currency = settings.baseCurrency();
        Set<Long> scope = categories.findBySystemCode(SUBSCRIPTIONS_CODE)
                .map(c -> categories.selfAndChildren(c.id())).orElse(Set.of());
        LocalDate today = planning.today();
        List<RecurringRule> subs = recurring.findAll().stream()
                .filter(r -> r.active() && r.type() == TransactionType.EXPENSE)
                .filter(r -> r.endDate() == null || !r.endDate().isBefore(today))
                .filter(r -> r.categoryId() != null && scope.contains(r.categoryId()))
                .filter(r -> r.amount().currency().equals(currency))
                .sorted(Comparator.comparing((RecurringRule r) -> r.monthlyEquivalent().amount()))
                .toList();
        Money monthly = subs.stream().map(r -> r.monthlyEquivalent().negate()).reduce(Money.zero(currency), Money::plus);
        return new Overview(subs, monthly, monthly.multiply(BigDecimal.valueOf(12)));
    }

    /** Paiements reguliers des 13 derniers mois qui ne sont pas encore des operations recurrentes. */
    public List<RecurringPaymentCandidate> detectCandidates() {
        LocalDate today = planning.today();
        Set<String> dismissed = settings.dismissedRecurringPayments();
        return detector.detect(transactions.findCounted(today.minusMonths(13), today), recurring.findAll(), today)
                .stream()
                .filter(c -> !dismissed.contains(RecurringPaymentDetector.normalize(c.label())))
                .toList();
    }

    /** Ne plus proposer ce paiement (ex. un restaurant frequente chaque mois n'est pas un abonnement). */
    public void dismiss(RecurringPaymentCandidate candidate) {
        Set<String> dismissed = settings.dismissedRecurringPayments();
        dismissed.add(RecurringPaymentDetector.normalize(candidate.label()));
        settings.setDismissedRecurringPayments(dismissed);
    }
}
