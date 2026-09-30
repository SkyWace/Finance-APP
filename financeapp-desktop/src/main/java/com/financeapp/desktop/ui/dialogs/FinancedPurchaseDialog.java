package com.financeapp.desktop.ui.dialogs;

import com.financeapp.core.loan.AmortizationSchedule;
import com.financeapp.core.money.Money;
import com.financeapp.core.service.BusinessException;
import com.financeapp.core.simulation.SimulationItem;
import com.financeapp.core.simulation.SimulationItem.Kind;
import com.financeapp.desktop.ui.common.AmountParser;
import com.financeapp.desktop.ui.common.FormDialog;
import com.financeapp.desktop.ui.common.Formats;
import com.financeapp.desktop.ui.common.UiContext;
import com.financeapp.desktop.ui.common.Widgets;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;

/**
 * Modele "achat finance a credit" (ex. voiture) : prix, apport, credit et frais
 * mensuels induits. Produit les hypotheses correspondantes : l'apport (ponctuel),
 * le credit (capital = prix - apport) et chaque frais mensuel.
 */
public final class FinancedPurchaseDialog extends FormDialog<List<SimulationItem>> {

    private final Currency currency;
    private final TextField name = new TextField("Voiture");
    private final TextField price = new TextField();
    private final TextField downPayment = new TextField();
    private final DatePicker purchaseDate;
    private final TextField months = new TextField("48");
    private final TextField rate = new TextField();
    private final TextField payment = new TextField();
    private final TextField insurance = new TextField();
    private final TextField fuel = new TextField();
    private final TextField maintenance = new TextField();
    private final Label preview = Widgets.label("", "hint");

    public FinancedPurchaseDialog(UiContext ctx) {
        super(ctx, "Achat financé à crédit", "Ajouter à la simulation");
        currency = ctx.services().settings().baseCurrency();
        purchaseDate = Widgets.datePicker(ctx.services().planning().today().plusMonths(1).withDayOfMonth(1));
        price.setPromptText("Ex. 15000");
        downPayment.setPromptText("Ex. 4000 — payé comptant");
        rate.setPromptText("Ex. 4,5");
        payment.setPromptText("Ou la mensualité proposée, ex. 250");
        insurance.setPromptText("Facultatif, par mois");
        fuel.setPromptText("Facultatif, par mois");
        maintenance.setPromptText("Facultatif, par mois");

        addRow("Achat", name);
        addRow("Prix", price);
        addRow("Apport", downPayment);
        addRow("Date d'achat", purchaseDate);
        addRow("Durée du crédit (mois)", months);
        addRow("Taux annuel (%)", rate);
        addRow("ou mensualité", payment);
        addRow("Assurance", insurance);
        addRow("Carburant / énergie", fuel);
        addRow("Entretien estimé", maintenance);
        preview.setWrapText(true);
        preview.setMaxWidth(480);
        addFullRow(preview);
        Label hint = Widgets.label("Le crédit est supposé versé directement au vendeur : seuls l'apport, les mensualités "
                + "(dès le mois suivant l'achat) et les frais sortent de vos comptes.", "hint");
        hint.setWrapText(true);
        hint.setMaxWidth(480);
        addFullRow(hint);
        for (TextField f : new TextField[]{price, downPayment, months, rate, payment}) {
            f.textProperty().addListener((o, a, b) -> updatePreview());
        }
        updatePreview();
        setOnShown(e -> price.requestFocus());
    }

    private void updatePreview() {
        if (price.getText().isBlank()) {
            preview.setText("Saisissez le prix, l'apport et le taux (ou la mensualité) pour voir le crédit correspondant.");
            fitToContent();
            return;
        }
        try {
            SimulationItem loan = build().stream().filter(i -> i.kind() == Kind.LOAN).findFirst().orElse(null);
            if (loan == null) {
                preview.setText("Achat payé comptant : pas de crédit.");
                fitToContent();
                return;
            }
            AmortizationSchedule s = ctx.services().loans().calculator().schedule(loan.amount(), loan.annualRate(),
                    loan.payment(), loan.months(), loan.date(), null);
            preview.setText("Crédit de " + ctx.formats().money(loan.amount()) + " : " + ctx.formats().money(s.payment())
                    + " par mois pendant " + s.rows().size() + " mois · taux " + Formats.percent(s.annualRate())
                    + (s.rateEstimated() ? " (estimé)" : "") + " · intérêts " + ctx.formats().money(s.totalInterest()));
            fitToContent();
        } catch (RuntimeException e) {
            preview.setText(e.getMessage() == null ? "" : e.getMessage());
            fitToContent();
        }
    }

    private List<SimulationItem> build() {
        String what = name.getText().isBlank() ? "Achat" : name.getText().strip();
        BigDecimal total = requireAmount(price, false);
        BigDecimal down = downPayment.getText().isBlank() ? BigDecimal.ZERO : requireAmount(downPayment, true);
        if (down.signum() < 0 || down.compareTo(total) > 0) {
            throw new BusinessException("L'apport doit être compris entre 0 et le prix");
        }
        LocalDate date = require(Widgets.dateValue(purchaseDate), "Date d'achat invalide");
        List<SimulationItem> items = new ArrayList<>();
        if (down.signum() > 0) {
            items.add(new SimulationItem(null, Kind.ONE_TIME, "Apport — " + what, Money.of(down.negate(), currency),
                    date, null, null, null, null));
        }
        BigDecimal borrowed = total.subtract(down);
        if (borrowed.signum() > 0) {
            int term;
            try {
                term = Integer.parseInt(months.getText().strip());
            } catch (NumberFormatException e) {
                throw new BusinessException("Durée du crédit invalide");
            }
            BigDecimal r = rate.getText().isBlank() ? null : AmountParser.parse(rate.getText())
                    .orElseThrow(() -> new BusinessException("Taux invalide"));
            Money p = payment.getText().isBlank() ? null : Money.of(requireAmount(payment, false), currency);
            if (r == null && p == null) {
                throw new BusinessException("Indiquez le taux ou la mensualité du crédit");
            }
            items.add(new SimulationItem(null, Kind.LOAN, "Crédit — " + what, Money.of(borrowed, currency),
                    date.plusMonths(1), term, r, p, null));
        }
        addMonthly(items, insurance, "Assurance — " + what, date);
        addMonthly(items, fuel, "Carburant — " + what, date);
        addMonthly(items, maintenance, "Entretien — " + what, date);
        return items;
    }

    private void addMonthly(List<SimulationItem> items, TextField field, String label, LocalDate date) {
        if (!field.getText().isBlank()) {
            items.add(new SimulationItem(null, Kind.MONTHLY, label, Money.of(requireAmount(field, false).negate(), currency),
                    date.plusMonths(1), null, null, null, null));
        }
    }

    @Override
    protected List<SimulationItem> submit() {
        List<SimulationItem> items = build();
        items.stream().filter(i -> i.kind() == Kind.LOAN).forEach(l -> {
            try {
                ctx.services().loans().calculator().schedule(l.amount(), l.annualRate(), l.payment(), l.months(),
                        l.date(), null);
            } catch (IllegalArgumentException e) {
                throw new BusinessException(e.getMessage());
            }
        });
        return items;
    }
}
