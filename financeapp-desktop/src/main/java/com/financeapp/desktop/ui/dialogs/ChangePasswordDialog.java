package com.financeapp.desktop.ui.dialogs;

import com.financeapp.core.service.BusinessException;
import com.financeapp.desktop.ui.common.FormDialog;
import com.financeapp.desktop.ui.common.UiContext;
import com.financeapp.desktop.ui.common.Widgets;
import com.financeapp.infra.security.InvalidSecretException;
import com.financeapp.infra.security.VaultService;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;

import java.io.IOException;
import java.util.Arrays;

/**
 * Changement du mot de passe maitre. La base n'est pas rechiffree : seule
 * l'enveloppe de sa cle change ; les sauvegardes restent lisibles.
 */
public final class ChangePasswordDialog extends FormDialog<Boolean> {

    private final PasswordField current = new PasswordField();
    private final PasswordField fresh = new PasswordField();
    private final PasswordField confirm = new PasswordField();

    public ChangePasswordDialog(UiContext ctx) {
        super(ctx, "Changer le mot de passe maître", "Changer");
        fresh.setPromptText(VaultService.MIN_PASSWORD_LENGTH + " caractères minimum");
        addRow("Mot de passe actuel", current);
        addRow("Nouveau mot de passe", fresh);
        addRow("Confirmation", confirm);
        Label hint = Widgets.label("Vos données et vos sauvegardes restent accessibles : seule la protection de leur clé change. "
                + "Votre clé de récupération reste valable.", "hint");
        hint.setWrapText(true);
        hint.setMaxWidth(480);
        addFullRow(hint);
        setOnShown(e -> current.requestFocus());
    }

    @Override
    protected Boolean submit() {
        if (!fresh.getText().equals(confirm.getText())) {
            throw new BusinessException("Les deux saisies du nouveau mot de passe ne correspondent pas.");
        }
        char[] oldPassword = current.getText().toCharArray();
        char[] newPassword = fresh.getText().toCharArray();
        try {
            ctx.security().vault().changePassword(oldPassword, newPassword);
            return true;
        } catch (InvalidSecretException e) {
            throw new BusinessException("Mot de passe actuel incorrect.");
        } catch (IOException e) {
            throw new IllegalStateException("Trousseau illisible", e);
        } finally {
            Arrays.fill(oldPassword, '\0');
            Arrays.fill(newPassword, '\0');
            current.clear();
            fresh.clear();
            confirm.clear();
        }
    }
}
