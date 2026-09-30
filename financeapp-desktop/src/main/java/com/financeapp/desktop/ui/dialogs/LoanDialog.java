package com.financeapp.desktop.ui.dialogs;

import com.financeapp.core.account.Account;
import com.financeapp.core.loan.AmortizationSchedule;
import com.financeapp.core.loan.Loan;
import com.financeapp.core.money.Money;
import com.financeapp.core.recurring.RecurringRule;
import com.financeapp.core.service.BusinessException;
import com.financeapp.desktop.ui.common.AmountParser;
import com.financeapp.desktop.ui.common.Choice;
import com.financeapp.desktop.ui.common.FormDialog;
import com.financeapp.desktop.ui.common.Formats;
import com.financeapp.desktop.ui.common.UiContext;
import com.financeapp.desktop.ui.common.Widgets;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Currency;

/**
 * Creation ou modification d'un credit. Le taux ou la mensualite suffit ; un
 * apercu (mensualite, fin, cout) se met a jour pendant la saisie.
 */
public final class LoanDialog extends FormDialog<Loan> {

    /** Valeurs speciales du choix de l'echeance recurrente. */
    private static final long NEW_RULE = -1L;
    private static final long NO_RULE = -2L;
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Formats.LOCALE);

    private final Loan existing;
    private final Currency currency;
    private final TextField name = new TextField();
    private final TextField principal = new TextField();
    private final TextField rate = new TextField();
    private final TextField months = new TextField();
    private final DatePicker firstPayment;
    private final TextField payment = new TextField();
    private final TextField insurance = new TextField();
    private final ComboBox<Choice<Long>> account;
    private final ComboBox<Choice<Long>> category = new ComboBox<>();
    private final ComboBox<Choice<Long>> recurring = new ComboBox<>();
    private final Label preview = Widgets.label("", "hint");

    public LoanDialog(UiContext ctx, Loan existing) {
        super(ctx, existing == null ? "Nouveau crédit" : "Modifier le crédit", "Enregistrer");
        this.existing = existing;
        this.currency = ctx.services().settings().baseCurrency();
        firstPayment = Widgets.datePicker(existing == null ? ctx.services().planning().today().plusMonths(1)
                : existing.firstPaymentDate());
        account = Widgets.accountCombo(ctx.services().accounts().findActive(), existing == null ? null : existing.accountId());
        account.getItems().addFirst(new Choice<>(null, "— Aucun —"));
        if (existing == null || existing.accountId() == null) {
            account.getSelectionModel().selectFirst();
            ctx.services().accounts().findActive().stream().filter(a -> a.type().includedInAvailableByDefault())
                    .findFirst().map(Account::id).ifPresent(id -> Widgets.select(account, id));
        }
        category.getItems().add(new Choice<>(null, "— Sans catégorie —"));
        category.getItems().addAll(Widgets.categoryChoices(ctx.services().categories().activeTree(), false, null));
        category.setMaxWidth(Double.MAX_VALUE);

        recurring.getItems().add(new Choice<>(NEW_RULE, "Créer l'échéance mensuelle correspondante"));
        for (RecurringRule r : ctx.services().loans().linkableRules(existing == null ? null : existing.id())) {
            recurring.getItems().add(new Choice<>(r.id(), "Lier à la récurrence « " + r.label() + " » (" +
                    ctx.formats().money(r.amount()) + ")"));
        }
        recurring.getItems().add(new Choice<>(NO_RULE, "Aucune : suivi du capital seulement"));
        recurring.setMaxWidth(Double.MAX_VALUE);

        name.setPromptText("Ex. Crédit auto");
        principal.setPromptText("Ex. 11000");
        rate.setPromptText("Ex. 4,5 — vide si inconnu");
        months.setPromptText("Ex. 48");
        payment.setPromptText("Calculée à partir du taux");
        insurance.setPromptText("0,00");
        if (existing == null) {
            recurring.getSelectionModel().selectFirst();
            Widgets.select(category, null);
        } else {
            name.setText(existing.name());
            principal.setText(AmountParser.toEditable(existing.principal().amount()));
            rate.setText(existing.annualRate() == null ? "" : AmountParser.toEditable(existing.annualRate()));
            months.setText(Integer.toString(existing.termMonths()));
            payment.setText(existing.payment() == null ? "" : AmountParser.toEditable(existing.payment().amount()));
            insurance.setText(existing.monthlyInsurance().isZero() ? "" : AmountParser.toEditable(existing.monthlyInsurance().amount()));
            Widgets.select(category, existing.categoryId());
            Widgets.select(recurring, existing.recurringId() == null ? NO_RULE : existing.recurringId());
            if (recurring.getValue() == null) {
                recurring.getSelectionModel().selectLast();
            }
        }

        addRow("Nom", name);
        addRow("Capital emprunté", principal);
        addRow("Taux annuel (%)", rate);
        addRow("Durée (mois)", months);
        addRow("1re mensualité", firstPayment);
        addRow("Mensualité", payment);
        addRow("Assurance / mois", insurance);
        addRow("Compte débité", account);
        addRow("Catégorie", category);
        addRow("Mensualités", recurring);
        preview.setWrapText(true);
        preview.setMaxWidth(480);
        addFullRow(preview);
        Label hint = Widgets.label("L'échéance récurrente fait apparaître chaque mensualité dans « À venir », le disponible "
                + "réel et les prévisions. Une échéance liée est alignée sur le crédit (montant assurance comprise, dates).",
                "hint");
        hint.setWrapText(true);
        hint.setMaxWidth(480);
        addFullRow(hint);

        for (TextField field : new TextField[]{principal, rate, months, payment, insurance}) {
            field.textProperty().addListener((o, a, b) -> updatePreview());
        }
        firstPayment.valueProperty().addListener((o, a, b) -> updatePreview());
        updatePreview();
        setOnShown(e -> name.requestFocus());
    }

    private void updatePreview() {
        if (principal.getText().isBlank() || months.getText().isBlank()
                || rate.getText().isBlank() && payment.getText().isBlank()) {
            preview.setText("Saisissez le capital, la durée et le taux (ou la mensualité) pour voir la mensualité, "
                    + "la date de fin et le coût du crédit.");
            fitToContent();
            return;
        }
        try {
            Loan loan = build("Aperçu");
            AmortizationSchedule s = ctx.services().loans().calculator().schedule(loan);
            Formats f = ctx.formats();
            StringBuilder text = new StringBuilder("Mensualité " + f.money(s.payment()));
            if (!loan.monthlyInsurance().isZero()) {
                text.append(" + ").append(f.money(loan.monthlyInsurance())).append(" d'assurance");
            }
            text.append(" · taux ").append(Formats.percent(s.annualRate()));
            if (s.rateEstimated()) {
                text.append(" (estimé)");
            }
            text.append("\nDernière mensualité : ").append(MONTH.format(s.endDate()))
                    .append(" · coût : ").append(f.money(s.totalInterest())).append(" d'intérêts");
            if (!s.totalInsurance().isZero()) {
                text.append(" + ").append(f.money(s.totalInsurance())).append(" d'assurance");
            }
            preview.setText(text.toString());
            fitToContent();
        } catch (RuntimeException e) {
            preview.setText(e.getMessage() == null ? "" : "Aperçu indisponible : " + e.getMessage());
            fitToContent();
        }
    }

    private Loan build(String loanName) {
        BigDecimal capital = AmountParser.parse(principal.getText())
                .orElseThrow(() -> new BusinessException("Capital emprunté invalide"));
        BigDecimal annualRate = rate.getText().isBlank() ? null : AmountParser.parse(rate.getText())
                .orElseThrow(() -> new BusinessException("Taux invalide : saisissez par exemple 4,5"));
        int term;
        try {
            term = Integer.parseInt(months.getText().strip());
        } catch (NumberFormatException e) {
            throw new BusinessException("Durée invalide : nombre de mensualités, par exemple 48");
        }
        Money monthly = payment.getText().isBlank() ? null : Money.of(AmountParser.parse(payment.getText())
                .orElseThrow(() -> new BusinessException("Mensualité invalide")), currency);
        Money fee = insurance.getText().isBlank() ? null : Money.of(AmountParser.parse(insurance.getText())
                .orElseThrow(() -> new BusinessException("Assurance invalide")), currency);
        LocalDate first = Widgets.dateValue(firstPayment);
        if (first == null) {
            throw new BusinessException("Date de la première mensualité invalide");
        }
        Long choice = Widgets.selected(recurring);
        Long recurringId = choice == null || choice < 0 ? null : choice;
        try {
            return new Loan(existing == null ? null : existing.id(), loanName, Money.of(capital, currency), annualRate,
                    term, first, monthly, fee, Widgets.selected(account), recurringId, Widgets.selected(category),
                    existing != null && existing.archived(), existing == null ? null : existing.note());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(e.getMessage());
        }
    }

    @Override
    protected Loan submit() {
        if (name.getText().isBlank()) {
            throw new BusinessException("Le nom du crédit est obligatoire");
        }
        Long choice = Widgets.selected(recurring);
        boolean manage = choice == null || choice != NO_RULE;
        return ctx.services().loans().save(build(name.getText()), manage);
    }
}
