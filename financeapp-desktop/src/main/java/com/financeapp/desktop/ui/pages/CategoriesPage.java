package com.financeapp.desktop.ui.pages;

import com.financeapp.core.category.Category;
import com.financeapp.core.category.CategoryKind;
import com.financeapp.desktop.ui.common.Dialogs;
import com.financeapp.desktop.ui.common.UiContext;
import com.financeapp.desktop.ui.common.Widgets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** Gestion des categories et sous-categories (archivage plutot que suppression). */
public final class CategoriesPage extends Page {

    private final TreeView<Category> tree = new TreeView<>();
    private final CheckBox showArchived = new CheckBox("Afficher les catégories archivées");
    private final Button addChild = button("+  Sous-catégorie", "secondary", this::addChild);
    private final Button rename = button("Renommer", "ghost", this::rename);
    private final Button archive = button("Archiver", "ghost", this::toggleArchive);
    private final Button delete = button("Supprimer", "ghost", this::delete);

    public CategoriesPage(UiContext ctx) {
        super(ctx);
        tree.setShowRoot(false);
        tree.setCellFactory(tv -> new TreeCell<>() {
            @Override
            protected void updateItem(Category c, boolean empty) {
                super.updateItem(c, empty);
                getStyleClass().remove("archived");
                if (empty || c == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                setText(c.name());
                setGraphic(c.isRoot() ? Widgets.badge(c.kind().label(), c.kind() == CategoryKind.INCOME ? "success" : "neutral")
                        : null);
                if (c.archived()) {
                    getStyleClass().add("archived");
                    setText(c.name() + "  (archivée)");
                }
            }
        });
        tree.getSelectionModel().selectedItemProperty().addListener((o, old, item) -> updateButtons());
        showArchived.setOnAction(e -> refresh());
        Button addRoot = button("+  Catégorie", "primary", this::addRoot);
        VBox.setVgrow(tree, Priority.ALWAYS);
        content.getChildren().setAll(
                Widgets.row(addRoot, addChild, rename, archive, delete, Widgets.spacer(), showArchived),
                tree,
                Widgets.label("Une catégorie utilisée ne peut pas être supprimée : archivez-la, l'historique reste intact.", "muted"));
    }

    @Override
    public Node view() {
        return content;
    }

    @Override
    public String title() {
        return "Catégories";
    }

    @Override
    public void refresh() {
        Long selectedId = selected().map(Category::id).orElse(null);
        List<Category> all = ctx.services().categories().findAll().stream()
                .filter(c -> showArchived.isSelected() || !c.archived())
                .sorted(Comparator.comparingInt(Category::sortOrder).thenComparing(Category::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        Map<Long, List<Category>> children = all.stream().filter(c -> !c.isRoot())
                .collect(Collectors.groupingBy(Category::parentId));
        TreeItem<Category> root = new TreeItem<>();
        TreeItem<Category> toSelect = null;
        for (Category c : all) {
            if (!c.isRoot()) {
                continue;
            }
            TreeItem<Category> item = new TreeItem<>(c);
            item.setExpanded(true);
            if (c.id().equals(selectedId)) {
                toSelect = item;
            }
            for (Category child : children.getOrDefault(c.id(), List.of())) {
                TreeItem<Category> childItem = new TreeItem<>(child);
                item.getChildren().add(childItem);
                if (child.id().equals(selectedId)) {
                    toSelect = childItem;
                }
            }
            root.getChildren().add(item);
        }
        tree.setRoot(root);
        if (toSelect != null) {
            tree.getSelectionModel().select(toSelect);
        }
        updateButtons();
    }

    private Optional<Category> selected() {
        TreeItem<Category> item = tree.getSelectionModel().getSelectedItem();
        return item == null ? Optional.empty() : Optional.ofNullable(item.getValue());
    }

    private void updateButtons() {
        Optional<Category> c = selected();
        addChild.setDisable(c.isEmpty() || !c.get().isRoot());
        rename.setDisable(c.isEmpty());
        archive.setDisable(c.isEmpty());
        delete.setDisable(c.isEmpty());
        archive.setText(c.isPresent() && c.get().archived() ? "Réactiver" : "Archiver");
    }

    private void addRoot() {
        ChoiceDialog<CategoryKind> kindDialog = new ChoiceDialog<>(CategoryKind.EXPENSE, CategoryKind.values());
        kindDialog.setTitle("Nouvelle catégorie");
        kindDialog.setHeaderText(null);
        kindDialog.setContentText("Utilisée pour :");
        Dialogs.style(kindDialog.getDialogPane(), window(), kindDialog);
        kindDialog.showAndWait().ifPresent(kind -> askName("Nouvelle catégorie", "").ifPresent(name -> {
            ctx.services().categories().create(null, name, kind);
            ctx.events().fireChanged();
        }));
    }

    private void addChild() {
        selected().ifPresent(parent -> askName("Nouvelle sous-catégorie de « " + parent.name() + " »", "")
                .ifPresent(name -> {
                    ctx.services().categories().create(parent.id(), name, null);
                    ctx.events().fireChanged();
                }));
    }

    private void rename() {
        selected().ifPresent(c -> askName("Renommer « " + c.name() + " »", c.name()).ifPresent(name -> {
            ctx.services().categories().rename(c.id(), name);
            ctx.events().fireChanged();
        }));
    }

    private void toggleArchive() {
        selected().ifPresent(c -> {
            ctx.services().categories().setArchived(c.id(), !c.archived());
            ctx.events().fireChanged();
        });
    }

    private void delete() {
        selected().ifPresent(c -> {
            if (Dialogs.confirm(window(), "Supprimer la catégorie", "Supprimer « " + c.name() + " » ?", "Supprimer")) {
                ctx.services().categories().delete(c.id());
                ctx.events().fireChanged();
            }
        });
    }

    private Optional<String> askName(String title, String initial) {
        TextInputDialog dialog = new TextInputDialog(initial);
        dialog.setTitle(title);
        dialog.setHeaderText(null);
        dialog.setContentText("Nom :");
        Dialogs.style(dialog.getDialogPane(), window(), dialog);
        return dialog.showAndWait().map(String::strip).filter(s -> !s.isEmpty());
    }

    private Button button(String text, String style, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().add(style);
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
