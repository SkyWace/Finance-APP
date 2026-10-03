package com.financeapp.desktop.ui.dialogs;

import com.financeapp.core.service.BusinessException;
import com.financeapp.desktop.ui.common.FormDialog;
import com.financeapp.desktop.ui.common.UiContext;
import com.financeapp.desktop.ui.common.Widgets;
import com.financeapp.infra.security.WebBackupWriter;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;

/**
 * Mot de passe du fichier destine a la version web : il protegera le profil cree dans
 * le navigateur (il peut differer du mot de passe maitre de l'application).
 */
public final class WebExportDialog extends FormDialog<char[]> {

    private final PasswordField password = new PasswordField();
    private final PasswordField confirm = new PasswordField();

    public WebExportDialog(UiContext ctx) {
        super(ctx, "Exporter pour la version web", "Choisir le fichier…");
        Label intro = Widgets.label("Crée un fichier chiffré à ouvrir sur le site FinanceApp : Paramètres → "
                + "« Importer depuis l'application desktop », puis le mot de passe choisi ici (il protégera le profil sur le site ; "
                + "il peut être différent de celui de l'application). Le profil y est ajouté, sans rien remplacer.",
                "hint");
        intro.setWrapText(true);
        intro.setMaxWidth(480);
        addFullRow(intro);
        password.setPromptText(WebBackupWriter.MIN_PASSWORD_LENGTH + " caractères minimum");
        addRow("Mot de passe", password);
        addRow("Confirmation", confirm);
        Label limits = Widgets.label("Sont repris : comptes, catégories, opérations, virements, récurrences, étiquettes, "
                + "réglages ; les soldes actuels sont identiques. Ne sont pas repris (absents de la version web) : budgets, "
                + "objectifs, crédits (leurs échéances restent en récurrences), simulations, justificatifs, historique des "
                + "valeurs d'épargne, règles de catégorisation. Une opération ventilée garde sa catégorie principale, le "
                + "détail est ajouté au commentaire. Seuls les comptes dans la devise de référence sont exportés. Le "
                + "disponible réel du site ne déduit donc pas les budgets ni les objectifs.", "hint");
        limits.setWrapText(true);
        limits.setMaxWidth(480);
        addFullRow(limits);
        setOnShown(e -> password.requestFocus());
    }

    @Override
    protected char[] submit() {
        if (password.getText().length() < WebBackupWriter.MIN_PASSWORD_LENGTH) {
            throw new BusinessException("Le mot de passe doit contenir au moins " + WebBackupWriter.MIN_PASSWORD_LENGTH
                    + " caractères.");
        }
        if (!password.getText().equals(confirm.getText())) {
            throw new BusinessException("Les deux saisies ne correspondent pas.");
        }
        char[] result = password.getText().toCharArray();
        password.clear();
        confirm.clear();
        return result;
    }
}
