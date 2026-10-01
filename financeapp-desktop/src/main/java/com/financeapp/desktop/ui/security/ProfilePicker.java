package com.financeapp.desktop.ui.security;

import com.financeapp.desktop.ui.common.Widgets;
import com.financeapp.infra.storage.Profile;
import com.financeapp.infra.storage.ProfileRegistry;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Consumer;

/**
 * Choix de l'utilisateur au demarrage : chaque personne a son propre profil
 * (donnees et mot de passe maitre distincts). Seuls les noms sont visibles ici.
 */
public final class ProfilePicker {

    private final ProfileRegistry registry;
    private final String appName;
    private final Consumer<Profile> onChosen;
    private final StackPane root = new StackPane();

    public ProfilePicker(ProfileRegistry registry, String appName, Consumer<Profile> onChosen) {
        this.registry = registry;
        this.appName = appName;
        this.onChosen = onChosen;
        root.getStyleClass().add("lock-root");
    }

    public Parent root() {
        return root;
    }

    public ProfilePicker show() {
        List<Profile> profiles = registry.list();
        if (profiles.isEmpty()) {
            showCreate(true);
            return this;
        }
        String last = registry.lastUsed().map(Profile::id).orElse(null);
        VBox list = new VBox(8);
        for (Profile p : profiles) {
            Button b = new Button(p.name());
            b.getStyleClass().addAll("profile-button", p.id().equals(last) ? "primary" : "secondary");
            b.setMaxWidth(Double.MAX_VALUE);
            b.setOnAction(e -> onChosen.accept(p));
            if (p.id().equals(last)) {
                b.setDefaultButton(true);
            }
            list.getChildren().add(b);
        }
        Button add = link("+  Ajouter un utilisateur", () -> showCreate(false));
        show(card(brand(), Widgets.label("Qui utilise " + appName + " ?", "lock-title"),
                text("Chaque utilisateur a ses propres comptes et son propre mot de passe maître."), list, add));
        return this;
    }

    private void showCreate(boolean first) {
        TextField name = new TextField();
        name.setPromptText("Prénom ou nom du profil");
        Label error = Widgets.label("", "form-error");
        error.setWrapText(true);
        Button create = new Button("Continuer");
        create.getStyleClass().add("primary");
        create.setDefaultButton(true);
        create.setMaxWidth(Double.MAX_VALUE);
        create.setOnAction(e -> {
            try {
                onChosen.accept(registry.create(name.getText()));
            } catch (IllegalArgumentException ex) {
                error.setText(ex.getMessage());
            } catch (RuntimeException ex) {
                error.setText("Création impossible : " + ex.getMessage());
            }
        });
        VBox card = card(brand(),
                Widgets.label(first ? "Bienvenue" : "Nouvel utilisateur", "lock-title"),
                text(first
                        ? "Chaque personne qui utilise " + appName + " sur cet ordinateur a son propre profil, protégé "
                          + "par son propre mot de passe maître. Commencez par le vôtre."
                        : "Le nouvel utilisateur choisira ensuite son propre mot de passe maître. Il ne verra pas vos "
                          + "données, et vous ne verrez pas les siennes."),
                name, error, create);
        if (!first) {
            card.getChildren().add(link("← Retour", this::show));
        }
        show(card);
        Platform.runLater(name::requestFocus);
    }

    private void show(Node card) {
        root.getChildren().setAll(card);
        StackPane.setAlignment(card, Pos.CENTER);
    }

    private VBox card(Node... children) {
        VBox box = new VBox(14, children);
        box.getStyleClass().add("lock-card");
        box.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        return box;
    }

    private Label brand() {
        return Widgets.label(appName, "lock-brand");
    }

    private static Label text(String s) {
        Label l = Widgets.label(s, "lock-text");
        l.setWrapText(true);
        return l;
    }

    private static Button link(String text, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().add("link");
        b.setOnAction(e -> action.run());
        return b;
    }
}
