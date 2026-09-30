package com.financeapp.desktop.ui.dialogs;

import com.financeapp.core.loan.AmortizationSchedule;
import com.financeapp.core.money.Money;
import com.financeapp.core.recurring.RecurringRule;
import com.financeapp.core.service.BusinessException;
import com.financeapp.core.simulation.SimulationItem;
import com.financeapp.core.simulation.SimulationItem.Kind;
import com.financeapp.core.transaction.TransactionType;
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

/** Saisie d'une hypothese de simulation ; les champs dependent du type. Rien n'est enregistre ici. */
public final class SimulationItemDialog extends FormDialog<SimulationItem> {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Formats.LOCALE);

    private final Kind kind;
    private final SimulationItem existing;
    private final Currency currency;
    private final TextField label = new TextField();
    private final ComboBox<Choice<Boolean>> direction = new ComboBox<>();
    private final TextField amount = new TextField();
    private final DatePicker date;
    private final TextField months = new TextField();
    private final TextField rate = new TextField();
    private final TextField payment = new TextField();
    private final ComboBox<Choice<Long>> rule = new ComboBox<>();
    private final Label preview = Widgets.label("", "hint");
    private final java.util.Map<Long, String> ruleLabels = new java.util.HashMap<>();

    public SimulationItemDialog(UiContext ctx, Kind kind, SimulationItem existing) {
        super(ctx, kind.label(), existing == null ? "Ajouter" : "Enregistrer");
        this.kind = kind;
        this.existing = existing;
        this.currency = ctx.services().settings().baseCurrency();
        LocalDate today = ctx.services().planning().today();
        date = Widgets.datePicker(existing != null ? existing.date() : today.plusMonths(1).withDayOfMonth(1));
        direction.getItems().setAll(new Choice<>(false, "Dépense"), new Choice<>(true, "Rentrée d'argent"));
        direction.getSelectionModel().selectFirst();
        direction.setMaxWidth(Double.MAX_VALUE);
        rule.setMaxWidth(Double.MAX_VALUE);

        if (existing != null) {
            label.setText(existing.label());
            if (existing.amount() != null) {
                Widgets.select(direction, existing.amount().isPositive() && kind != Kind.LOAN);
                amount.setText(AmountParser.toEditable(existing.amount().abs().amount()));
            }
            months.setText(existing.months() == null ? "" : existing.months().toString());
            rate.setText(existing.annualRate() == null ? "" : AmountParser.toEditable(existing.annualRate()));
            payment.setText(existing.payment() == null ? "" : AmountParser.toEditable(existing.payment().amount()));
        }

        switch (kind) {
            case ONE_TIME -> {
                label.setPromptText("Ex. Apport voiture, prime, travaux");
                addRow("Libellé", label);
                addRow("Sens", direction);
                addRow("Montant", amount);
                addRow("Date", date);
            }
            case MONTHLY -> {
                label.setPromptText("Ex. Assurance auto, nouveau loyer, augmentation");
                months.setPromptText("Vide = jusqu'à la fin de la simulation");
                addRow("Libellé", label);
                addRow("Sens", direction);
                addRow("Montant par mois", amount);
                addRow("À partir du", date);
                addRow("Pendant (mois)", months);
            }
            case LOAN -> {
                label.setPromptText("Ex. Crédit voiture");
                amount.setPromptText("Capital emprunté, ex. 11000");
                rate.setPromptText("Ex. 4,5 — vide si inconnu");
                payment.setPromptText("Vide = calculée à partir du taux");
                months.setPromptText("Ex. 48");
                addRow("Libellé", label);
                addRow("Capital emprunté", amount);
                addRow("Taux annuel (%)", rate);
                addRow("Mensualité", payment);
                addRow("Durée (mois)", months);
                addRow("1re mensualité", date);
                preview.setWrapText(true);
                preview.setMaxWidth(480);
                addFullRow(preview);
                for (TextField f : new TextField[]{amount, rate, payment, months}) {
                    f.textProperty().addListener((o, a, b) -> updateLoanPreview());
                }
                date.valueProperty().addListener((o, a, b) -> updateLoanPreview());
                updateLoanPreview();
            }
            case STOP_RECURRING -> {
                for (RecurringRule r : ctx.services().recurring().findAll()) {
                    if (r.active() && r.type() != TransactionType.TRANSFER) {
                        ruleLabels.put(r.id(), r.label());
                        rule.getItems().add(new Choice<>(r.id(), r.label() + " (" + (r.type() == TransactionType.INCOME
                                ? "+" : "-") + ctx.formats().money(r.amount()) + ")"));
                    }
                }
                if (existing != null) {
                    Widgets.select(rule, existing.recurringId());
                }
                if (rule.getValue() == null) {
                    rule.getSelectionModel().selectFirst();
                }
                addRow("Récurrence", rule);
                addRow("À partir du", date);
                Label hint = Widgets.label("Ex. résilier un abonnement, finir un crédit par anticipation, changer de "
                        + "logement. La récurrence réelle n'est pas modifiée.", "hint");
                hint.setWrapText(true);
                hint.setMaxWidth(480);
                addFullRow(hint);
            }
        }
        setOnShown(e -> (kind == Kind.STOP_RECURRING ? rule : label).requestFocus());
    }

    private void updateLoanPreview() {
        try {
            SimulationItem item = build();
            AmortizationSchedule s = ctx.services().loans().calculator().schedule(item.amount(), item.annualRate(),
                    item.payment(), item.months(), item.date(), null);
            preview.setText("Mensualité " + ctx.formats().money(s.payment()) + " · taux " + Formats.percent(s.annualRate())
                    + (s.rateEstimated() ? " (estimé)" : "") + " · fin " + MONTH.format(s.endDate()) + " · intérêts "
                    + ctx.formats().money(s.totalInterest()));
            fitToContent();
        } catch (RuntimeException e) {
            preview.setText(e.getMessage() == null ? "" : "Aperçu indisponible : " + e.getMessage());
            fitToContent();
        }
    }

    private SimulationItem build() {
        LocalDate d = Widgets.dateValue(date);
        if (d == null) {
            throw new BusinessException("Date invalide");
        }
        Integer duration = null;
        if (!months.getText().isBlank()) {
            try {
                duration = Integer.parseInt(months.getText().strip());
            } catch (NumberFormatException e) {
                throw new BusinessException("Durée invalide : nombre de mois, par exemple 48");
            }
        }
        Long id = existing == null ? null : existing.id();
        try {
            return switch (kind) {
                case ONE_TIME, MONTHLY -> {
                    BigDecimal value = requireAmount(amount, false);
                    boolean income = Boolean.TRUE.equals(Widgets.selected(direction));
                    yield new SimulationItem(id, kind, label.getText(), Money.of(income ? value : value.negate(), currency),
                            d, kind == Kind.MONTHLY ? duration : null, null, null, null);
                }
                case LOAN -> {
                    BigDecimal r = rate.getText().isBlank() ? null : AmountParser.parse(rate.getText())
                            .orElseThrow(() -> new BusinessException("Taux invalide : saisissez par exemple 4,5"));
                    Money p = payment.getText().isBlank() ? null : Money.of(requireAmount(payment, false), currency);
                    yield new SimulationItem(id, kind, label.getText().isBlank() ? "Crédit" : label.getText(),
                            Money.of(requireAmount(amount, false), currency), d, duration, r, p, null);
                }
                case STOP_RECURRING -> {
                    Choice<Long> chosen = require(rule.getValue(), "Aucune récurrence à arrêter");
                    yield new SimulationItem(id, kind, ruleLabels.get(chosen.value()), null, d, null, null, null,
                            chosen.value());
                }
            };
        } catch (IllegalArgumentException e) {
            throw new BusinessException(e.getMessage());
        }
    }

    @Override
    protected SimulationItem submit() {
        SimulationItem item = build();
        if (kind == Kind.LOAN) {
            try {
                ctx.services().loans().calculator().schedule(item.amount(), item.annualRate(), item.payment(),
                        item.months(), item.date(), null);
            } catch (IllegalArgumentException e) {
                throw new BusinessException(e.getMessage());
            }
        }
        return item;
    }
}
