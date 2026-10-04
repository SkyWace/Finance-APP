import { useState } from 'react';
import { goalProgress, type GoalProgress } from '../../domain/budgets';
import { formatLongMonth } from '../../domain/dates';
import { parseAmount } from '../../domain/money';
import { addContribution, deleteGoal, setGoalArchived } from '../../domain/operations';
import type { SavingsGoal } from '../../domain/types';
import { mutate, useData } from '../../store/session';
import { GoalDialog } from '../components/budgetDialogs';
import { Empty, ErrorText, Money, useAction, useToday } from '../common';
import { formatPermille, ProgressBar } from './BudgetsPage';

export function GoalsPage() {
  const data = useData();
  const today = useToday();
  const [editing, setEditing] = useState<{ goal?: SavingsGoal } | null>(null);
  const [error, run] = useAction();
  const goals = goalProgress(data, today);
  const archived = data.goals.filter((g) => g.archived);
  const accounts = new Map(data.accounts.map((a) => [a.id, a.name]));

  const contribute = (g: SavingsGoal) => void run(() => {
    const text = window.prompt(`Montant à verser sur « ${g.name} » (négatif pour un retrait) :`);
    if (text === null || text.trim() === '') {
      return;
    }
    const cents = parseAmount(text);
    if (cents === null || cents === 0) {
      throw new Error('Montant invalide : saisissez par exemple 150 ou -50');
    }
    mutate((d) => addContribution(d, g.id, cents));
  });

  const card = (p: GoalProgress) => {
    const g = p.goal;
    let schedule = 'Sans échéance';
    if (g.targetDate) {
      schedule = `Échéance : ${formatLongMonth(g.targetDate)}`;
      if (p.monthlyNeeded !== undefined) {
        schedule += ' · ';
      }
    }
    return (
      <section className="card section" key={g.id}>
        <div className="row">
          <strong>{g.name}</strong>
          <span className="spacer" />
          {p.reached && <span className="badge positive">✓ Objectif atteint</span>}
          {p.overdue && <span className="badge warning">! Échéance dépassée</span>}
        </div>
        <div className="row progress-row">
          <ProgressBar permille={p.permille} tone={p.reached ? 'positive' : 'info'} />
          <span className="percent">{formatPermille(p.permille)}</span>
        </div>
        <div className="row">
          <span><Money cents={p.saved} /> / <Money cents={g.target} /></span>
          <span className="spacer" />
          {!p.reached && <span>Reste <Money cents={p.remaining} /></span>}
        </div>
        <p className="hint">{schedule}{p.monthlyNeeded !== undefined && <><Money cents={p.monthlyNeeded} /> par mois
          nécessaires{p.monthsLeft ? ` (${p.monthsLeft} mois)` : ''}</>}</p>
        <div className="row">
          <span className="muted small-text">
            {g.linkedAccountId === undefined ? 'Montant suivi manuellement'
              : `Suit le solde du compte « ${accounts.get(g.linkedAccountId) ?? '?'} »`}
            {g.reserveInAvailable ? ' · effort mensuel réservé dans le disponible' : ''}</span>
          <span className="spacer" />
          {g.linkedAccountId === undefined && <button className="btn small" onClick={() => contribute(g)}>Verser / retirer…</button>}
          <button className="btn small" onClick={() => setEditing({ goal: g })}>Modifier</button>
          <button className="btn small" onClick={() => mutate((d) => setGoalArchived(d, g.id, true))}>Archiver</button>
          <button className="btn small danger" onClick={() => {
            if (window.confirm(`Supprimer « ${g.name} » ?`)) {
              mutate((d) => deleteGoal(d, g.id));
            }
          }}>Supprimer</button>
        </div>
      </section>
    );
  };

  return (
    <>
      <div className="row">
        <button className="btn primary" onClick={() => setEditing({})}>+ Nouvel objectif</button>
      </div>
      <ErrorText text={error} />
      {goals.length === 0 ? (
        <section className="card section"><Empty>Aucun objectif. Fonds d'urgence, vacances, voiture… : fixez un montant
          et une échéance, l'application calcule l'épargne mensuelle nécessaire.</Empty></section>
      ) : goals.map(card)}
      {archived.length > 0 && (
        <section className="card section">
          <h2>Archivés</h2>
          <div className="rows">
            {archived.map((g) => (
              <div className="op-row" key={g.id}>
                <div className="texts"><div className="label">{g.name}</div></div>
                <Money cents={g.target} />
                <div className="actions">
                  <button className="btn small" onClick={() => mutate((d) => setGoalArchived(d, g.id, false))}>Réactiver</button>
                  <button className="btn small danger" onClick={() => {
                    if (window.confirm(`Supprimer « ${g.name} » ?`)) {
                      mutate((d) => deleteGoal(d, g.id));
                    }
                  }}>Supprimer</button>
                </div>
              </div>
            ))}
          </div>
        </section>
      )}
      {editing && <GoalDialog existing={editing.goal} onClose={() => setEditing(null)} />}
    </>
  );
}
