import { useEffect, useRef, useState, type ReactNode } from 'react';
import { todayLocal, type IsoDate } from '../domain/dates';
import { formatMoney, formatSigned, type Cents } from '../domain/money';
import { BusinessError, categoryPath } from '../domain/operations';
import type { Account, Category, FinanceData } from '../domain/types';
import { useSession } from '../store/session';

/** Date du jour, mise a jour si l'onglet reste ouvert apres minuit. */
export function useToday(): IsoDate {
  const [today, setToday] = useState(todayLocal());
  useEffect(() => {
    const timer = window.setInterval(() => setToday(todayLocal()), 60_000);
    return () => window.clearInterval(timer);
  }, []);
  return today;
}

export function usePrivacy(): boolean {
  return useSession().data?.settings.privacy ?? false;
}

/**
 * Montant affiche. signed : signe toujours ecrit (+/-) ; tone : couleur en plus du
 * signe (jamais la couleur seule). Mode confidentialite : montant masque.
 */
export function Money({ cents, signed = false, tone = false, className = '' }:
  { cents: Cents; signed?: boolean; tone?: boolean; className?: string }) {
  const privacy = usePrivacy();
  if (privacy) {
    return <span className={`amount privacy-hidden ${className}`} aria-label="montant masqué">•••••• €</span>;
  }
  const toneClass = tone ? (cents > 0 ? 'positive' : cents < 0 ? 'negative' : '') : '';
  return <span className={`amount ${toneClass} ${className}`}>{signed ? formatSigned(cents) : formatMoney(cents)}</span>;
}

export function Dialog({ title, onClose, children, wide = false }:
  { title: string; onClose: () => void; children: ReactNode; wide?: boolean }) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    const first = ref.current?.querySelector<HTMLElement>('input, select, textarea, button');
    first?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        onClose();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('keydown', onKey);
      previous?.focus?.();
    };
  }, [onClose]);
  return (
    <div className="overlay" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="dialog" role="dialog" aria-modal="true" aria-label={title} ref={ref}
        style={wide ? { width: 'min(820px, 100%)' } : undefined}>
        <h2>{title}</h2>
        {children}
      </div>
    </div>
  );
}

/**
 * Execute une action ; une erreur de saisie s'affiche dans le formulaire, une
 * erreur inattendue aussi (jamais ignoree en silence).
 */
export function useAction(): [string, (run: () => void | Promise<void>) => Promise<boolean>, (m: string) => void] {
  const [error, setError] = useState('');
  const run = async (action: () => void | Promise<void>) => {
    try {
      setError('');
      await action();
      return true;
    } catch (e) {
      setError(e instanceof BusinessError || e instanceof Error ? e.message : String(e));
      return false;
    }
  };
  return [error, run, setError];
}

export function ErrorText({ text }: { text: string }) {
  return text ? <p className="error full" role="alert">{text}</p> : null;
}

/** Choix d'un compte (actifs ; garde le compte deja choisi meme archive). */
export function AccountSelect({ accounts, value, onChange, id, exclude, emptyLabel }:
  { accounts: Account[]; value: number | undefined; onChange: (id: number | undefined) => void; id?: string;
    exclude?: number; emptyLabel?: string }) {
  const options = accounts.filter((a) => (!a.archived || a.id === value) && a.id !== exclude);
  return (
    <select id={id} value={value ?? ''} onChange={(e) => onChange(e.target.value ? Number(e.target.value) : undefined)}>
      {emptyLabel !== undefined && <option value="">{emptyLabel}</option>}
      {options.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
    </select>
  );
}

/** Categories adaptees au sens (depense / revenu), en arbre "Parent › Enfant". */
export function categoryOptions(data: FinanceData, income: boolean | null, keep?: number | null): Category[] {
  const fits = (c: Category) => income === null || c.kind === 'BOTH' || c.kind === (income ? 'INCOME' : 'EXPENSE');
  const parents = data.categories.filter((c) => c.parentId === null);
  const result: Category[] = [];
  for (const p of parents) {
    if ((fits(p) && !p.archived) || p.id === keep) {
      result.push(p);
    }
    for (const c of data.categories.filter((x) => x.parentId === p.id)) {
      if ((fits(c) && !c.archived) || c.id === keep) {
        result.push(c);
      }
    }
  }
  return result;
}

export function CategorySelect({ data, income, value, onChange, id, emptyLabel = '— Sans catégorie —' }:
  { data: FinanceData; income: boolean | null; value: number | null; onChange: (id: number | null) => void;
    id?: string; emptyLabel?: string }) {
  return (
    <select id={id} value={value ?? ''} onChange={(e) => onChange(e.target.value ? Number(e.target.value) : null)}>
      <option value="">{emptyLabel}</option>
      {categoryOptions(data, income, value).map((c) => (
        <option key={c.id} value={c.id}>{categoryPath(data, c.id)}{c.archived ? ' (archivée)' : ''}</option>
      ))}
    </select>
  );
}

/** Saisie libre "vacances, travaux" avec les etiquettes existantes proposees en un clic. */
export function TagInput({ value, onChange, known, id }:
  { value: string; onChange: (v: string) => void; known: string[]; id?: string }) {
  const typed = new Set(value.split(',').map((t) => t.trim().toLowerCase()).filter(Boolean));
  const suggestions = known.filter((t) => !typed.has(t.toLowerCase())).slice(0, 8);
  return (
    <div>
      <input id={id} type="text" value={value} placeholder="Ex. vacances 2026, remboursable"
        onChange={(e) => onChange(e.target.value)} />
      {suggestions.length > 0 && (
        <div className="tag-suggestions">
          {suggestions.map((t) => (
            <button type="button" key={t} className="btn ghost small" onClick={() => {
              const text = value.trim();
              onChange(text === '' || text.endsWith(',') ? `${text}${text ? ' ' : ''}${t}` : `${text}, ${t}`);
            }}>+ {t}</button>
          ))}
        </div>
      )}
    </div>
  );
}

export function splitTags(text: string): string[] {
  return text.split(',').map((t) => t.trim()).filter(Boolean);
}

/** Telecharge un texte comme fichier (rien ne quitte l'ordinateur). */
export function download(fileName: string, content: string, type: string): void {
  const url = URL.createObjectURL(new Blob([content], { type }));
  const a = document.createElement('a');
  a.href = url;
  a.download = fileName;
  document.body.appendChild(a);
  a.click();
  a.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export function readFile(file: File): Promise<string> {
  return file.text();
}

export function Empty({ children }: { children: ReactNode }) {
  return <div className="empty">{children}</div>;
}
