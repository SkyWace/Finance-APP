import { useEffect, useState } from 'react';
import { isDuplicate, parseCsv, type CsvParseResult } from '../../domain/csv';
import { formatDate, todayLocal } from '../../domain/dates';
import { HORIZON_LABELS, type HorizonType } from '../../domain/types';
import { saveTransaction } from '../../domain/operations';
import { deleteCurrent, flush, mutate, renameCurrent, useData, useSession } from '../../store/session';
import { changePassword, exportBackup, MIN_PASSWORD_LENGTH, renewRecoveryKey } from '../../store/vault';
import { AccountSelect, Dialog, ErrorText, Money, download, useAction } from '../common';
import { getTheme, setTheme, type ThemeChoice } from '../theme';

function PasswordDialog({ onClose }: { onClose: () => void }) {
  const session = useSession();
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [again, setAgain] = useState('');
  const [done, setDone] = useState(false);
  const [error, run] = useAction();
  return (
    <Dialog title="Changer le mot de passe maître" onClose={onClose}>
      {done ? <><p>Mot de passe modifié. Votre clé de récupération reste valable.</p>
        <div className="buttons"><button className="btn primary" onClick={onClose}>Fermer</button></div></> : (
        <form className="form" onSubmit={(e) => {
          e.preventDefault();
          void run(async () => {
            if (next !== again) {
              throw new Error('Les deux nouveaux mots de passe ne sont pas identiques');
            }
            await changePassword(session.profile!.id, current, next);
            setDone(true);
          });
        }}>
          <label htmlFor="pw-current">Mot de passe actuel</label>
          <input id="pw-current" type="password" autoComplete="current-password" value={current}
            onChange={(e) => setCurrent(e.target.value)} />
          <label htmlFor="pw-next">Nouveau mot de passe</label>
          <input id="pw-next" type="password" autoComplete="new-password" value={next} onChange={(e) => setNext(e.target.value)} />
          <label htmlFor="pw-again">Confirmation</label>
          <input id="pw-again" type="password" autoComplete="new-password" value={again} onChange={(e) => setAgain(e.target.value)} />
          <span className="hint full">Au moins {MIN_PASSWORD_LENGTH} caractères. Une phrase facile à retenir est idéale.</span>
          <ErrorText text={error} />
          <div className="buttons full">
            <button type="button" className="btn ghost" onClick={onClose}>Annuler</button>
            <button type="submit" className="btn primary">Changer</button>
          </div>
        </form>
      )}
    </Dialog>
  );
}

function RecoveryDialog({ onClose }: { onClose: () => void }) {
  const session = useSession();
  const [password, setPassword] = useState('');
  const [key, setKey] = useState('');
  const [error, run] = useAction();
  return (
    <Dialog title="Nouvelle clé de récupération" onClose={onClose}>
      {key ? <>
        <p>Votre nouvelle clé (l'ancienne ne fonctionne plus) :</p>
        <div className="recovery-key">{key}</div>
        <p className="hint">Notez-la et rangez-la en lieu sûr : c'est le seul recours si vous oubliez votre mot de passe.
          Elle ne sera plus jamais affichée.</p>
        <div className="buttons"><button className="btn primary" onClick={onClose}>Je l'ai notée</button></div>
      </> : (
        <form className="form" onSubmit={(e) => {
          e.preventDefault();
          void run(async () => setKey(await renewRecoveryKey(session.profile!.id, password)));
        }}>
          <label htmlFor="rk-pw">Mot de passe maître</label>
          <input id="rk-pw" type="password" autoComplete="current-password" value={password}
            onChange={(e) => setPassword(e.target.value)} />
          <ErrorText text={error} />
          <div className="buttons full">
            <button type="button" className="btn ghost" onClick={onClose}>Annuler</button>
            <button type="submit" className="btn primary">Générer</button>
          </div>
        </form>
      )}
    </Dialog>
  );
}

function DeleteProfileDialog({ onClose }: { onClose: () => void }) {
  const session = useSession();
  const [password, setPassword] = useState('');
  const [error, run] = useAction();
  return (
    <Dialog title={`Supprimer le profil « ${session.profile?.name} »`} onClose={onClose}>
      <form className="form" onSubmit={(e) => {
        e.preventDefault();
        void run(() => deleteCurrent(password));
      }}>
        <p className="full">Toutes les données de ce profil seront <strong>définitivement effacées de ce navigateur</strong>.
          Les sauvegardes que vous avez téléchargées ne sont pas touchées.</p>
        <label htmlFor="del-pw">Mot de passe maître</label>
        <input id="del-pw" type="password" autoComplete="current-password" value={password}
          onChange={(e) => setPassword(e.target.value)} />
        <ErrorText text={error} />
        <div className="buttons full">
          <button type="button" className="btn ghost" onClick={onClose}>Annuler</button>
          <button type="submit" className="btn danger">Supprimer définitivement</button>
        </div>
      </form>
    </Dialog>
  );
}

function CsvImportDialog({ onClose }: { onClose: () => void }) {
  const data = useData();
  const [accountId, setAccountId] = useState<number | undefined>(data.accounts.find((a) => !a.archived)?.id);
  const [parsed, setParsed] = useState<CsvParseResult | null>(null);
  const [done, setDone] = useState<number | null>(null);
  const [error, run] = useAction();
  const fresh = parsed && accountId !== undefined ? parsed.rows.filter((r) => !isDuplicate(data, accountId, r)) : [];
  const duplicates = parsed ? parsed.rows.length - fresh.length : 0;
  return (
    <Dialog title="Importer un relevé (CSV)" onClose={onClose} wide>
      {done !== null ? <><p>{done} opération(s) importée(s) comme « effectuées », sans catégorie : complétez-les dans
        Transactions.</p><div className="buttons"><button className="btn primary" onClick={onClose}>Fermer</button></div></> : (
        <div className="form">
          <label htmlFor="csv-account">Compte</label>
          <AccountSelect id="csv-account" accounts={data.accounts} value={accountId} onChange={setAccountId} />
          <label htmlFor="csv-file">Fichier</label>
          <input id="csv-file" type="file" accept=".csv,text/csv,text/plain" onChange={(e) => {
            const file = e.target.files?.[0];
            if (file) {
              void run(async () => {
                if (file.size > 5_000_000) {
                  throw new Error('Fichier trop volumineux (5 Mo au plus)');
                }
                setParsed(parseCsv(await file.text()));
              });
            }
          }} />
          <p className="hint full">Colonnes attendues : Date ; Libellé ; Montant (ou Débit ; Crédit). Les dates au format
            31/10/2026 ou 2026-10-31 ; les montants négatifs sont des dépenses.</p>
          {parsed && <div className="full">
            <p><strong>{fresh.length}</strong> nouvelle(s) opération(s) · {duplicates} déjà présente(s), ignorée(s) ·{' '}
              {parsed.rejected.length} ligne(s) illisible(s), ignorée(s)</p>
            {parsed.rejected.length > 0 && <p className="hint">Lignes ignorées : {parsed.rejected.slice(0, 10)
              .map((r) => `${r.line} (${r.reason})`).join(', ')}{parsed.rejected.length > 10 ? '…' : ''}</p>}
            <div className="table-wrap" style={{ maxHeight: 260, overflowY: 'auto' }}>
              <table className="data"><tbody>
                {fresh.slice(0, 100).map((r, i) => <tr key={i}><td>{formatDate(r.date)}</td><td>{r.label}</td>
                  <td className="num"><Money cents={r.amount} signed tone /></td></tr>)}
              </tbody></table>
            </div>
          </div>}
          <ErrorText text={error} />
          <div className="buttons full">
            <button className="btn ghost" onClick={onClose}>Annuler</button>
            <button className="btn primary" disabled={!fresh.length} onClick={() => run(() => {
              mutate((d) => fresh.forEach((r) => saveTransaction(d, { accountId: accountId!, date: r.date, label: r.label,
                amount: Math.abs(r.amount), type: r.amount < 0 ? 'EXPENSE' : 'INCOME', status: 'COMPLETED',
                categoryId: null, tags: [] })));
              setDone(fresh.length);
            })}>Importer {fresh.length} opération(s)</button>
          </div>
        </div>
      )}
    </Dialog>
  );
}

export function SettingsPage() {
  const data = useData();
  const session = useSession();
  const [dialog, setDialog] = useState<'password' | 'recovery' | 'delete' | 'csv' | null>(null);
  const [name, setName] = useState(session.profile?.name ?? '');
  const [theme, setThemeState] = useState<ThemeChoice>(getTheme());
  const [persisted, setPersisted] = useState<boolean | null>(null);
  const [usage, setUsage] = useState<string>('');
  const [error, run] = useAction();
  useEffect(() => {
    navigator.storage?.persisted?.().then(setPersisted).catch(() => setPersisted(null));
    navigator.storage?.estimate?.().then((e) => setUsage(e.usage ? `${(e.usage / 1_048_576).toFixed(1)} Mo utilisés`
      : '')).catch(() => {});
  }, []);
  const set = (change: (s: typeof data.settings) => void) => mutate((d) => change(d.settings));
  return (
    <>
      <section className="card section">
        <h2>Utilisateur</h2>
        <div className="form">
          <label htmlFor="st-name">Nom du profil</label>
          <div className="row">
            <input id="st-name" type="text" value={name} onChange={(e) => setName(e.target.value)} style={{ maxWidth: 280 }} />
            <button className="btn" onClick={() => run(() => renameCurrent(name))}>Renommer</button>
          </div>
          <ErrorText text={error} />
          <div className="row full">
            <button className="btn" onClick={() => setDialog('password')}>Changer le mot de passe…</button>
            <button className="btn" onClick={() => setDialog('recovery')}>Nouvelle clé de récupération…</button>
            <button className="btn danger" onClick={() => setDialog('delete')}>Supprimer ce profil…</button>
          </div>
        </div>
      </section>
      <section className="card section">
        <h2>Affichage et sécurité</h2>
        <div className="form">
          <label htmlFor="st-theme">Thème</label>
          <select id="st-theme" value={theme} onChange={(e) => { const t = e.target.value as ThemeChoice; setTheme(t);
            setThemeState(t); }} style={{ maxWidth: 280 }}>
            <option value="system">Comme le système</option>
            <option value="dark">Sombre « Nuit & Saphir »</option>
            <option value="light">Clair « Lin & Prune »</option>
          </select>
          <label className="check full">
            <input type="checkbox" checked={data.settings.privacy} onChange={(e) => set((s) => { s.privacy = e.target.checked; })} />
            Masquer tous les montants (mode confidentialité)
          </label>
          <label htmlFor="st-lock">Verrouillage automatique</label>
          <select id="st-lock" value={data.settings.autoLockMinutes} style={{ maxWidth: 280 }}
            onChange={(e) => set((s) => { s.autoLockMinutes = Number(e.target.value); })}>
            {[1, 2, 5, 10, 15, 30, 60].map((m) => <option key={m} value={m}>après {m} min d'inactivité</option>)}
            <option value={0}>jamais (déconseillé)</option>
          </select>
          <label htmlFor="st-horizon">Échéance du disponible</label>
          <select id="st-horizon" value={data.settings.defaultHorizon} style={{ maxWidth: 280 }}
            onChange={(e) => set((s) => { s.defaultHorizon = e.target.value as HorizonType; })}>
            {Object.entries(HORIZON_LABELS).filter(([k]) => k !== 'CUSTOM_DATE')
              .map(([k, v]) => <option key={k} value={k}>{v}</option>)}
          </select>
        </div>
      </section>
      <section className="card section">
        <h2>Sauvegardes</h2>
        <p>Vos données sont stockées <strong>uniquement dans ce navigateur</strong>, chiffrées avec votre mot de passe.
          Téléchargez régulièrement une sauvegarde : si le navigateur est réinitialisé ou si vous changez d'ordinateur,
          c'est elle qui vous permettra de tout retrouver (écran d'accueil → « Restaurer une sauvegarde »).</p>
        <div className="row">
          <button className="btn primary" onClick={() => run(async () => {
            await flush();
            download(`financeapp-sauvegarde-${todayLocal()}.json`, await exportBackup(session.profile!.id),
              'application/json');
          })}>⇩ Télécharger une sauvegarde chiffrée</button>
        </div>
        <p className="hint">Le fichier est chiffré : il ne s'ouvre qu'avec votre mot de passe (ou votre clé de
          récupération). {persisted === false && 'Le navigateur n\'a pas garanti la conservation de ces données en cas de '
          + 'manque de place : les sauvegardes sont d\'autant plus importantes.'} {usage}</p>
      </section>
      <section className="card section">
        <h2>Import</h2>
        <p>Ajoutez les opérations d'un relevé bancaire exporté en CSV. Un aperçu est montré avant tout import ; les
          lignes déjà présentes ne sont pas réimportées.</p>
        <button className="btn" disabled={data.accounts.length === 0} onClick={() => setDialog('csv')}>Importer un relevé
          CSV…</button>
      </section>
      <section className="card section">
        <h2>À propos</h2>
        <p className="muted">FinanceApp web · aucune donnée financière n'est envoyée sur Internet : tout est calculé et
          stocké dans ce navigateur. Aucun compte en ligne, aucune publicité, aucun suivi.</p>
      </section>
      {dialog === 'password' && <PasswordDialog onClose={() => setDialog(null)} />}
      {dialog === 'recovery' && <RecoveryDialog onClose={() => setDialog(null)} />}
      {dialog === 'delete' && <DeleteProfileDialog onClose={() => setDialog(null)} />}
      {dialog === 'csv' && <CsvImportDialog onClose={() => setDialog(null)} />}
    </>
  );
}
