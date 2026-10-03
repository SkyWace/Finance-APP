import { useState } from 'react';
import { formatLongDate, type IsoDate } from '../../domain/dates';
import { parseAmount, toEditable, type Cents } from '../../domain/money';
import {
  allTags, confirmOccurrence, createTransfer, deleteTransaction, saveAccount, saveRule, saveTransaction, transferLegsOf, updateTransfer,
} from '../../domain/operations';
import { isCustom } from '../../domain/recurrence';
import {
  ACCOUNT_TYPES, FREQUENCY_LABELS, STATUS_LABELS, type Account, type AccountType, type Frequency, type PlannedItem,
  type RecurringRule, type Transaction, type TransactionStatus, type TransactionType,
} from '../../domain/types';
import { mutate, useData } from '../../store/session';
import {
  AccountSelect, CategorySelect, Dialog, ErrorText, TagInput, splitTags, useAction, useToday,
} from '../common';

function amountOrError(text: string, allowZeroOrNegative = false): Cents {
  const value = parseAmount(text);
  if (value === null || (!allowZeroOrNegative && value <= 0)) {
    throw new Error(allowZeroOrNegative ? 'Montant invalide' : 'Le montant doit être un nombre positif (ex. 12,50)');
  }
  return value;
}

// ---------------------------------------------------------------- operation

/**
 * Nouvelle operation ou modification. Un virement existant s'ouvre en mode virement
 * (ses deux jambes sont modifiees ensemble).
 */
export function TransactionDialog({ existing, initialType = 'EXPENSE', onClose }:
  { existing?: Transaction; initialType?: TransactionType; onClose: () => void }) {
  const remove = () => {
    if (!existing) {
      return;
    }
    const what = existing.type === 'TRANSFER' ? 'ce virement (ses deux opérations)' : 'cette opération';
    if (window.confirm(`Supprimer définitivement ${what} ?`)) {
      mutate((d) => deleteTransaction(d, existing.id));
      onClose();
    }
  };
  const data = useData();
  const today = useToday();
  const legs = existing?.transferGroup ? transferLegsOf(data, existing.transferGroup) : [];
  const [type, setType] = useState<TransactionType>(existing?.type ?? initialType);
  const firstAccount = data.accounts.find((a) => !a.archived)?.id;
  const [accountId, setAccountId] = useState<number | undefined>(legs[0]?.accountId ?? existing?.accountId ?? firstAccount);
  const [toAccountId, setToAccountId] = useState<number | undefined>(legs[1]?.accountId);
  const [date, setDate] = useState<IsoDate>(existing?.date ?? today);
  const [label, setLabel] = useState(existing?.label ?? '');
  const [amount, setAmount] = useState(existing ? toEditable(Math.abs(existing.amount)) : '');
  const [categoryId, setCategoryId] = useState<number | null>(existing?.categoryId ?? null);
  const [status, setStatus] = useState<TransactionStatus>(existing?.status ?? 'COMPLETED');
  const [statusTouched, setStatusTouched] = useState(!!existing);
  const [note, setNote] = useState(existing?.note ?? '');
  const [tags, setTags] = useState((existing?.tags ?? []).join(', '));
  const [error, run] = useAction();
  const transfer = type === 'TRANSFER';

  const onDate = (d: string) => {
    setDate(d);
    // Une operation datee dans le futur est "prevue" par defaut.
    if (!statusTouched) {
      setStatus(d > today ? 'PLANNED' : 'COMPLETED');
    }
  };

  const submit = () => run(() => {
    const cents = amountOrError(amount);
    mutate((d) => {
      if (transfer) {
        const draft = { fromAccountId: accountId!, toAccountId: toAccountId!, date, label, amount: cents, status,
          note };
        if (existing?.transferGroup) {
          updateTransfer(d, existing.transferGroup, draft);
        } else {
          createTransfer(d, draft);
        }
      } else {
        saveTransaction(d, { accountId: accountId!, date, label, amount: cents, type: type as 'EXPENSE' | 'INCOME',
          status, categoryId, note, tags: splitTags(tags) }, existing?.id);
      }
    });
    onClose();
  });

  const title = existing ? (transfer ? 'Modifier le virement' : "Modifier l'opération")
    : transfer ? 'Nouveau virement' : 'Nouvelle opération';
  return (
    <Dialog title={title} onClose={onClose}>
      <form className="form" onSubmit={(e) => { e.preventDefault(); void submit(); }}>
        <label>Type</label>
        <div className="segmented" role="group" aria-label="Type d'opération">
          {(['EXPENSE', 'INCOME', 'TRANSFER'] as const)
            .filter((t) => !existing || (t === 'TRANSFER') === (existing.type === 'TRANSFER'))
            .map((t) => (
              <button type="button" key={t} aria-pressed={type === t} onClick={() => setType(t)}>
                {t === 'EXPENSE' ? 'Dépense' : t === 'INCOME' ? 'Revenu' : 'Virement'}
              </button>
            ))}
        </div>
        <label htmlFor="tx-account">{transfer ? 'Depuis le compte' : 'Compte'}</label>
        <AccountSelect id="tx-account" accounts={data.accounts} value={accountId} onChange={setAccountId} />
        {transfer && <>
          <label htmlFor="tx-to">Vers le compte</label>
          <AccountSelect id="tx-to" accounts={data.accounts} value={toAccountId} onChange={setToAccountId}
            exclude={accountId} emptyLabel="— Choisir —" />
        </>}
        <label htmlFor="tx-date">Date</label>
        <input id="tx-date" type="date" value={date} onChange={(e) => onDate(e.target.value)} required />
        <label htmlFor="tx-label">Libellé</label>
        <input id="tx-label" type="text" value={label} onChange={(e) => setLabel(e.target.value)}
          placeholder={transfer ? 'Ex. Épargne mensuelle' : 'Ex. Carrefour'} maxLength={200} />
        <label htmlFor="tx-amount">Montant (€)</label>
        <input id="tx-amount" type="text" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)}
          placeholder="0,00" />
        {!transfer && <>
          <label htmlFor="tx-category">Catégorie</label>
          <CategorySelect id="tx-category" data={data} income={type === 'INCOME'} value={categoryId}
            onChange={setCategoryId} />
        </>}
        <label htmlFor="tx-status">Statut</label>
        <select id="tx-status" value={status} onChange={(e) => { setStatus(e.target.value as TransactionStatus);
          setStatusTouched(true); }}>
          {Object.entries(STATUS_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
        </select>
        {!transfer && <>
          <label htmlFor="tx-tags">Étiquettes</label>
          <TagInput id="tx-tags" value={tags} onChange={setTags} known={allTags(data)} />
        </>}
        <label htmlFor="tx-note">Commentaire</label>
        <textarea id="tx-note" value={note} onChange={(e) => setNote(e.target.value)} maxLength={1000} />
        <ErrorText text={error} />
        <div className="buttons full">
          {existing && <button type="button" className="btn danger" onClick={remove} style={{ marginRight: 'auto' }}>
            Supprimer</button>}
          <button type="button" className="btn ghost" onClick={onClose}>Annuler</button>
          <button type="submit" className="btn primary">Enregistrer</button>
        </div>
      </form>
    </Dialog>
  );
}

// ---------------------------------------------------------------- compte

export function AccountDialog({ existing, savings = false, onClose }:
  { existing?: Account; savings?: boolean; onClose: () => void }) {
  const today = useToday();
  const [name, setName] = useState(existing?.name ?? '');
  const [type, setType] = useState<AccountType>(existing?.type ?? (savings ? 'LIVRET_A' : 'CHECKING'));
  const [balance, setBalance] = useState(existing ? toEditable(existing.initialBalance) : '0,00');
  const [opening, setOpening] = useState<IsoDate>(existing?.openingDate ?? today);
  const [include, setInclude] = useState(existing?.includeInAvailable ?? ACCOUNT_TYPES[type].includedByDefault);
  const [includeTouched, setIncludeTouched] = useState(!!existing);
  const [error, run] = useAction();
  const types = (Object.keys(ACCOUNT_TYPES) as AccountType[])
    .filter((t) => !savings || ACCOUNT_TYPES[t].group === 'SAVINGS' || t === existing?.type);

  const submit = () => run(() => {
    const initialBalance = amountOrError(balance, true);
    mutate((d) => saveAccount(d, { name, type, initialBalance, openingDate: opening, includeInAvailable: include },
      existing?.id));
    onClose();
  });
  return (
    <Dialog title={existing ? 'Modifier le compte' : savings ? 'Ajouter une épargne' : 'Nouveau compte'} onClose={onClose}>
      <form className="form" onSubmit={(e) => { e.preventDefault(); void submit(); }}>
        <label htmlFor="ac-name">Nom</label>
        <input id="ac-name" type="text" value={name} onChange={(e) => setName(e.target.value)} maxLength={200}
          placeholder={savings ? 'Ex. Livret A' : 'Ex. Compte courant'} />
        <label htmlFor="ac-type">Type</label>
        <select id="ac-type" value={type} onChange={(e) => {
          const t = e.target.value as AccountType;
          setType(t);
          if (!includeTouched) {
            setInclude(ACCOUNT_TYPES[t].includedByDefault);
          }
        }}>
          {types.map((t) => <option key={t} value={t}>{ACCOUNT_TYPES[t].label}</option>)}
        </select>
        <label htmlFor="ac-balance">{savings && !existing ? 'Valeur actuelle (€)' : 'Solde initial (€)'}</label>
        <input id="ac-balance" type="text" inputMode="decimal" value={balance} onChange={(e) => setBalance(e.target.value)} />
        <label htmlFor="ac-opening">{savings && !existing ? 'Valeur au' : "Date d'ouverture"}</label>
        <input id="ac-opening" type="date" value={opening} onChange={(e) => setOpening(e.target.value)} />
        <label className="check full">
          <input type="checkbox" checked={include} onChange={(e) => { setInclude(e.target.checked); setIncludeTouched(true); }} />
          <span>Inclus dans le disponible réel et les prévisions
            <span className="hint"> — en général : comptes courants et espèces, pas l'épargne</span></span>
        </label>
        <ErrorText text={error} />
        <div className="buttons full">
          <button type="button" className="btn ghost" onClick={onClose}>Annuler</button>
          <button type="submit" className="btn primary">Enregistrer</button>
        </div>
      </form>
    </Dialog>
  );
}

// ---------------------------------------------------------------- recurrence

export function RuleDialog({ existing, onClose }: { existing?: RecurringRule; onClose: () => void }) {
  const data = useData();
  const today = useToday();
  const [type, setType] = useState<TransactionType>(existing?.type ?? 'EXPENSE');
  const [accountId, setAccountId] = useState<number | undefined>(existing?.accountId
    ?? data.accounts.find((a) => !a.archived)?.id);
  const [toAccountId, setToAccountId] = useState<number | undefined>(existing?.toAccountId);
  const [label, setLabel] = useState(existing?.label ?? '');
  const [amount, setAmount] = useState(existing ? toEditable(existing.amount) : '');
  const [categoryId, setCategoryId] = useState<number | null>(existing?.categoryId ?? null);
  const [frequency, setFrequency] = useState<Frequency>(existing?.frequency ?? 'MONTHLY');
  const [interval, setInterval] = useState(String(existing?.interval ?? 1));
  const [start, setStart] = useState<IsoDate>(existing?.startDate ?? today);
  const [end, setEnd] = useState<IsoDate>(existing?.endDate ?? '');
  const [certain, setCertain] = useState(existing?.certain ?? true);
  const [active, setActive] = useState(existing?.active ?? true);
  const [note, setNote] = useState(existing?.note ?? '');
  const [tags, setTags] = useState((existing?.tags ?? []).join(', '));
  const [error, run] = useAction();
  const transfer = type === 'TRANSFER';

  const submit = () => run(() => {
    const cents = amountOrError(amount);
    mutate((d) => saveRule(d, {
      accountId: accountId!, toAccountId, type, label, amount: cents, categoryId, frequency,
      interval: Number(interval), startDate: start, endDate: end || undefined, certain, active, note,
      tags: splitTags(tags),
    }, today, existing?.id));
    onClose();
  });
  return (
    <Dialog title={existing ? 'Modifier la récurrence' : 'Nouvelle opération récurrente'} onClose={onClose}>
      <form className="form" onSubmit={(e) => { e.preventDefault(); void submit(); }}>
        <label htmlFor="ru-type">Type</label>
        <select id="ru-type" value={type} onChange={(e) => setType(e.target.value as TransactionType)}>
          <option value="EXPENSE">Dépense</option>
          <option value="INCOME">Revenu</option>
          <option value="TRANSFER">Virement interne (ex. épargne)</option>
        </select>
        <label htmlFor="ru-account">{transfer ? 'Depuis le compte' : 'Compte'}</label>
        <AccountSelect id="ru-account" accounts={data.accounts} value={accountId} onChange={setAccountId} />
        {transfer && <>
          <label htmlFor="ru-to">Vers le compte</label>
          <AccountSelect id="ru-to" accounts={data.accounts} value={toAccountId} onChange={setToAccountId}
            exclude={accountId} emptyLabel="— Choisir —" />
        </>}
        <label htmlFor="ru-label">Libellé</label>
        <input id="ru-label" type="text" value={label} onChange={(e) => setLabel(e.target.value)} maxLength={200}
          placeholder="Ex. Loyer" />
        <label htmlFor="ru-amount">Montant (€)</label>
        <input id="ru-amount" type="text" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)}
          placeholder="0,00" />
        {!transfer && <>
          <label htmlFor="ru-category">Catégorie</label>
          <CategorySelect id="ru-category" data={data} income={type === 'INCOME'} value={categoryId}
            onChange={setCategoryId} />
        </>}
        <label htmlFor="ru-frequency">Fréquence</label>
        <select id="ru-frequency" value={frequency} onChange={(e) => setFrequency(e.target.value as Frequency)}>
          {Object.entries(FREQUENCY_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
        </select>
        {isCustom(frequency) && <>
          <label htmlFor="ru-interval">Intervalle (N)</label>
          <input id="ru-interval" type="number" min={1} max={365} value={interval}
            onChange={(e) => setInterval(e.target.value)} />
        </>}
        <label htmlFor="ru-start">Première échéance</label>
        <input id="ru-start" type="date" value={start} onChange={(e) => setStart(e.target.value)} />
        <label htmlFor="ru-end">Dernière échéance</label>
        <input id="ru-end" type="date" value={end} onChange={(e) => setEnd(e.target.value)} aria-describedby="ru-end-hint" />
        {!transfer && <>
          <label htmlFor="ru-tags">Étiquettes</label>
          <TagInput id="ru-tags" value={tags} onChange={setTags} known={allTags(data)} />
        </>}
        <label htmlFor="ru-note">Commentaire</label>
        <input id="ru-note" type="text" value={note} onChange={(e) => setNote(e.target.value)} maxLength={1000} />
        <span id="ru-end-hint" className="hint full">Dernière échéance vide : sans fin.</span>
        {type === 'INCOME' && (
          <label className="check full">
            <input type="checkbox" checked={certain} onChange={(e) => setCertain(e.target.checked)} />
            <span>Revenu suffisamment certain pour être compté dans le disponible réel</span>
          </label>
        )}
        <label className="check full">
          <input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} />
          <span>Récurrence active</span>
        </label>
        <ErrorText text={error} />
        <div className="buttons full">
          <button type="button" className="btn ghost" onClick={onClose}>Annuler</button>
          <button type="submit" className="btn primary">Enregistrer</button>
        </div>
      </form>
    </Dialog>
  );
}

// ---------------------------------------------------------------- validation d'une occurrence

export function ConfirmOccurrenceDialog({ item, onClose }: { item: PlannedItem; onClose: () => void }) {
  const today = useToday();
  const [date, setDate] = useState<IsoDate>(item.date > today ? item.date : today);
  const [amount, setAmount] = useState(toEditable(Math.abs(item.amount)));
  const [error, run] = useAction();
  const submit = () => run(() => {
    const cents = amountOrError(amount);
    mutate((d) => confirmOccurrence(d, item.ruleId!, item.date, date, cents));
    onClose();
  });
  return (
    <Dialog title={`Valider « ${item.label} »`} onClose={onClose}>
      <form className="form" onSubmit={(e) => { e.preventDefault(); void submit(); }}>
        <p className="full muted">Échéance prévue le {formatLongDate(item.date)}. Indiquez la date et le montant réels.</p>
        <label htmlFor="oc-date">Date réelle</label>
        <input id="oc-date" type="date" value={date} onChange={(e) => setDate(e.target.value)} />
        <label htmlFor="oc-amount">Montant réel (€)</label>
        <input id="oc-amount" type="text" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} />
        <ErrorText text={error} />
        <div className="buttons full">
          <button type="button" className="btn ghost" onClick={onClose}>Annuler</button>
          <button type="submit" className="btn primary">Valider</button>
        </div>
      </form>
    </Dialog>
  );
}
