package com.financeapp.desktop.ui.dialogs;

import com.financeapp.core.loan.AmortizationRow;
import com.financeapp.core.loan.AmortizationSchedule;
import com.financeapp.core.loan.Loan;
import com.financeapp.core.money.Money;
import com.financeapp.desktop.ui.common.Dialogs;
import com.financeapp.desktop.ui.common.Formats;
import com.financeapp.desktop.ui.common.UiContext;
import com.financeapp.desktop.ui.common.Widgets;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.util.function.Function;

/** Tableau d'amortissement complet d'un credit (calcule, jamais stocke). */
public final class AmortizationDialog extends Dialog<Void> {

    public AmortizationDialog(UiContext ctx, Loan loan) {
        setTitle("Tableau d'amortissement — " + loan.name());
        setHeaderText(null);
        Dialogs.style(getDialogPane(), ctx.window(), this);
        Formats f = ctx.formats();
        AmortizationSchedule s = ctx.services().loans().schedule(loan);
        LocalDate today = ctx.services().planning().today();

        TableView<AmortizationRow> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getColumns().add(column("N°", 70, r -> (r.date().isAfter(today) ? "" : "✓ ") + r.number()));
        table.getColumns().add(column("Date", 110, r -> Formats.date(r.date())));
        table.getColumns().add(money("Mensualité", f, AmortizationRow::payment));
        table.getColumns().add(money("Intérêts", f, AmortizationRow::interest));
        table.getColumns().add(money("Capital", f, AmortizationRow::principal));
        if (!loan.monthlyInsurance().isZero()) {
            table.getColumns().add(money("Assurance", f, AmortizationRow::insurance));
        }
        table.getColumns().add(money("Capital restant", f, AmortizationRow::remaining));
        table.getItems().setAll(s.rows());
        table.setRowFactory(tv -> new TableRow<>() {
            @Override
            protected void updateItem(AmortizationRow item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().remove("row-past");
                if (!empty && item != null && !item.date().isAfter(today)) {
                    getStyleClass().add("row-past");
                }
            }
        });
        table.setPrefSize(820, 480);
        s.nextAfter(today).ifPresent(next -> table.scrollTo(Math.max(0, next.number() - 3)));

        String summary = "Capital " + f.money(s.principal()) + " · taux " + Formats.percent(s.annualRate())
                + (s.rateEstimated() ? " (estimé à partir de la mensualité)" : "") + " · " + s.rows().size()
                + " mensualités · intérêts " + f.money(s.totalInterest())
                + (s.totalInsurance().isZero() ? "" : " · assurance " + f.money(s.totalInsurance()))
                + " · coût total " + f.money(s.totalCost());
        var label = Widgets.label(summary, "muted");
        label.setWrapText(true);
        var legend = Widgets.label("✓ = mensualité échue (d'après le calendrier du crédit). Les intérêts sont arrondis "
                + "au centime chaque mois ; la dernière mensualité solde l'écart d'arrondi. Votre offre de prêt fait foi.",
                "hint");
        legend.setWrapText(true);
        getDialogPane().setContent(new VBox(12, label, table, legend));
        getDialogPane().getButtonTypes().add(new ButtonType("Fermer", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE));
        setResizable(true);
    }

    private static TableColumn<AmortizationRow, String> column(String title, double width,
                                                               Function<AmortizationRow, String> value) {
        TableColumn<AmortizationRow, String> c = new TableColumn<>(title);
        c.setCellValueFactory(r -> new SimpleStringProperty(value.apply(r.getValue())));
        c.setPrefWidth(width);
        c.setSortable(false);
        return c;
    }

    private static TableColumn<AmortizationRow, String> money(String title, Formats f,
                                                              Function<AmortizationRow, Money> value) {
        TableColumn<AmortizationRow, String> c = column(title, 120, r -> f.money(value.apply(r)));
        c.setStyle("-fx-alignment: center-right;");
        return c;
    }
}
