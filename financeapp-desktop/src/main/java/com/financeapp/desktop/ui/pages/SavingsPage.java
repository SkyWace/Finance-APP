package com.financeapp.desktop.ui.pages;

import com.financeapp.core.money.Money;
import com.financeapp.core.service.SavingsService.Holding;
import com.financeapp.core.service.SavingsService.Overview;
import com.financeapp.desktop.ui.common.Dialogs;
import com.financeapp.desktop.ui.common.Formats;
import com.financeapp.desktop.ui.common.Progress;
import com.financeapp.desktop.ui.common.UiAsync;
import com.financeapp.desktop.ui.common.UiContext;
import com.financeapp.desktop.ui.common.Widgets;
import com.financeapp.desktop.ui.dialogs.AccountDialog;
import com.financeapp.desktop.ui.dialogs.SavingsAccountDialog;
import com.financeapp.desktop.ui.dialogs.ValuationDialog;
import com.financeapp.desktop.ui.dialogs.ValuationHistoryDialog;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * Epargne detenue : livrets, epargne logement, assurance-vie, PEA, PER, epargne
 * salariale... Chaque produit est un compte d'epargne dont la valeur se met a
 * jour a la main ; les plafonds des livrets reglementes sont rappeles.
 */
public final class SavingsPage extends Page {

    public SavingsPage(UiContext ctx) {
        super(ctx);
    }

    @Override
    public String title() {
        return "Épargne";
    }

    @Override
    public void refresh() {
        UiAsync.load(() -> ctx.services().savings().overview(), this::render);
    }

    private void render(Overview o) {
        Formats f = ctx.formats();
        Button add = new Button("+  Ajouter une épargne");
        add.getStyleClass().add("primary");
        add.setOnAction(e -> new SavingsAccountDialog(ctx).showAndWait().ifPresent(a -> ctx.events().fireChanged()));
        Button goals = new Button("Objectifs d'épargne →");
        goals.getStyleClass().add("ghost");
        goals.setOnAction(e -> ctx.navigator().accept("goals"));
        content.getChildren().setAll(Widgets.row(add, Widgets.spacer(), goals));

        if (o.isEmpty()) {
            content.getChildren().add(Widgets.section(null, Widgets.emptyState(
                    "Aucune épargne renseignée. Ajoutez vos livrets (Livret A, LDDS, LEP…), votre épargne logement, "
                            + "assurance-vie, PEA, PER ou épargne salariale avec leur valeur actuelle : vous suivrez "
                            + "ainsi votre patrimoine et la marge restante sous les plafonds.")));
            netWorthSection();
            return;
        }

        String share = o.shareOfNetWorth() == null ? null
                : Formats.percent(o.shareOfNetWorth()) + " de votre patrimoine (" + f.money(o.netWorth()) + ")";
        content.getChildren().add(Widgets.row(
                Widgets.kpiCard("Épargne totale", f.money(o.total()), null, share),
                Widgets.kpiCard("Épargne disponible", f.money(o.totalAvailable()), null,
                        count(o.available().size()) + " · retrait à tout moment"),
                Widgets.kpiCard("Moyen et long terme", f.money(o.totalLongTerm()), null,
                        count(o.longTerm().size()) + " · placements et épargne bloquée")));

        netWorthSection();
        section("Épargne disponible", o.available(), f);
        section("Épargne à moyen et long terme", o.longTerm(), f);

        Label note = Widgets.label("L'épargne n'est pas comptée dans le disponible réel (modifiable compte par compte). "
                + "Les plafonds affichés sont les plafonds de versements réglementaires, à titre indicatif : les intérêts "
                + "capitalisés peuvent les dépasser. Les montants en devise étrangère ne sont pas additionnés aux totaux.",
                "hint");
        note.setWrapText(true);
        content.getChildren().add(note);
    }

    /** Periode choisie pour l'evolution du patrimoine (mois ; 0 = depuis le debut). */
    private int netWorthMonths = 12;

    /** Evolution du patrimoine en fin de mois, avec le choix de la periode. */
    private void netWorthSection() {
        javafx.scene.control.ComboBox<com.financeapp.desktop.ui.common.Choice<Integer>> period =
                new javafx.scene.control.ComboBox<>();
        period.getItems().setAll(
                new com.financeapp.desktop.ui.common.Choice<>(12, "12 derniers mois"),
                new com.financeapp.desktop.ui.common.Choice<>(24, "24 derniers mois"),
                new com.financeapp.desktop.ui.common.Choice<>(60, "5 dernières années"),
                new com.financeapp.desktop.ui.common.Choice<>(0, "Depuis le début"));
        Widgets.select(period, netWorthMonths);
        VBox body = new VBox(10);
        VBox box = new VBox(10, Widgets.row(Widgets.label("Période", "form-label"), period), body);
        period.valueProperty().addListener((o, old, c) -> {
            if (c != null) {
                netWorthMonths = c.value();
                loadNetWorth(body);
            }
        });
        content.getChildren().add(Widgets.section("Évolution du patrimoine", box));
        loadNetWorth(body);
    }

    private void loadNetWorth(VBox body) {
        int months = netWorthMonths;
        UiAsync.load(() -> ctx.services().netWorth().history(months), h -> {
            Formats f = ctx.formats();
            if (h.isEmpty()) {
                body.getChildren().setAll(Widgets.emptyState("Pas encore assez d'historique : la courbe apparaîtra à "
                        + "la fin du premier mois suivi."));
                return;
            }
            var first = h.points().getFirst();
            var last = h.points().getLast();
            Label summary = Widgets.label("Aujourd'hui : " + f.money(last.total()) + " · " + f.signed(h.change())
                    + " depuis le " + Formats.date(first.date()), "op-label");
            var chart = com.financeapp.desktop.ui.common.Charts.netWorthChart(h, f);
            chart.setPrefHeight(300);
            Label legend = Widgets.label("Trait plein épais : patrimoine (tous les comptes actifs) · tirets : épargne · "
                    + "pointillés : comptes courants. Soldes en fin de mois, puis aujourd'hui ; un compte compte à "
                    + "partir de sa date d'ouverture ; les valeurs d'épargne saisies sont reprises à leur date."
                    + (h.otherCurrencies() > 0 ? " Comptes dans une autre devise non comptés : " + h.otherCurrencies() + "." : ""),
                    "hint");
            legend.setWrapText(true);
            body.getChildren().setAll(summary, chart, legend);
        });
    }

    private static String count(int n) {
        return n + (n > 1 ? " produits" : " produit");
    }

    private void section(String title, List<Holding> holdings, Formats f) {
        if (holdings.isEmpty()) {
            return;
        }
        VBox rows = new VBox(14);
        for (Holding h : holdings) {
            rows.getChildren().add(holdingRow(h, f));
        }
        content.getChildren().add(Widgets.section(title, rows));
    }

    private VBox holdingRow(Holding h, Formats f) {
        var account = h.account();
        String source = h.lastValuation() == null ? "Solde calculé d'après les opérations"
                : "Valeur au " + Formats.date(h.lastValuation().date())
                  + (h.lastValuation().date().isBefore(ctx.services().planning().today()) ? " + opérations suivantes" : "");
        Money change = h.changeSincePrevious();
        if (change != null) {
            source += " · " + (change.isZero() ? "inchangée" : f.signed(change)) + " depuis le "
                    + Formats.date(h.previousValuation().date());
        }
        VBox texts = new VBox(2, Widgets.label(account.name(), "account-name"),
                Widgets.label(account.type().label() + " · " + source, "op-detail"));
        Label value = Widgets.label(f.money(h.balance()), "kpi-value");
        HBox head = Widgets.row(texts, Widgets.spacer(), value);

        VBox box = new VBox(6, head);
        if (h.ceiling() != null) {
            double ratio = Math.min(1.0, h.ceilingPercent().doubleValue() / 100.0);
            boolean full = h.room().isZero();
            String text = Formats.percent(h.ceilingPercent()) + " du plafond (" + f.money(h.ceiling()) + ") · "
                    + (full ? "plafond atteint : plus de versement possible" : "marge de versement : " + f.money(h.room()));
            box.getChildren().add(Widgets.row(Progress.bar(ratio, full ? "progress-done" : "progress-ok"),
                    Widgets.label(text, "op-detail")));
        }
        HBox actions = Widgets.row(Widgets.spacer(),
                small(account.type().marketValued() ? "Mettre à jour la valeur…" : "Saisir le solde du relevé…",
                        () -> new ValuationDialog(ctx, account).showAndWait().ifPresent(v -> ctx.events().fireChanged())),
                small("Historique", () -> {
                    ValuationHistoryDialog d = new ValuationHistoryDialog(ctx, account);
                    d.showAndWait();
                    if (d.changed()) {
                        ctx.events().fireChanged();
                    }
                }),
                small("Modifier", () -> new AccountDialog(ctx, account).showAndWait().ifPresent(a -> ctx.events().fireChanged())));
        box.getChildren().add(actions);
        return box;
    }

    private Button small(String text, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().addAll("ghost", "compact");
        b.setOnAction(e -> {
            try {
                action.run();
            } catch (RuntimeException ex) {
                Dialogs.error(window(), ex);
            }
        });
        return b;
    }
}
