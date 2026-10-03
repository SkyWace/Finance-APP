import { useEffect, useState } from 'react';
import * as session from '../store/session';
import { importBackup, listProfiles, MIN_PASSWORD_LENGTH, type ProfileSummary, WrongSecretError } from '../store/vault';
import { ErrorText, useAction } from './common';

type Mode = { kind: 'picker' } | { kind: 'unlock'; profile: ProfileSummary } | { kind: 'recover'; profile: ProfileSummary }
  | { kind: 'create' };

function message(e: unknown): string {
  if (e instanceof WrongSecretError) {
    return 'Mot de passe incorrect.';
  }
  return e instanceof Error ? e.message : String(e);
}

/** Ecran d'accueil : qui utilise FinanceApp ? puis mot de passe maitre. */
export function LockScreen({ onCreated }: { onCreated: (recoveryKey: string) => void }) {
  const [profiles, setProfiles] = useState<ProfileSummary[] | null>(null);
  const [mode, setMode] = useState<Mode>({ kind: 'picker' });
  const [error, run, setError] = useAction();

  const refresh = async () => {
    try {
      const list = await listProfiles();
      setProfiles(list);
      const preferredId = session.takePreferredProfile();
      const preferred = list.find((p) => p.id === preferredId);
      setMode(preferred ? { kind: 'unlock', profile: preferred } : list.length === 0 ? { kind: 'create' } : list.length === 1 ? { kind: 'unlock', profile: list[0] }
        : { kind: 'picker' });
    } catch {
      setProfiles([]);
      setError('Ce navigateur ne permet pas de stocker les données (navigation privée stricte ?). FinanceApp a besoin '
        + 'du stockage local pour fonctionner.');
    }
  };
  useEffect(() => {
    void refresh();
  }, []);

  const restore = () => {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = '.json,application/json';
    input.onchange = () => {
      const file = input.files?.[0];
      if (file) {
        void run(async () => {
          const added = await importBackup(await file.text());
          const list = await listProfiles();
          setProfiles(list);
          setMode({ kind: 'unlock', profile: added });
        });
      }
    };
    input.click();
  };

  if (profiles === null) {
    return <div className="lock-root"><p className="muted">Chargement…</p></div>;
  }
  return (
    <div className="lock-root">
      <main className="card lock-card">
        <div className="brand">FinanceApp</div>
        {mode.kind === 'picker' && <>
          <h1>Qui utilise FinanceApp ?</h1>
          <div className="profile-list">
            {profiles.map((p) => (
              <button key={p.id} onClick={() => { setError(''); setMode({ kind: 'unlock', profile: p }); }}>
                <span aria-hidden="true">◯</span>{p.name}</button>
            ))}
          </div>
          <div className="stack">
            <button className="btn" onClick={() => { setError(''); setMode({ kind: 'create' }); }}>+ Ajouter un utilisateur</button>
            <button className="btn" onClick={restore}>⇧ Importer une sauvegarde (desktop ou site)…</button>
          </div>
        </>}
        {mode.kind === 'unlock' && <Unlock profile={mode.profile} onRecover={() => { setError('');
          setMode({ kind: 'recover', profile: mode.profile }); }} onSwitch={() => { setError(''); setMode({ kind: 'picker' }); }}
        showSwitch={profiles.length > 1} onImport={restore} />}
        {mode.kind === 'recover' && <Recover profile={mode.profile} onBack={() => setMode({ kind: 'unlock', profile: mode.profile })} />}
        {mode.kind === 'create' && <Create first={profiles.length === 0} onCreated={onCreated}
          onBack={profiles.length > 0 ? () => setMode({ kind: 'picker' }) : undefined} onRestore={restore} />}
        <ErrorText text={error} />
        <p className="hint" style={{ marginTop: 18 }}>🔒 Vos données restent dans ce navigateur, chiffrées avec votre mot de
          passe. Rien n'est envoyé sur Internet.</p>
      </main>
    </div>
  );
}

function Unlock({ profile, onRecover, onSwitch, showSwitch, onImport }:
  { profile: ProfileSummary; onRecover: () => void; onSwitch: () => void; showSwitch: boolean; onImport: () => void }) {
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  return (
    <form onSubmit={(e) => {
      e.preventDefault();
      setBusy(true);
      setError('');
      session.unlock(profile.id, password).catch((err) => { setError(message(err)); setBusy(false); setPassword(''); });
    }}>
      <p className="muted">Utilisateur : {profile.name}</p>
      <h1>Application verrouillée</h1>
      <p>Saisissez votre mot de passe maître pour accéder à vos données.</p>
      <div className="stack">
        <label className="sr-only" htmlFor="unlock-pw">Mot de passe maître</label>
        <input id="unlock-pw" type="password" autoComplete="current-password" autoFocus placeholder="Mot de passe maître"
          value={password} onChange={(e) => setPassword(e.target.value)} />
        <ErrorText text={error} />
        <button className="btn primary" type="submit" disabled={busy || !password}>{busy ? 'Ouverture…' : 'Déverrouiller'}</button>
        <button type="button" className="link" onClick={onRecover}>Mot de passe oublié ? Utiliser la clé de récupération</button>
        {showSwitch ? <button type="button" className="link" onClick={onSwitch}>Changer d'utilisateur</button>
          : <button type="button" className="link" onClick={onSwitch}>Ajouter un utilisateur</button>}
        <button type="button" className="btn" onClick={onImport}>⇧ Importer une sauvegarde (desktop ou site)…</button>
      </div>
    </form>
  );
}

function Recover({ profile, onBack }: { profile: ProfileSummary; onBack: () => void }) {
  const [key, setKey] = useState('');
  const [password, setPassword] = useState('');
  const [again, setAgain] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  return (
    <form onSubmit={(e) => {
      e.preventDefault();
      if (password !== again) {
        setError('Les deux mots de passe ne sont pas identiques.');
        return;
      }
      setBusy(true);
      setError('');
      session.recover(profile.id, key, password).catch((err) => {
        setError(err instanceof WrongSecretError ? 'Clé de récupération incorrecte.' : message(err));
        setBusy(false);
      });
    }}>
      <p className="muted">Utilisateur : {profile.name}</p>
      <h1>Clé de récupération</h1>
      <p>Saisissez la clé remise à la création du profil, puis choisissez un nouveau mot de passe.</p>
      <div className="stack">
        <input type="text" aria-label="Clé de récupération" placeholder="XXXX-XXXX-XXXX-…" value={key} autoFocus
          onChange={(e) => setKey(e.target.value)} autoComplete="off" spellCheck={false} />
        <input type="password" aria-label="Nouveau mot de passe" placeholder="Nouveau mot de passe" value={password}
          autoComplete="new-password" onChange={(e) => setPassword(e.target.value)} />
        <input type="password" aria-label="Confirmation" placeholder="Confirmation" value={again}
          autoComplete="new-password" onChange={(e) => setAgain(e.target.value)} />
        <ErrorText text={error} />
        <button className="btn primary" type="submit" disabled={busy}>{busy ? 'Vérification…' : 'Récupérer mon accès'}</button>
        <button type="button" className="link" onClick={onBack}>Retour</button>
      </div>
    </form>
  );
}

function Create({ first, onCreated, onBack, onRestore }:
  { first: boolean; onCreated: (key: string) => void; onBack?: () => void; onRestore: () => void }) {
  const [name, setName] = useState(first ? 'Profil principal' : '');
  const [password, setPassword] = useState('');
  const [again, setAgain] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  return (
    <form onSubmit={(e) => {
      e.preventDefault();
      if (password !== again) {
        setError('Les deux mots de passe ne sont pas identiques.');
        return;
      }
      setBusy(true);
      setError('');
      session.createAndOpen(name, password).then(onCreated).catch((err) => { setError(message(err)); setBusy(false); });
    }}>
      <h1>{first ? 'Bienvenue' : 'Nouvel utilisateur'}</h1>
      <p>{first ? 'Créez votre profil. ' : ''}Choisissez un mot de passe maître : il chiffre vos données dans ce
        navigateur. Il n'est stocké nulle part.</p>
      <div className="stack">
        <input type="text" aria-label="Nom" placeholder="Nom (ex. Alice)" value={name} onChange={(e) => setName(e.target.value)}
          maxLength={40} />
        <input type="password" aria-label="Mot de passe maître" placeholder={`Mot de passe maître (${MIN_PASSWORD_LENGTH} caractères au moins)`}
          value={password} autoComplete="new-password" onChange={(e) => setPassword(e.target.value)} />
        <input type="password" aria-label="Confirmation" placeholder="Confirmation" value={again} autoComplete="new-password"
          onChange={(e) => setAgain(e.target.value)} />
        <ErrorText text={error} />
        <button className="btn primary" type="submit" disabled={busy}>{busy ? 'Création…' : 'Créer le profil'}</button>
        {onBack && <button type="button" className="link" onClick={onBack}>Retour</button>}
        <button type="button" className="btn" onClick={onRestore}>⇧ Importer une sauvegarde (desktop ou site)…</button>
      </div>
    </form>
  );
}

/** Cle de recuperation montree une seule fois apres la creation du profil. */
export function RecoveryKeyScreen({ recoveryKey, onDone }: { recoveryKey: string; onDone: () => void }) {
  const [confirmed, setConfirmed] = useState(false);
  return (
    <div className="lock-root">
      <main className="card lock-card">
        <div className="brand">FinanceApp</div>
        <h1>Votre clé de récupération</h1>
        <p>Si vous oubliez votre mot de passe, cette clé est <strong>le seul moyen</strong> de retrouver vos données.
          Notez-la sur papier ou dans un gestionnaire de mots de passe. Elle ne sera plus jamais affichée.</p>
        <div className="recovery-key" aria-label="Clé de récupération">{recoveryKey}</div>
        <div className="stack">
          <button className="btn" onClick={() => navigator.clipboard?.writeText(recoveryKey)}>Copier la clé</button>
          <label className="check"><input type="checkbox" checked={confirmed} onChange={(e) => setConfirmed(e.target.checked)} />
            J'ai noté ma clé de récupération en lieu sûr</label>
          <button className="btn primary" disabled={!confirmed} onClick={onDone}>Continuer</button>
        </div>
      </main>
    </div>
  );
}
