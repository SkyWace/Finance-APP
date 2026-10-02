package com.financeapp.desktop.ui.common;

import com.financeapp.core.service.TagService;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Saisie libre d'etiquettes separees par des virgules ("vacances, travaux"), avec les
 * etiquettes existantes proposees en un clic. Les nouvelles sont creees a l'enregistrement.
 */
public final class TagField {

    private final TagService tags;
    private final TextField field = new TextField();
    private final FlowPane suggestions = new FlowPane(6, 6);
    private final VBox node = new VBox(6, field, suggestions);

    public TagField(TagService tags) {
        this.tags = tags;
        field.setPromptText("Ex. vacances 2026, remboursable");
        field.textProperty().addListener((o, a, b) -> refreshSuggestions());
        refreshSuggestions();
    }

    public Node node() {
        return node;
    }

    /** Affiche les etiquettes donnees (par identifiant), triees par nom. */
    public void setTagIds(Collection<Long> ids) {
        Map<Long, String> names = tags.names();
        field.setText(String.join(", ", ids.stream().map(names::get).filter(Objects::nonNull)
                .sorted(String.CASE_INSENSITIVE_ORDER).toList()));
    }

    public void clear() {
        field.clear();
    }

    /** Identifiants des etiquettes saisies ; celles qui n'existent pas encore sont creees. */
    public Set<Long> resolve() {
        return tags.resolve(typed());
    }

    private List<String> typed() {
        String text = field.getText() == null ? "" : field.getText();
        return Arrays.stream(text.split(",")).map(String::strip).filter(t -> !t.isEmpty()).toList();
    }

    /** Etiquettes existantes pas encore saisies, cliquables pour les ajouter. */
    private void refreshSuggestions() {
        Set<String> typed = new HashSet<>(typed().stream().map(t -> t.toLowerCase(Locale.ROOT)).toList());
        suggestions.getChildren().clear();
        tags.findAll().stream()
                .filter(t -> !typed.contains(t.name().toLowerCase(Locale.ROOT)))
                .limit(10)
                .forEach(t -> {
                    Button b = new Button("+ " + t.name());
                    b.getStyleClass().addAll("ghost", "compact", "tag-suggestion");
                    b.setOnAction(e -> {
                        String text = field.getText() == null ? "" : field.getText().strip();
                        field.setText(text.isEmpty() || text.endsWith(",") ? text + (text.isEmpty() ? "" : " ") + t.name()
                                : text + ", " + t.name());
                    });
                    suggestions.getChildren().add(b);
                });
        suggestions.setVisible(!suggestions.getChildren().isEmpty());
        suggestions.setManaged(suggestions.isVisible());
    }
}
