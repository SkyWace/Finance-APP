import { useState } from 'react';
import { categoryUsage, deleteCategory, saveCategory, setCategoryArchived } from '../../domain/operations';
import type { Category, CategoryKind } from '../../domain/types';
import { mutate, useData } from '../../store/session';
import { Dialog, ErrorText, useAction } from '../common';

const KINDS: Record<CategoryKind, string> = { EXPENSE: 'Dépenses', INCOME: 'Revenus', BOTH: 'Dépenses et revenus' };

function CategoryDialog({ existing, parentId, onClose }:
  { existing?: Category; parentId: number | null; onClose: () => void }) {
  const data = useData();
  const [name, setName] = useState(existing?.name ?? '');
  const [parent, setParent] = useState<number | null>(existing ? existing.parentId : parentId);
  const [kind, setKind] = useState<CategoryKind>(existing?.kind ?? 'EXPENSE');
  const [error, run] = useAction();
  const parents = data.categories.filter((c) => c.parentId === null && c.id !== existing?.id);
  return (
    <Dialog title={existing ? 'Modifier la catégorie' : 'Nouvelle catégorie'} onClose={onClose}>
      <form className="form" onSubmit={(e) => {
        e.preventDefault();
        void run(() => {
          mutate((d) => saveCategory(d, name, parent, kind, existing?.id));
          onClose();
        });
      }}>
        <label htmlFor="ca-name">Nom</label>
        <input id="ca-name" type="text" value={name} onChange={(e) => setName(e.target.value)} maxLength={200} />
        <label htmlFor="ca-parent">Dans</label>
        <select id="ca-parent" value={parent ?? ''} onChange={(e) => setParent(e.target.value ? Number(e.target.value) : null)}>
          <option value="">— Catégorie principale —</option>
          {parents.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
        </select>
        <label htmlFor="ca-kind">Usage</label>
        <select id="ca-kind" value={kind} onChange={(e) => setKind(e.target.value as CategoryKind)}>
          {Object.entries(KINDS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
        </select>
        <ErrorText text={error} />
        <div className="buttons full">
          <button type="button" className="btn ghost" onClick={onClose}>Annuler</button>
          <button type="submit" className="btn primary">Enregistrer</button>
        </div>
      </form>
    </Dialog>
  );
}

export function CategoriesPage() {
  const data = useData();
  const [editing, setEditing] = useState<{ category?: Category; parentId: number | null } | null>(null);
  const [showArchived, setShowArchived] = useState(false);
  const act = (run: () => void) => {
    try {
      run();
    } catch (e) {
      window.alert(e instanceof Error ? e.message : String(e));
    }
  };
  const row = (c: Category, child: boolean) => {
    const used = categoryUsage(data, c.id);
    return (
      <div className="op-row" key={c.id} style={{ paddingLeft: child ? 28 : 4, opacity: c.archived ? 0.6 : 1 }}>
        <div className="texts">
          <div className="label" style={{ fontWeight: child ? 400 : 700 }}>{c.name}
            {c.archived && <span className="badge"> archivée</span>}</div>
          <div className="detail">{KINDS[c.kind]} · {used} opération(s) ou récurrence(s)</div>
        </div>
        <div className="actions">
          {!child && <button className="btn small" onClick={() => setEditing({ parentId: c.id })}>+ Sous-catégorie</button>}
          <button className="btn small" onClick={() => setEditing({ category: c, parentId: c.parentId })}>Modifier</button>
          <button className="btn small" onClick={() => act(() => mutate((d) => setCategoryArchived(d, c.id, !c.archived)))}>
            {c.archived ? 'Réactiver' : 'Archiver'}</button>
          {used === 0 && <button className="btn small danger" onClick={() => {
            if (window.confirm(`Supprimer la catégorie « ${c.name} » ?`)) {
              act(() => mutate((d) => deleteCategory(d, c.id)));
            }
          }}>Supprimer</button>}
        </div>
      </div>
    );
  };
  const parents = data.categories.filter((c) => c.parentId === null && (showArchived || !c.archived));
  return (
    <>
      <div className="row">
        <button className="btn primary" onClick={() => setEditing({ parentId: null })}>+ Nouvelle catégorie</button>
        <span className="spacer" />
        <label className="check"><input type="checkbox" checked={showArchived}
          onChange={(e) => setShowArchived(e.target.checked)} />Afficher les catégories archivées</label>
      </div>
      <div className="card rows">
        {parents.map((p) => [row(p, false), ...data.categories
          .filter((c) => c.parentId === p.id && (showArchived || !c.archived)).map((c) => row(c, true))])}
      </div>
      <p className="hint">Une catégorie utilisée ne peut pas être supprimée : archivez-la, elle disparaît des choix
        mais l'historique est conservé.</p>
      {editing && <CategoryDialog existing={editing.category} parentId={editing.parentId} onClose={() => setEditing(null)} />}
    </>
  );
}
