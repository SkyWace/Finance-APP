package com.financeapp.desktop.ui.common;

import com.financeapp.core.attachment.Attachment;
import com.financeapp.core.attachment.AttachmentType;
import com.financeapp.core.service.AttachmentService;
import com.financeapp.core.service.BusinessException;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Justificatifs d'une operation dans sa fenetre de saisie : joindre (bouton ou
 * glisser-deposer), voir, enregistrer une copie, retirer. Rien n'est ecrit avant
 * l'enregistrement de l'operation ({@link #commit(long)}) : "Annuler" annule aussi
 * les ajouts et retraits.
 */
public final class AttachmentsPane {

    /** Fichier choisi, pas encore enregistre. */
    private record Pending(String name, AttachmentType type, byte[] content) {
    }

    /** Dernier dossier choisi, propose a la prochaine ouverture (session en cours seulement). */
    private static File lastDirectory;

    private final UiContext ctx;
    private final Supplier<Window> owner;
    private final List<Attachment> saved = new ArrayList<>();
    private final Set<Long> removed = new HashSet<>();
    private final List<Pending> added = new ArrayList<>();
    private final VBox list = new VBox(6);
    private final VBox node;
    private final Label error = Widgets.label("", "form-error");
    private Runnable onResize = () -> { };

    public AttachmentsPane(UiContext ctx, Long transactionId, Supplier<Window> owner) {
        this.ctx = ctx;
        this.owner = owner;
        if (transactionId != null) {
            saved.addAll(ctx.services().attachments().list(transactionId));
        }
        Button add = new Button("+  Joindre un fichier…");
        add.getStyleClass().addAll("ghost", "compact");
        add.setOnAction(e -> choose());
        Label hint = Widgets.label("ou glissez-le ici · PDF ou image, 10 Mo max", "hint");
        error.setWrapText(true);
        error.managedProperty().bind(error.textProperty().isNotEmpty());
        add.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        hint.setWrapText(true);
        node = new VBox(6, list, add, hint, error);
        node.getStyleClass().add("attachments");
        node.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) {
                e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });
        node.setOnDragDropped(e -> {
            boolean ok = e.getDragboard().hasFiles();
            if (ok) {
                e.getDragboard().getFiles().forEach(this::add);
            }
            e.setDropCompleted(ok);
            e.consume();
        });
        render();
    }

    public Node node() {
        return node;
    }

    /** Appele quand la hauteur change (la fenetre s'ajuste). */
    public void setOnResize(Runnable onResize) {
        this.onResize = onResize;
    }

    /** Applique les ajouts et retraits, une fois l'operation enregistree. */
    public void commit(long transactionId) {
        AttachmentService service = ctx.services().attachments();
        for (Long id : removed) {
            service.delete(id);
        }
        for (Pending p : added) {
            service.attach(transactionId, p.name(), p.content());
        }
        removed.clear();
        added.clear();
    }

    private void choose() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Joindre un justificatif");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("PDF et images", "*.pdf", "*.PDF", "*.jpg", "*.JPG", "*.jpeg",
                        "*.JPEG", "*.png", "*.PNG", "*.gif", "*.webp", "*.heic", "*.HEIC"),
                new FileChooser.ExtensionFilter("Tous les fichiers", "*.*"));
        if (lastDirectory != null && lastDirectory.isDirectory()) {
            chooser.setInitialDirectory(lastDirectory);
        }
        List<File> files = chooser.showOpenMultipleDialog(owner.get());
        if (files != null) {
            if (!files.isEmpty()) {
                lastDirectory = files.getFirst().getParentFile();
            }
            files.forEach(this::add);
        }
    }

    private void add(File file) {
        try {
            if (Files.size(file.toPath()) > AttachmentService.MAX_SIZE) {
                throw new BusinessException("« " + file.getName() + " » dépasse 10 Mo : réduisez-le avant de le joindre");
            }
            byte[] content = Files.readAllBytes(file.toPath());
            AttachmentType type = AttachmentService.check(content);
            if (saved.size() - removed.size() + added.size() >= AttachmentService.MAX_PER_TRANSACTION) {
                throw new BusinessException("Une opération ne peut pas avoir plus de "
                        + AttachmentService.MAX_PER_TRANSACTION + " justificatifs");
            }
            added.add(new Pending(file.getName(), type, content));
            error.setText("");
        } catch (BusinessException e) {
            error.setText(e.getMessage().startsWith("«") ? e.getMessage() : "« " + file.getName() + " » : " + e.getMessage());
        } catch (IOException e) {
            error.setText("« " + file.getName() + " » n'a pas pu être lu.");
        }
        render();
    }

    private void render() {
        list.getChildren().clear();
        for (Attachment a : saved) {
            if (!removed.contains(a.id())) {
                list.getChildren().add(row(a.fileName(), a.type(), a.size(), false,
                        () -> ctx.services().attachments().content(a.id()), () -> removed.add(a.id())));
            }
        }
        for (Pending p : added) {
            list.getChildren().add(row(p.name(), p.type(), p.content().length, true, p::content, () -> added.remove(p)));
        }
        if (list.getChildren().isEmpty()) {
            list.getChildren().add(Widgets.label("Aucun justificatif.", "op-detail"));
        }
        onResize.run();
    }

    private VBox row(String name, AttachmentType type, long size, boolean pending, Supplier<byte[]> content,
                     Runnable remove) {
        Label kind = Widgets.badge(type == AttachmentType.PDF ? "PDF" : "Image", "neutral");
        kind.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        Label title = Widgets.label(name, "op-label");
        title.setMinWidth(0);
        title.setTooltip(new Tooltip(name));
        HBox top = new HBox(8, kind, title);
        top.setAlignment(Pos.CENTER_LEFT);
        Label detail = Widgets.label(size(size) + (pending ? " · nouveau" : ""), "op-detail");
        detail.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        Button view = new Button(type.previewable() ? "Voir" : "Ouvrir");
        view.getStyleClass().addAll("ghost", "compact");
        view.setTooltip(new Tooltip(type.previewable() ? "Afficher dans l'application"
                : "Ouvrir avec l'application de l'ordinateur (copie temporaire effacée au verrouillage)"));
        view.setOnAction(e -> view(name, type, content));
        Button export = new Button("Enregistrer…");
        export.getStyleClass().addAll("ghost", "compact");
        export.setTooltip(new Tooltip("Enregistrer une copie (non chiffrée) à l'endroit de votre choix"));
        export.setOnAction(e -> export(name, content));
        Button delete = new Button("Retirer");
        delete.getStyleClass().addAll("ghost", "compact");
        delete.setOnAction(e -> {
            remove.run();
            render();
        });
        for (Button b : List.of(view, export, delete)) {
            b.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        }
        HBox bottom = new HBox(8, detail, Widgets.spacer(), view, export, delete);
        bottom.setAlignment(Pos.CENTER_LEFT);
        VBox row = new VBox(4, top, bottom);
        row.getStyleClass().add("attachment-row");
        return row;
    }

    private void view(String name, AttachmentType type, Supplier<byte[]> content) {
        try {
            byte[] bytes = content.get();
            if (type.previewable()) {
                preview(name, bytes);
                return;
            }
            Path copy = OpenedFiles.write(ctx.services().directories().openedAttachmentsDir(), name, bytes);
            if (!Browser.openFile(copy)) {
                error.setText("Aucune application n'a pu ouvrir ce fichier : utilisez « Enregistrer… ».");
            }
        } catch (IOException | RuntimeException e) {
            error.setText("Le justificatif n'a pas pu être ouvert : utilisez « Enregistrer… ».");
        }
    }

    /** Image affichee dans une fenetre de l'application : aucune copie sur le disque. */
    private void preview(String name, byte[] bytes) {
        Image image = new Image(new ByteArrayInputStream(bytes));
        if (image.isError()) {
            error.setText("Cette image n'a pas pu être affichée : utilisez « Enregistrer… ».");
            return;
        }
        ImageView view = new ImageView(image);
        view.setPreserveRatio(true);
        view.setFitWidth(Math.min(image.getWidth(), 900));
        ScrollPane scroll = new ScrollPane(view);
        scroll.setPannable(true);
        scroll.getStyleClass().add("attachment-preview");
        Stage stage = new Stage();
        stage.initOwner(owner.get());
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle(name);
        Scene scene = new Scene(scroll, Math.min(image.getWidth(), 900) + 24, Math.min(image.getHeight(), 700) + 24);
        Theme.apply(scene.getStylesheets());
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                stage.close();
            }
        });
        stage.setScene(scene);
        // Fermee aussi au verrouillage (toutes les fenetres secondaires le sont)
        stage.show();
    }

    private void export(String name, Supplier<byte[]> content) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Enregistrer une copie du justificatif");
        chooser.setInitialFileName(name);
        File target = chooser.showSaveDialog(owner.get());
        if (target == null) {
            return;
        }
        try {
            Files.write(target.toPath(), content.get());
        } catch (IOException | RuntimeException e) {
            error.setText("La copie n'a pas pu être enregistrée à cet endroit.");
        }
    }

    public static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " o";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.FRANCE, "%.0f Ko", bytes / 1024.0);
        }
        return String.format(Locale.FRANCE, "%.1f Mo", bytes / (1024.0 * 1024));
    }
}
