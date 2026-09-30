package com.financeapp.desktop.ui.pages;

import com.financeapp.core.account.Account;
import com.financeapp.core.imports.ImportBatch;
import com.financeapp.desktop.ui.common.Dialogs;
import com.financeapp.desktop.ui.common.UiContext;
import com.financeapp.desktop.ui.common.Widgets;
import com.financeapp.desktop.ui.dialogs.ImportWizard;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Import de releves et historique des imports (annulables). */
public final class ImportsPage extends Page {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    public ImportsPage(UiContext ctx) {
        super(ctx);
    }

    @Override
    public String title() {
        return "Import de relevés";
    }

    @Override
    public void refresh() {
        List<ImportBatch> batches = ctx.services().imports().batches();
        Map<Long, String> accounts = ctx.services().accounts().findAll().stream()
                .collect(Collectors.toMap(Account::id, Account::name));

        Button start = new Button("Importer un relevé…");
        start.getStyleClass().add("primary");
        start.setOnAction(e -> new ImportWizard(ctx).showAndWait().ifPresent(b -> {
            ctx.events().fireChanged();
            if (b.created() + b.reconciled() > 0 && Dialogs.confirm(window(), "Import terminé", b.created()
                    + " nouvelle(s) opération(s), " + b.reconciled() + " opération(s) prévue(s) réalisée(s), " + b.skipped()
                    + " ligne(s) non importée(s).\n\n"
                    + "Les opérations importées attendent la confirmation de leur catégorie.", "Voir les opérations à valider")) {
                ctx.navigate("inbox");
            }
        }));
        Label formats = Widgets.label("Formats acceptés : CSV (toutes banques, assistant de correspondance des colonnes), "
                + "OFX / QFX, QIF.", "muted");

        VBox list = new VBox(2);
        for (ImportBatch b : batches) {
            String when = STAMP.format(b.importedAt().atZone(ZoneId.systemDefault()));
            VBox texts = new VBox(1, Widgets.label(b.fileName(), "op-label"),
                    Widgets.label(when + " · " + accounts.getOrDefault(b.accountId(), "?") + " · " + b.format(), "op-detail"));
            Label counts = Widgets.label(b.created() + " nouvelle(s) · " + b.reconciled() + " rapprochée(s) · "
                    + b.skipped() + " ignorée(s)", "op-detail");
            HBox row = new HBox(12, texts, Widgets.spacer(), counts);
            if (b.undone()) {
                row.getChildren().add(Widgets.badge("défait", "neutral"));
            } else {
                Button undo = new Button("Défaire cet import");
                undo.getStyleClass().addAll("ghost", "compact");
                undo.setOnAction(e -> {
                    if (Dialogs.confirm(window(), "Défaire l'import", "Supprimer les " + b.created()
                            + " nouvelle(s) opération(s) de cet import (y compris si vous les avez modifiées depuis) et remettre "
                            + b.reconciled() + " opération(s) rapprochée(s) à l'état « prévu » ?", "Défaire l'import")) {
                        try {
                            ctx.services().imports().undo(b.id());
                            ctx.events().fireChanged();
                        } catch (RuntimeException ex) {
                            Dialogs.error(window(), ex);
                        }
                    }
                });
                row.getChildren().add(undo);
            }
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("op-row");
            list.getChildren().add(row);
        }
        if (batches.isEmpty()) {
            list.getChildren().add(Widgets.emptyState("Aucun import pour l'instant."));
        }
        content.getChildren().setAll(Widgets.row(start), formats, Widgets.section("Historique des imports", list));
    }
}
