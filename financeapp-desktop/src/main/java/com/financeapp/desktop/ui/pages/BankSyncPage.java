package com.financeapp.desktop.ui.pages;

import com.financeapp.core.account.Account;
import com.financeapp.core.banksync.BankAccountLink;
import com.financeapp.core.imports.ImportCandidate;
import com.financeapp.core.service.BankSyncService;
import com.financeapp.desktop.ui.common.Choice;
import com.financeapp.desktop.ui.common.Dialogs;
import com.financeapp.desktop.ui.common.Formats;
import com.financeapp.desktop.ui.common.UiAsync;
import com.financeapp.desktop.ui.common.UiContext;
import com.financeapp.desktop.ui.common.Widgets;
import com.financeapp.desktop.ui.dialogs.BankSyncSetupDialog;
import com.financeapp.desktop.ui.dialogs.ConnectBankDialog;
import com.financeapp.desktop.ui.dialogs.ImportWizard;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Synchronisation bancaire (prototype, facultative, desactivee par defaut) :
 * activation, connexions aux banques (consentements), association des comptes
 * et recuperation des operations, verifiees comme un import de fichier.
 */
public final class BankSyncPage extends Page {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    public BankSyncPage(UiContext ctx) {
        super(ctx);
    }

    @Override
    public String title() {
        return "Synchronisation bancaire";
    }

    @Override
    public void refresh() {
        Label prototype = Widgets.label("Prototype — lecture seule via " + ctx.services().bankSync().providerName()
                + ", agrégateur utilisé avec votre propre compte. L'import de fichiers reste disponible et suffit pour "
                + "utiliser FinanceApp.", "sim-banner");
        prototype.setWrapText(true);
        prototype.setMaxWidth(Double.MAX_VALUE);
        if (!ctx.services().bankSync().isEnabled()) {
            renderDisabled(prototype);
        } else {
            renderEnabled(prototype);
        }
    }

    private void renderDisabled(Label prototype) {
        Button enable = new Button("Activer la synchronisation…");
        enable.getStyleClass().add("primary");
        enable.setOnAction(e -> new BankSyncSetupDialog(ctx).showAndWait().ifPresent(ok -> ctx.events().fireChanged()));
        VBox facts = new VBox(6,
                fact("Vos identifiants bancaires ne sont jamais demandés ni stockés : vous vous authentifiez sur le site de votre banque."),
                fact("Les opérations vont de votre banque à " + ctx.services().bankSync().providerName()
                        + " puis directement à cet ordinateur. Aucun serveur FinanceApp."),
                fact("Lecture seule : aucun virement ni paiement n'est possible."),
                fact("Chaque récupération passe par l'aperçu habituel : doublons décochés, rapprochements, « À valider »."),
                fact("Consentement renouvelable au plus tous les " + BankSyncService.MAX_CONSENT_DAYS + " jours ; au plus "
                        + BankSyncService.MAX_FETCHES_PER_DAY + " consultations par jour et par compte (réglementation DSP2)."),
                fact("Prérequis : un compte gratuit sur enablebanking.com (mode restreint, usage personnel)."));
        content.getChildren().setAll(prototype, Widgets.section("Synchronisation désactivée", facts, Widgets.row(enable)));
    }

    private static Label fact(String text) {
        Label l = Widgets.label("•  " + text, "op-label");
        l.setWrapText(true);
        return l;
    }

    private void renderEnabled(Label prototype) {
        Button connect = new Button("+  Connecter une banque…");
        connect.getStyleClass().add("primary");
        connect.setOnAction(e -> new ConnectBankDialog(ctx).showAndWait().ifPresent(c -> ctx.events().fireChanged()));
        Button settings = small("Paramètres…", () -> new BankSyncSetupDialog(ctx).showAndWait()
                .ifPresent(ok -> ctx.events().fireChanged()));
        Button disable = small("Désactiver et tout effacer", () -> {
            if (Dialogs.confirm(window(), "Désactiver la synchronisation", "Révoquer les consentements et effacer "
                    + "l'identifiant d'application, la clé privée et les connexions de cet ordinateur ? Les opérations "
                    + "déjà importées sont conservées.", "Désactiver")) {
                ctx.services().bankSync().disable();
                ctx.events().fireChanged();
            }
        });
        String appId = ctx.services().bankSync().credentials().map(c -> c.applicationId()).orElse("");
        Label provider = Widgets.label("Application " + (appId.length() > 8 ? appId.substring(0, 8) + "…" : appId)
                + " · retour : " + ctx.services().bankSync().credentials().map(c -> c.redirectUrl()).orElse(""), "muted");
        content.getChildren().setAll(prototype, Widgets.row(connect, Widgets.spacer(), settings, disable), provider);

        List<BankSyncService.ConnectionView> connections = ctx.services().bankSync().overview();
        if (connections.isEmpty()) {
            content.getChildren().add(Widgets.section(null, Widgets.emptyState("Aucune banque connectée. « Connecter "
                    + "une banque » ouvre la page de votre banque dans le navigateur ; vous y donnez votre accord, puis "
                    + "vous collez ici l'adresse de retour.")));
            return;
        }
        List<Account> accounts = ctx.services().accounts().findActive();
        for (BankSyncService.ConnectionView view : connections) {
            content.getChildren().add(connectionCard(view, accounts));
        }
    }

    private VBox connectionCard(BankSyncService.ConnectionView view, List<Account> accounts) {
        var c = view.connection();
        String until = Formats.date(c.validUntil().atZone(ZoneId.systemDefault()).toLocalDate());
        Label consent = view.expired()
                ? Widgets.badge("✕  Consentement expiré le " + until + " — reconnectez la banque", "danger-small")
                : view.daysLeft() <= 14
                ? Widgets.badge("!  Consentement valable jusqu'au " + until + " (" + view.daysLeft() + " j)", "warning")
                : Widgets.badge("✓  Consentement valable jusqu'au " + until, "success");
        HBox title = Widgets.row(Widgets.label(c.bankName(), "account-name"), Widgets.label(c.country(), "muted"),
                Widgets.spacer(), consent, small("Déconnecter", () -> {
                    if (Dialogs.confirm(window(), "Déconnecter la banque", "Révoquer le consentement donné à "
                            + c.bankName() + " ? Les opérations déjà importées sont conservées.", "Déconnecter")) {
                        ctx.services().bankSync().disconnect(c.id());
                        ctx.events().fireChanged();
                    }
                }));
        VBox rows = new VBox(2);
        for (BankAccountLink link : view.accounts()) {
            rows.getChildren().add(accountRow(link, accounts, view.expired()));
        }
        VBox card = new VBox(10, title, rows);
        card.getStyleClass().add("card");
        return card;
    }

    private HBox accountRow(BankAccountLink link, List<Account> accounts, boolean expired) {
        Label detail = Widgets.label((link.maskedIban() == null ? "" : link.maskedIban() + " · ") + lastSync(link), "op-detail");
        Label quota = Widgets.label(ctx.services().bankSync().fetchesLeftToday(link.id())
                + " consultation(s) restante(s) sur 24 h", "op-detail");
        detail.setWrapText(true);
        VBox texts = new VBox(1, Widgets.label(link.name(), "op-label"), detail, quota);
        texts.setMinWidth(0);
        HBox.setHgrow(texts, javafx.scene.layout.Priority.ALWAYS);
        ComboBox<Choice<Long>> target = new ComboBox<>();
        target.getItems().add(new Choice<>(null, "— Ne pas synchroniser —"));
        for (Account a : accounts) {
            if (link.currency() == null || link.currency().equals(a.currency().getCurrencyCode())) {
                target.getItems().add(new Choice<>(a.id(), "→ " + a.name()));
            }
        }
        Widgets.select(target, link.localAccountId());
        target.setPrefWidth(220);
        target.setOnAction(e -> {
            Long chosen = Widgets.selected(target);
            if (!java.util.Objects.equals(chosen, link.localAccountId())) {
                try {
                    ctx.services().bankSync().assign(link.id(), chosen);
                    ctx.events().fireChanged();
                } catch (RuntimeException ex) {
                    Dialogs.error(window(), ex);
                    Widgets.select(target, link.localAccountId());
                }
            }
        });
        Button sync = new Button("Synchroniser");
        sync.getStyleClass().addAll("secondary", "compact");
        sync.setDisable(expired || link.localAccountId() == null);
        sync.setOnAction(e -> synchronize(link, sync));
        Button again = small("Tout reprendre", () -> {
            ctx.services().bankSync().resync(link.id());
            ctx.events().fireChanged();
        });
        again.setDisable(link.syncedUntil() == null);
        again.setTooltip(new javafx.scene.control.Tooltip("Récupérer à nouveau les " + BankSyncService.FIRST_SYNC_DAYS
                + " derniers jours (les opérations déjà présentes seront reconnues)"));
        for (javafx.scene.control.Control c : List.of(target, sync, again)) {
            c.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        }
        HBox row = new HBox(12, texts, target, sync, again);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("op-row");
        return row;
    }

    private static String lastSync(BankAccountLink link) {
        String last = link.lastSyncAt() == null ? "jamais synchronisé"
                : "dernière synchronisation le " + STAMP.format(link.lastSyncAt().atZone(ZoneId.systemDefault()))
                + (link.syncedUntil() == null ? "" : " (opérations jusqu'au " + Formats.shortDate(link.syncedUntil()) + ")");
        return last;
    }

    private void synchronize(BankAccountLink link, Button button) {
        button.setDisable(true);
        button.setText("Récupération…");
        UiAsync.load(() -> ctx.services().bankSync().fetch(link.id()), batch -> {
            button.setText("Synchroniser");
            button.setDisable(false);
            String skipped = (batch.pendingSkipped() > 0 ? "\n" + batch.pendingSkipped()
                    + " opération(s) en attente seront proposées une fois comptabilisées par la banque." : "")
                    + (batch.otherCurrencySkipped() > 0 ? "\n" + batch.otherCurrencySkipped()
                    + " opération(s) dans une autre devise ignorée(s)." : "");
            boolean nothingNew = batch.candidates().stream().allMatch(c -> c.kind() == ImportCandidate.Kind.DUPLICATE);
            if (nothingNew) {
                ctx.services().bankSync().markSynced(batch);
                Dialogs.info(window(), "Synchronisation", (batch.rows().isEmpty() ? "Aucune opération comptabilisée"
                        : "Les " + batch.rows().size() + " opération(s) récupérée(s) sont déjà présentes")
                        + " depuis le " + Formats.date(batch.from()) + " : « " + link.name() + " » est à jour." + skipped);
                ctx.events().fireChanged();
                return;
            }
            ImportWizard.forBankSync(ctx, batch).showAndWait().ifPresent(b -> {
                ctx.events().fireChanged();
                if (Dialogs.confirm(window(), "Synchronisation terminée", b.created() + " nouvelle(s) opération(s), "
                        + b.reconciled() + " opération(s) prévue(s) réalisée(s), " + b.skipped()
                        + " ligne(s) non importée(s)." + skipped
                        + "\n\nLes opérations importées attendent la confirmation de leur catégorie.",
                        "Voir les opérations à valider")) {
                    ctx.navigate("inbox");
                }
            });
        }, ex -> {
            button.setText("Synchroniser");
            button.setDisable(false);
            Dialogs.error(window(), ex);
            ctx.events().fireChanged();
        });
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
