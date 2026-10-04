import { useState } from 'react';
import { parseAmount, toEditable, type Cents } from '../../domain/money';
import { saveBudget, saveGoal } from '../../domain/operations';
import type { Budget, SavingsGoal } from '../../domain/types';
import { mutate, useData } from '../../store/session';
import { AccountSelect, CategorySelect, Dialog, ErrorText, useAction } from '../common';

function positive(text: string, message: string): Cents {
  const value = parseAmount(text);
  if (value === null || value <= 0) {
    throw new Error(message);
  }
  return value;
}

export function BudgetDialog({ existing, onClose }: { existing?: Budget; onClose: () => void }) {
  const data = useData();
  const [categoryId, setCategoryId] = useState<number | null>(existing?.categoryId ?? null);
  const [limit, setLimit] = useState(existing ? toEditable(existing.limit) : '');
  const [reserve, setReserve] = useState(existing?.reserveInAvailable ?? true);
  const [error, run] = useAction();
  const submit = () => run(() => {
    const cents = positive(limit, 'Le plafond doit être un montant positif (ex. 400)');
    mutate((d) => saveBudget(d, { categoryId, limit: cents, reserveInAvailable: reserve }, existing?.id));
    onClose();
  });
  return (
    <Dialog title={existing ? 'Modifier le budget' : 'Nouveau budget'} onClose={onClose}>
      <form className="form" onSubmit={(e) => { e.preventDefault(); void submit(); }}>
        <label htmlFor="bd-category">Catégorie</label>
        <CategorySelect id="bd-category" data={data} income={false} value={categoryId} onChange={setCategoryId}
          emptyLabel="— Choisir —" />
        <label htmlFor="bd-limit">Plafond mensuel (€)</label>
        <input id="bd-limit" type="text" inputMode="decimal" value={limit} placeholder="0,00"
          onChange={(e) => setLimit(e.target.value)} />
        <label className="check full">
          <input type="checkbox" checked={reserve} onChange={(e) => setReserve(e.target.checked)} />
          Réserver le reste du budget dans le disponible réel</label>
        <p className="hint full">Le budget couvre aussi les sous-catégories (« Alimentation » compte « Courses »). Réservé,
          ce qu'il reste à dépenser ce mois-ci (au prorata des jours) est mis de côté dans le disponible réel, sans
          compter deux fois les dépenses déjà prévues.</p>
        <ErrorText text={error} />
        <div className="buttons full">
          <button type="button" className="btn" onClick={onClose}>Annuler</button>
          <button type="submit" className="btn primary">Enregistrer</button>
        </div>
      </form>
    </Dialog>
  );
}

export function GoalDialog({ existing, onClose }: { existing?: SavingsGoal; onClose: () => void }) {
  const data = useData();
  const [name, setName] = useState(existing?.name ?? '');
  const [target, setTarget] = useState(existing ? toEditable(existing.target) : '');
  const [targetDate, setTargetDate] = useState(existing?.targetDate ?? '');
  const [linked, setLinked] = useState<number | undefined>(existing?.linkedAccountId);
  const [saved, setSaved] = useState(existing ? toEditable(existing.manualSaved) : '0');
  const [reserve, setReserve] = useState(existing?.reserveInAvailable ?? false);
  const [error, run] = useAction();
  const submit = () => run(() => {
    const targetCents = positive(target, 'Le montant visé doit être un montant positif (ex. 1500)');
    const savedCents = linked === undefined ? parseAmount(saved || '0') : existing?.manualSaved ?? 0;
    if (savedCents === null || savedCents < 0) {
      throw new Error('Montant déjà épargné invalide');
    }
    mutate((d) => saveGoal(d, { name, target: targetCents, targetDate: targetDate || undefined, linkedAccountId: linked,
      manualSaved: savedCents, reserveInAvailable: reserve }, existing?.id));
    onClose();
  });
  return (
    <Dialog title={existing ? "Modifier l'objectif" : 'Nouvel objectif'} onClose={onClose}>
      <form className="form" onSubmit={(e) => { e.preventDefault(); void submit(); }}>
        <label htmlFor="gd-name">Nom</label>
        <input id="gd-name" type="text" value={name} maxLength={200} placeholder="Ex. Vacances, fonds d'urgence"
          onChange={(e) => setName(e.target.value)} />
        <label htmlFor="gd-target">Montant visé (€)</label>
        <input id="gd-target" type="text" inputMode="decimal" value={target} placeholder="0,00"
          onChange={(e) => setTarget(e.target.value)} />
        <label htmlFor="gd-date">Échéance (facultative)</label>
        <input id="gd-date" type="date" value={targetDate} onChange={(e) => setTargetDate(e.target.value)} />
        <label htmlFor="gd-account">Épargne suivie</label>
        <AccountSelect id="gd-account" accounts={data.accounts} value={linked} onChange={setLinked}
          emptyLabel="À la main (montant ci-dessous)" />
        {linked === undefined && <>
          <label htmlFor="gd-saved">Déjà épargné (€)</label>
          <input id="gd-saved" type="text" inputMode="decimal" value={saved} onChange={(e) => setSaved(e.target.value)} />
        </>}
        <label className="check full">
          <input type="checkbox" checked={reserve} onChange={(e) => setReserve(e.target.checked)} />
          Réserver chaque mois l'effort nécessaire dans le disponible réel</label>
        <p className="hint full">Lié à un compte (ex. un livret dédié), l'objectif suit son solde. Laissez la réservation
          décochée si un virement récurrent alimente déjà l'objectif : il est déjà déduit du disponible.</p>
        <ErrorText text={error} />
        <div className="buttons full">
          <button type="button" className="btn" onClick={onClose}>Annuler</button>
          <button type="submit" className="btn primary">Enregistrer</button>
        </div>
      </form>
    </Dialog>
  );
}
