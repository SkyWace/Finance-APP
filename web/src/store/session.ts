import { useSyncExternalStore } from 'react';
import { emptyData } from '../domain/defaults';
import type { FinanceData } from '../domain/types';
import * as vault from './vault';

/**
 * Session de l'application : profil ouvert, donnees en memoire, enregistrement
 * chiffre automatique apres chaque modification, verrouillage.
 */
export interface SessionState {
  profile?: { id: string; name: string };
  data?: FinanceData;
  /** Derniere erreur d'enregistrement (affichee, jamais ignoree). */
  saveError?: string;
  saving: boolean;
}

type Listener = () => void;

let state: SessionState = { saving: false };
let current: vault.OpenVault<FinanceData> | undefined;
let releaseTabLock: (() => void) | undefined;
let saveChain: Promise<void> = Promise.resolve();
const listeners = new Set<Listener>();

function set(next: Partial<SessionState>): void {
  state = { ...state, ...next };
  listeners.forEach((l) => l());
}

export function getState(): SessionState {
  return state;
}

export function subscribe(listener: Listener): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function useSession(): SessionState {
  return useSyncExternalStore(subscribe, getState);
}

/** Donnees ouvertes (l'ecran principal n'existe que deverrouille). */
export function useData(): FinanceData {
  const s = useSession();
  if (!s.data) {
    throw new Error('Profil verrouillé');
  }
  return s.data;
}

/** Complete les donnees d'une version anterieure. */
function migrate(data: FinanceData): FinanceData {
  const base = emptyData();
  return {
    ...data,
    settings: { ...base.settings, ...data.settings },
    rules: data.rules.map((r) => ({ ...r, tags: r.tags ?? [] })),
    transactions: data.transactions.map((t) => ({ ...t, tags: t.tags ?? [] })),
  };
}

/**
 * Un profil ne s'ouvre que dans un seul onglet a la fois (deux onglets ecriraient
 * l'un sur l'autre). Web Locks : liberes automatiquement a la fermeture de l'onglet.
 */
async function acquireTabLock(id: string): Promise<boolean> {
  if (!navigator.locks) {
    return true;
  }
  return new Promise<boolean>((resolve) => {
    navigator.locks.request(`financeapp-profile-${id}`, { ifAvailable: true }, (lock) => {
      if (!lock) {
        resolve(false);
        return undefined;
      }
      resolve(true);
      return new Promise<void>((release) => {
        releaseTabLock = release;
      });
    });
  });
}

/** Profil a proposer a l'ecran d'accueil (ex. juste importe depuis les Parametres). */
let preferredProfileId: string | undefined;

export function preferProfile(id: string): void {
  preferredProfileId = id;
}

export function takePreferredProfile(): string | undefined {
  const id = preferredProfileId;
  preferredProfileId = undefined;
  return id;
}

export class AlreadyOpenError extends Error {
  constructor() {
    super('Ce profil est déjà ouvert dans un autre onglet ou une autre fenêtre de ce navigateur. Fermez-le, puis '
      + 'réessayez : deux onglets ne peuvent pas modifier les mêmes données en même temps.');
  }
}

async function enter(opened: vault.OpenVault<FinanceData>): Promise<void> {
  if (!(await acquireTabLock(opened.id))) {
    throw new AlreadyOpenError();
  }
  current = { ...opened, data: migrate(opened.data) };
  set({ profile: { id: opened.id, name: opened.name }, data: current.data, saveError: undefined });
  void vault.requestPersistence();
}

export async function createAndOpen(name: string, password: string): Promise<string> {
  const { vault: opened, recoveryKey } = await vault.createProfile(name, password, emptyData());
  await enter(opened);
  return recoveryKey;
}

export async function unlock(id: string, password: string): Promise<void> {
  await enter(await vault.unlock<FinanceData>(id, password));
}

export async function recover(id: string, recoveryKey: string, newPassword: string): Promise<void> {
  await enter(await vault.recover<FinanceData>(id, recoveryKey, newPassword));
}

/** Verrouille : la cle et les donnees quittent la memoire de l'application. */
export async function lock(): Promise<void> {
  await saveChain;
  current = undefined;
  releaseTabLock?.();
  releaseTabLock = undefined;
  set({ profile: undefined, data: undefined, saveError: undefined, saving: false });
}

/**
 * Applique une modification sur une copie des donnees ; si elle reussit, l'affiche
 * et l'enregistre (chiffree). Une erreur de saisie laisse les donnees intactes.
 */
export function mutate<R>(change: (draft: FinanceData) => R): R {
  if (!current) {
    throw new Error('Profil verrouillé');
  }
  const draft = structuredClone(current.data);
  const result = change(draft);
  current.data = draft;
  set({ data: draft, saving: true });
  const snapshot = current;
  saveChain = saveChain
    .then(() => vault.save(snapshot, draft))
    .then(() => set({ saving: false, saveError: undefined }))
    .catch((e: unknown) => set({ saving: false, saveError: e instanceof Error ? e.message : String(e) }));
  return result;
}

export async function flush(): Promise<void> {
  await saveChain;
}

export async function renameCurrent(name: string): Promise<void> {
  if (!current) {
    return;
  }
  const clean = await vault.renameProfile(current.id, name);
  current.name = clean;
  set({ profile: { id: current.id, name: clean } });
}

export async function deleteCurrent(password: string): Promise<void> {
  if (!current) {
    return;
  }
  const id = current.id;
  await saveChain;
  await vault.deleteProfile(id, password);
  await lock();
}
