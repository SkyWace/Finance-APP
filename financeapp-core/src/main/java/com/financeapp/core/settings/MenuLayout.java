package com.financeapp.core.settings;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Disposition de la barre laterale : ordre de tous les menus et menus retires.
 * Valeur immuable ; chaque modification renvoie une nouvelle disposition.
 *
 * @param order  tous les menus connus, dans l'ordre choisi (affiches ou non)
 * @param hidden menus retires de la barre (toujours accessibles par les liens)
 * @param pinned menus qui ne peuvent pas etre retires (ex. Parametres)
 */
public record MenuLayout(List<String> order, Set<String> hidden, Set<String> pinned) {

    public MenuLayout {
        order = List.copyOf(new LinkedHashSet<>(order));
        Set<String> h = new LinkedHashSet<>(hidden);
        h.retainAll(order);
        h.removeAll(pinned);
        hidden = Set.copyOf(h);
        pinned = Set.copyOf(pinned);
    }

    /**
     * Disposition a partir de ce qui est enregistre.
     *
     * @param catalog          menus de l'application, dans l'ordre par defaut
     * @param hiddenByDefault  menus facultatifs, retires tant qu'on ne les ajoute pas
     * @param pinned           menus non retirables
     * @param savedOrder       ordre enregistre (vide si jamais modifie)
     * @param savedHidden      menus retires enregistres, ou {@code null} si jamais enregistre
     * @param legacyEnabled    menus facultatifs ajoutes avec l'ancien reglage (avant l'ordre libre)
     */
    public static MenuLayout resolve(List<String> catalog, Set<String> hiddenByDefault, Set<String> pinned,
                                     List<String> savedOrder, Set<String> savedHidden, Set<String> legacyEnabled) {
        List<String> order = new ArrayList<>();
        for (String id : savedOrder) {
            if (catalog.contains(id) && !order.contains(id)) {
                order.add(id);
            }
        }
        Set<String> hidden = new LinkedHashSet<>();
        if (savedHidden == null) {
            hidden.addAll(hiddenByDefault);
            hidden.removeAll(legacyEnabled);
        } else {
            hidden.addAll(savedHidden);
        }
        // Menus inconnus de l'ordre enregistre (nouveaux dans cette version) : a leur place par defaut.
        boolean knownOrder = !order.isEmpty();
        for (int i = 0; i < catalog.size(); i++) {
            String id = catalog.get(i);
            if (order.contains(id)) {
                continue;
            }
            int at = 0;
            for (int j = i - 1; j >= 0; j--) {
                int previous = order.indexOf(catalog.get(j));
                if (previous >= 0) {
                    at = previous + 1;
                    break;
                }
            }
            order.add(at, id);
            if (knownOrder && savedHidden != null && hiddenByDefault.contains(id)) {
                hidden.add(id);
            }
        }
        return new MenuLayout(order, hidden, pinned);
    }

    /** Menus affiches, dans l'ordre. */
    public List<String> visible() {
        return order.stream().filter(id -> !hidden.contains(id)).toList();
    }

    public boolean isVisible(String id) {
        return order.contains(id) && !hidden.contains(id);
    }

    public boolean canHide(String id) {
        return !pinned.contains(id);
    }

    public MenuLayout hide(String id) {
        if (!canHide(id)) {
            return this;
        }
        Set<String> h = new LinkedHashSet<>(hidden);
        h.add(id);
        return new MenuLayout(order, h, pinned);
    }

    /** Remet un menu dans la barre, a la place qu'il occupait. */
    public MenuLayout show(String id) {
        Set<String> h = new LinkedHashSet<>(hidden);
        h.remove(id);
        return new MenuLayout(order, h, pinned);
    }

    /** Place {@code id} juste avant (ou apres) {@code target}. */
    public MenuLayout moveNextTo(String id, String target, boolean after) {
        if (Objects.equals(id, target) || !order.contains(id) || !order.contains(target)) {
            return this;
        }
        List<String> o = new ArrayList<>(order);
        o.remove(id);
        int at = o.indexOf(target) + (after ? 1 : 0);
        o.add(at, id);
        return new MenuLayout(o, hidden, pinned);
    }

    /** Deplace un menu d'un cran parmi les menus affiches (-1 : vers le haut, +1 : vers le bas). */
    public MenuLayout moveBy(String id, int step) {
        List<String> shown = visible();
        int i = shown.indexOf(id);
        int j = i + Integer.signum(step);
        if (i < 0 || step == 0 || j < 0 || j >= shown.size()) {
            return this;
        }
        return moveNextTo(id, shown.get(j), step > 0);
    }

    /** Disposition d'origine : ordre par defaut, menus facultatifs retires. */
    public static MenuLayout defaults(List<String> catalog, Collection<String> hiddenByDefault, Set<String> pinned) {
        return new MenuLayout(catalog, new LinkedHashSet<>(hiddenByDefault), pinned);
    }
}
