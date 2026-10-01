package com.financeapp.desktop.ui.pages;

import com.financeapp.core.calendar.CalendarDay;
import com.financeapp.core.money.Money;
import com.financeapp.core.planning.PlannedItem;
import com.financeapp.core.transaction.Transaction;
import com.financeapp.core.transaction.TransactionType;
import com.financeapp.desktop.ui.common.AccountFilter;
import com.financeapp.desktop.ui.common.Formats;
import com.financeapp.desktop.ui.common.MonthPicker;
import com.financeapp.desktop.ui.common.UiAsync;
import com.financeapp.desktop.ui.common.UiContext;
import com.financeapp.desktop.ui.common.Widgets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Calendrier financier : operations reelles et prevues jour par jour, avec
 * le solde en fin de journee (reel jusqu'a aujourd'hui, prevu ensuite).
 * Un clic sur un jour affiche le detail.
 */
public final class CalendarPage extends Page {

    private static final String[] WEEKDAYS = {"Lun.", "Mar.", "Mer.", "Jeu.", "Ven.", "Sam.", "Dim."};
    private static final int MAX_LINES = 3;

    private final MonthPicker picker;
    private final GridPane grid = new GridPane();
    private final VBox detail = new VBox(6);
    private final AccountFilter filter;
    private final Label legend = Widgets.label("", "muted");
    private LocalDate selected;
    private Long account;
    private List<CalendarDay> days = List.of();

    public CalendarPage(UiContext ctx) {
        super(ctx);
        LocalDate today = ctx.services().planning().today();
        selected = today;
        YearMonth current = YearMonth.from(today);
        picker = new MonthPicker(current, current);
        picker.monthProperty().addListener((o, old, m) -> {
            selected = m.equals(current) ? today : m.atDay(1);
            refresh();
        });
        grid.getStyleClass().add("calendar-grid");
        grid.setHgap(6);
        grid.setVgap(6);
        for (int i = 0; i < 7; i++) {
            ColumnConstraints col = new ColumnConstraints();
            col.setPercentWidth(100.0 / 7);
            col.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().add(col);
        }
        detail.getStyleClass().add("card");
        filter = new AccountFilter(ctx, this::refresh);
        legend.setWrapText(true);
        content.getChildren().setAll(Widgets.row(picker, Widgets.spacer(), filter.node()), grid, legend, detail);
    }

    @Override
    public String title() {
        return "Calendrier";
    }

    @Override
    public void refresh() {
        YearMonth month = picker.month();
        Long account = filter.sync();
        this.account = account;
        legend.setText(account == null
                ? "Sous chaque jour : solde des comptes du disponible en fin de journée (« prévu » à partir d'aujourd'hui)."
                : "Sous chaque jour : solde de « " + filter.selectedName() + " » en fin de journée (« prévu » à partir "
                  + "d'aujourd'hui), virements compris. Avant la dernière valeur saisie d'une épargne, il n'est pas affiché.");
        UiAsync.load(() -> ctx.services().calendar().month(month, account), list -> {
            days = list;
            render();
        });
    }

    private void render() {
        Formats f = ctx.formats();
        LocalDate today = ctx.services().planning().today();
        grid.getChildren().clear();
        for (int i = 0; i < 7; i++) {
            Label h = Widgets.label(WEEKDAYS[i], "calendar-weekday");
            h.setMaxWidth(Double.MAX_VALUE);
            h.setAlignment(Pos.CENTER);
            grid.add(h, i, 0);
        }
        if (days.isEmpty()) {
            return;
        }
        int offset = days.getFirst().date().getDayOfWeek().getValue() - 1;
        for (int i = 0; i < days.size(); i++) {
            CalendarDay day = days.get(i);
            grid.add(cell(day, today, f), (offset + i) % 7, 1 + (offset + i) / 7);
        }
        renderDetail(f, today);
    }

    private VBox cell(CalendarDay day, LocalDate today, Formats f) {
        Label number = Widgets.label(Integer.toString(day.date().getDayOfMonth()), "calendar-day-number");
        VBox box = new VBox(2, number);
        List<Object[]> lines = new ArrayList<>();
        for (Transaction t : day.realized()) {
            // Vue globale : une ligne par virement ; vue d'un compte : sa propre jambe.
            if (!t.isTransfer() || t.amount().isNegative() || account != null) {
                lines.add(new Object[]{t.label(), t.amount(), false});
            }
        }
        for (PlannedItem p : day.planned()) {
            lines.add(new Object[]{p.label(), p.amount(), true});
        }
        for (int i = 0; i < Math.min(MAX_LINES, lines.size()); i++) {
            Object[] l = lines.get(i);
            Label text = Widgets.label(l[0] + " " + f.signed((Money) l[1]), "calendar-op",
                    Formats.signClass((Money) l[1]));
            if ((boolean) l[2]) {
                text.getStyleClass().add("calendar-op-planned");
            }
            text.setMaxWidth(Double.MAX_VALUE);
            box.getChildren().add(text);
        }
        if (lines.size() > MAX_LINES) {
            box.getChildren().add(Widgets.label("+ " + (lines.size() - MAX_LINES) + " autre(s)", "op-detail"));
        }
        box.getChildren().add(Widgets.spacer());
        if (day.balance() != null) {
            Label balance = Widgets.label((day.projected() ? "prévu " : "") + f.money(day.balance()), "calendar-balance",
                    day.balance().isNegative() ? "amount-negative" : "muted");
            box.getChildren().add(balance);
        }
        box.getStyleClass().add("calendar-cell");
        if (day.date().equals(today)) {
            box.getStyleClass().add("calendar-today");
        }
        if (day.date().equals(selected)) {
            box.getStyleClass().add("calendar-selected");
        }
        if (day.date().isBefore(today)) {
            box.getStyleClass().add("calendar-past");
        }
        box.setMinHeight(96);
        box.setMaxWidth(Double.MAX_VALUE);
        box.setCursor(Cursor.HAND);
        box.setOnMouseClicked(e -> {
            selected = day.date();
            render();
        });
        return box;
    }

    private void renderDetail(Formats f, LocalDate today) {
        CalendarDay day = days.stream().filter(d -> d.date().equals(selected)).findFirst().orElse(days.getFirst());
        detail.getChildren().setAll(Widgets.label(Formats.longDate(day.date()), "section-title"));
        for (Transaction t : day.realized()) {
            detail.getChildren().add(Widgets.operationRow(f, t.date(), t.label(), t.status().label(), t.amount(),
                    t.type() == TransactionType.TRANSFER ? Widgets.badge("virement", "neutral") : null));
        }
        for (PlannedItem p : day.planned()) {
            detail.getChildren().add(Widgets.operationRow(f, p.date(), p.label(),
                    p.source() == PlannedItem.Source.RECURRING ? "récurrent" : "prévu", p.amount(),
                    p.isOverdue(today) ? Widgets.badge("en retard", "warning") : Widgets.badge("à venir", "info")));
        }
        if (day.isEmpty()) {
            detail.getChildren().add(Widgets.emptyState("Aucune opération ce jour-là."));
        }
        if (day.balance() != null) {
            HBox total = Widgets.row(Widgets.label(day.projected() ? "SOLDE PRÉVU EN FIN DE JOURNÉE" : "SOLDE EN FIN DE JOURNÉE",
                    "total-label"), Widgets.spacer(), Widgets.label(f.money(day.balance()), "total-value"));
            total.getStyleClass().add("total-row");
            detail.getChildren().add(total);
        }
    }
}
