import { useState } from 'react';
import { formatDate } from '../../domain/dates';
import { balances, groupOf } from '../../domain/ledger';
import { deleteAccount, setArchived } from '../../domain/operations';
import { ACCOUNT_TYPES, type Account, type AccountGroup } from '../../domain/types';
import { mutate, useData } from '../../store/session';
import { AccountDialog } from '../components/dialogs';
import { Empty, Money } from '../common';

const GROUPS: { key: AccountGroup[]; title: string }[] = [
  { key: ['CURRENT'], title: 'Comptes courants' },
  { key: ['SAVINGS'], title: 'Épargne' },
  { key: ['CASH', 'OTHER'], title: 'Espèces et autres' },
];

export function AccountsPage() {
  const data = useData();
  const [showArchived, setShowArchived] = useState(false);
  const [editing, setEditing] = useState<{ account?: Account; savings?: boolean } | null>(null);
  const b = balances(data);
  const visible = data.accounts.filter((a) => showArchived || !a.archived);
  const total = data.accounts.filter((a) => !a.archived).reduce((s, a) => s + (b.get(a.id) ?? 0), 0);

  const act = (run: () => void) => {
    try {
      run();
    } catch (e) {
      window.alert(e instanceof Error ? e.message : String(e));
    }
  };
  return (
    <>
      <div className="row">
        <button className="btn primary" onClick={() => setEditing({})}>+ Nouveau compte</button>
        <button className="btn" onClick={() => setEditing({ savings: true })}>+ Ajouter une épargne</button>
        <span className="spacer" />
        <label className="check">
          <input type="checkbox" checked={showArchived} onChange={(e) => setShowArchived(e.target.checked)} />
          Afficher les comptes archivés
        </label>
      </div>
      {visible.length === 0 && <div className="card"><Empty>Aucun compte. Créez votre premier compte pour commencer.
      </Empty></div>}
      {GROUPS.map((g) => {
        const members = visible.filter((a) => g.key.includes(groupOf(a)));
        if (members.length === 0) {
          return null;
        }
        const subtotal = members.filter((a) => !a.archived).reduce((s, a) => s + (b.get(a.id) ?? 0), 0);
        return (
          <section className="card section" key={g.title}>
            <h2>{g.title} · {members.length}</h2>
            {members.map((a) => (
              <div className={`account-row${a.archived ? ' archived' : ''}`} key={a.id}>
                <span className="bar" aria-hidden="true" />
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div className="name">{a.name}</div>
                  <div className="row" style={{ gap: 6 }}>
                    <span className="muted">{ACCOUNT_TYPES[a.type].label} · depuis le {formatDate(a.openingDate)}</span>
                    {a.includeInAvailable && <span className="badge info">inclus dans le disponible</span>}
                    {a.archived && <span className="badge">archivé</span>}
                  </div>
                </div>
                <Money cents={b.get(a.id) ?? 0} tone className="balance-big" />
                <div className="actions">
                  <button className="btn small" onClick={() => setEditing({ account: a })}>Modifier</button>
                  <button className="btn small" onClick={() => act(() => mutate((d) => setArchived(d, a.id, !a.archived)))}>
                    {a.archived ? 'Réactiver' : 'Archiver'}</button>
                  <button className="btn small danger" onClick={() => {
                    if (window.confirm(`Supprimer définitivement « ${a.name} » ? Seul un compte sans aucune opération peut être supprimé.`)) {
                      act(() => mutate((d) => deleteAccount(d, a.id)));
                    }
                  }}>Supprimer</button>
                </div>
              </div>
            ))}
            <div className="row" style={{ marginTop: 10 }}>
              <span className="muted">Sous-total {g.title.toLowerCase()}</span>
              <span className="spacer" />
              <Money cents={subtotal} />
            </div>
          </section>
        );
      })}
      {data.accounts.length > 0 && (
        <div className="card row">
          <strong>TOTAL</strong><span className="spacer" /><Money cents={total} className="balance-big" />
        </div>
      )}
      <p className="hint">Les comptes marqués « inclus dans le disponible » (par défaut : courants, joints, espèces,
        cartes prépayées) servent au calcul du disponible réel et des prévisions.</p>
      {editing && <AccountDialog existing={editing.account} savings={editing.savings} onClose={() => setEditing(null)} />}
    </>
  );
}
