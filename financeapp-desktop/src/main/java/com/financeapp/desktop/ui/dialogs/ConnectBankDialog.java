package com.financeapp.desktop.ui.dialogs;

import com.financeapp.core.banksync.BankConnection;
import com.financeapp.core.banksync.BankInfo;
import com.financeapp.core.service.BusinessException;
import com.financeapp.desktop.ui.common.Browser;
import com.financeapp.desktop.ui.common.Choice;
import com.financeapp.desktop.ui.common.Dialogs;
import com.financeapp.desktop.ui.common.FormDialog;
import com.financeapp.desktop.ui.common.UiAsync;
import com.financeapp.desktop.ui.common.UiContext;
import com.financeapp.desktop.ui.common.Widgets;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * Connexion d'une banque : choix de la banque, ouverture de sa page
 * d'autorisation dans le navigateur systeme (authentification forte chez la
 * banque), puis collage de l'adresse de retour. Aucun identifiant bancaire
 * n'est saisi dans FinanceApp.
 */
public final class ConnectBankDialog extends FormDialog<BankConnection> {

    private static final List<Choice<String>> COUNTRIES = List.of(
            new Choice<>("FR", "France"), new Choice<>("BE", "Belgique"), new Choice<>("LU", "Luxembourg"),
            new Choice<>("DE", "Allemagne"), new Choice<>("ES", "Espagne"), new Choice<>("IT", "Italie"),
            new Choice<>("NL", "Pays-Bas"), new Choice<>("PT", "Portugal"), new Choice<>("AT", "Autriche"),
            new Choice<>("FI", "Finlande"), new Choice<>("SE", "Suède"), new Choice<>("DK", "Danemark"),
            new Choice<>("NO", "Norvège"), new Choice<>("IE", "Irlande"));

    private final ComboBox<Choice<String>> country = new ComboBox<>();
    private final TextField filter = new TextField();
    private final ObservableList<BankInfo> banks = FXCollections.observableArrayList();
    private final FilteredList<BankInfo> filtered = new FilteredList<>(banks);
    private final ListView<BankInfo> list = new ListView<>(filtered);
    private final Label status = Widgets.label("", "op-detail");
    private final TextField authUrl = new TextField();
    private final TextField returned = new TextField();
    private final VBox step2;
    private boolean started;

    public ConnectBankDialog(UiContext ctx) {
        super(ctx, "Connecter une banque", "Terminer la connexion");
        country.getItems().setAll(COUNTRIES);
        country.getSelectionModel().selectFirst();
        country.setMaxWidth(Double.MAX_VALUE);
        country.setOnAction(e -> loadBanks());
        filter.setPromptText("Rechercher une banque…");
        filter.textProperty().addListener((o, a, text) -> filtered.setPredicate(b -> text == null || text.isBlank()
                || b.name().toLowerCase(java.util.Locale.ROOT).contains(text.strip().toLowerCase(java.util.Locale.ROOT))));
        list.setPrefHeight(190);
        list.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(BankInfo item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name() + (item.beta() ? "  (bêta)" : ""));
            }
        });

        Button open = new Button("Ouvrir la page de ma banque");
        open.getStyleClass().add("primary");
        open.setOnAction(e -> start());

        authUrl.setEditable(false);
        javafx.scene.layout.HBox.setHgrow(authUrl, javafx.scene.layout.Priority.ALWAYS);
        Button copy = new Button("Copier");
        copy.getStyleClass().addAll("ghost", "compact");
        copy.setOnAction(e -> Browser.copy(authUrl.getText()));
        returned.setPromptText("https://localhost/financeapp?code=…&state=…");
        Label howTo = Widgets.label("Après avoir donné votre accord sur le site de votre banque, le navigateur affiche "
                + "une page d'erreur ou une page vide à l'adresse de retour : c'est normal. Copiez l'adresse complète "
                + "(barre d'adresse) et collez-la ci-dessous.", "hint");
        howTo.setWrapText(true);
        howTo.setMaxWidth(480);
        howTo.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        step2 = new VBox(8, Widgets.label("Page d'autorisation (si le navigateur ne s'est pas ouvert, copiez-la)", "form-label"),
                Widgets.row(authUrl, copy), howTo, Widgets.label("Adresse de retour", "form-label"), returned);
        step2.setVisible(false);
        step2.managedProperty().bind(step2.visibleProperty());

        addRow("Pays", country);
        addRow("Banque", new VBox(6, filter, list, status));
        addFullRow(Widgets.row(open));
        addFullRow(step2);
        loadBanks();
    }

    private void loadBanks() {
        banks.clear();
        status.setText("Chargement de la liste des banques…");
        String code = country.getValue().value();
        UiAsync.load(() -> ctx.services().bankSync().banks(code), result -> {
            banks.setAll(result);
            status.setText(result.isEmpty() ? "Aucune banque disponible pour ce pays."
                    : result.size() + " banque(s) — choisissez la vôtre");
        }, ex -> status.setText(ex.getMessage()));
    }

    private void start() {
        BankInfo bank = list.getSelectionModel().getSelectedItem();
        if (bank == null) {
            status.setText("Choisissez d'abord votre banque dans la liste.");
            return;
        }
        try {
            String url = ctx.services().bankSync().startLink(bank);
            authUrl.setText(url);
            started = true;
            step2.setVisible(true);
            getDialogPane().getScene().getWindow().sizeToScene();
            if (!Browser.open(url)) {
                status.setText("Le navigateur n'a pas pu être ouvert : copiez l'adresse ci-dessous.");
            } else {
                status.setText("Page de « " + bank.name() + " » ouverte dans votre navigateur.");
            }
            returned.requestFocus();
        } catch (RuntimeException ex) {
            Dialogs.error(getDialogPane().getScene().getWindow(), ex);
        }
    }

    @Override
    protected BankConnection submit() {
        if (!started) {
            throw new BusinessException("Choisissez votre banque puis ouvrez sa page d'autorisation");
        }
        return ctx.services().bankSync().completeLink(returned.getText());
    }
}
