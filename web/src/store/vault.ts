/**
 * Coffre chiffre des profils, dans le navigateur (IndexedDB).
 *
 * - Les donnees d'un profil sont chiffrees en AES-256-GCM avec une cle de donnees
 *   aleatoire (DEK), jamais stockee en clair.
 * - La DEK est enveloppee deux fois : par une cle derivee du mot de passe maitre
 *   (PBKDF2-SHA-256, 600 000 iterations) et par une cle derivee de la cle de
 *   recuperation remise une seule fois a la creation.
 * - Seuls le nom du profil et des parametres non secrets sont lisibles sans mot de passe.
 * - Toute la cryptographie est celle du navigateur (WebCrypto) : aucun algorithme maison.
 */

export const MIN_PASSWORD_LENGTH = 10;
const PASSWORD_ITERATIONS = 600_000;
const RECOVERY_ITERATIONS = 100_000; // cle de recuperation : 160 bits aleatoires
const DB_NAME = 'financeapp';
const STORE = 'profiles';

interface Sealed {
  iv: string;
  data: string;
}

interface Wrapped {
  salt: string;
  iterations: number;
  key: Sealed;
}

export interface ProfileRecord {
  id: string;
  name: string;
  createdAt: string;
  updatedAt: string;
  password: Wrapped;
  recovery: Wrapped;
  payload: Sealed;
}

/** Profil tel qu'il apparait avant deverrouillage : seulement son nom. */
export interface ProfileSummary {
  id: string;
  name: string;
}

/** Mot de passe ou cle de recuperation incorrect (ou fichier altere). */
export class WrongSecretError extends Error {
  constructor() {
    super('Mot de passe incorrect');
  }
}

// ---------------------------------------------------------------- encodage

function toB64(bytes: ArrayBuffer | Uint8Array): string {
  const arr = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  let s = '';
  for (let i = 0; i < arr.length; i += 0x8000) {
    s += String.fromCharCode(...arr.subarray(i, i + 0x8000));
  }
  return btoa(s);
}

function fromB64(s: string): Uint8Array<ArrayBuffer> {
  const bin = atob(s);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) {
    out[i] = bin.charCodeAt(i);
  }
  return out;
}

function random(n: number): Uint8Array<ArrayBuffer> {
  return crypto.getRandomValues(new Uint8Array(n));
}

const BASE32 = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; // sans I, O, 0, 1 : pas de confusion a la recopie

/** Cle de recuperation : 32 caracteres en 8 groupes, ex. "K7QF-3MZP-…". */
export function newRecoveryKey(): string {
  const bytes = random(20);
  let bits = 0;
  let value = 0;
  let out = '';
  for (const b of bytes) {
    value = (value << 8) | b;
    bits += 8;
    while (bits >= 5) {
      out += BASE32[(value >>> (bits - 5)) & 31];
      bits -= 5;
    }
  }
  return out.match(/.{4}/g)!.join('-');
}

/** Normalise une cle saisie (espaces, tirets, minuscules). */
export function normalizeRecoveryKey(text: string): string {
  const clean = text.toUpperCase().replace(/[^A-Z0-9]/g, '');
  return clean.match(/.{1,4}/g)?.join('-') ?? '';
}

// ---------------------------------------------------------------- cles

async function deriveKek(secret: string, salt: Uint8Array<ArrayBuffer>, iterations: number): Promise<CryptoKey> {
  const material = await crypto.subtle.importKey('raw', new TextEncoder().encode(secret.normalize('NFC')),
    'PBKDF2', false, ['deriveKey']);
  return crypto.subtle.deriveKey({ name: 'PBKDF2', salt, iterations, hash: 'SHA-256' }, material,
    { name: 'AES-GCM', length: 256 }, false, ['wrapKey', 'unwrapKey']);
}

async function wrap(dek: CryptoKey, secret: string, iterations: number): Promise<Wrapped> {
  const salt = random(16);
  const iv = random(12);
  const kek = await deriveKek(secret, salt, iterations);
  const data = await crypto.subtle.wrapKey('raw', dek, kek, { name: 'AES-GCM', iv });
  return { salt: toB64(salt), iterations, key: { iv: toB64(iv), data: toB64(data) } };
}

/** Deballe la DEK ; extractable seulement le temps de l'envelopper a nouveau (changement de mot de passe). */
async function unwrap(w: Wrapped, secret: string, extractable: boolean): Promise<CryptoKey> {
  const kek = await deriveKek(secret, fromB64(w.salt), w.iterations);
  try {
    return await crypto.subtle.unwrapKey('raw', fromB64(w.key.data), kek,
      { name: 'AES-GCM', iv: fromB64(w.key.iv) }, { name: 'AES-GCM', length: 256 }, extractable,
      ['encrypt', 'decrypt']);
  } catch {
    throw new WrongSecretError();
  }
}

async function seal(dek: CryptoKey, profileId: string, plain: unknown): Promise<Sealed> {
  const iv = random(12);
  const data = await crypto.subtle.encrypt({ name: 'AES-GCM', iv, additionalData: new TextEncoder().encode(profileId) },
    dek, new TextEncoder().encode(JSON.stringify(plain)));
  return { iv: toB64(iv), data: toB64(data) };
}

async function open<T>(dek: CryptoKey, profileId: string, sealed: Sealed): Promise<T> {
  try {
    const plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: fromB64(sealed.iv),
      additionalData: new TextEncoder().encode(profileId) }, dek, fromB64(sealed.data));
    return JSON.parse(new TextDecoder().decode(plain)) as T;
  } catch {
    throw new WrongSecretError();
  }
}

// ---------------------------------------------------------------- IndexedDB

function db(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, 1);
    req.onupgradeneeded = () => req.result.createObjectStore(STORE, { keyPath: 'id' });
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

async function tx<T>(mode: IDBTransactionMode, run: (store: IDBObjectStore) => IDBRequest<T> | void): Promise<T> {
  const database = await db();
  return new Promise<T>((resolve, reject) => {
    const t = database.transaction(STORE, mode);
    const req = run(t.objectStore(STORE));
    t.oncomplete = () => {
      database.close();
      resolve(req ? req.result : (undefined as T));
    };
    t.onerror = () => {
      database.close();
      reject(t.error);
    };
    t.onabort = () => {
      database.close();
      reject(t.error ?? new Error('Écriture interrompue'));
    };
  });
}

async function getRecord(id: string): Promise<ProfileRecord> {
  const record = await tx<ProfileRecord | undefined>('readonly', (s) => s.get(id));
  if (!record) {
    throw new Error('Profil introuvable');
  }
  return record;
}

async function putRecord(record: ProfileRecord): Promise<void> {
  await tx('readwrite', (s) => s.put(record));
}

export async function listProfiles(): Promise<ProfileSummary[]> {
  const all = await tx<ProfileRecord[]>('readonly', (s) => s.getAll());
  return all.map((p) => ({ id: p.id, name: p.name })).sort((a, b) => a.name.localeCompare(b.name, 'fr'));
}

// ---------------------------------------------------------------- operations

/** Profil deverrouille : la cle reste en memoire jusqu'au verrouillage. */
export interface OpenVault<T> {
  id: string;
  name: string;
  key: CryptoKey;
  data: T;
}

function checkPassword(password: string): void {
  if (password.length < MIN_PASSWORD_LENGTH) {
    throw new Error(`Le mot de passe doit contenir au moins ${MIN_PASSWORD_LENGTH} caractères`);
  }
}

function checkName(name: string, existing: ProfileSummary[], exceptId?: string): string {
  const clean = name.trim();
  if (!clean) {
    throw new Error('Le nom est obligatoire');
  }
  if (clean.length > 40) {
    throw new Error('Le nom est trop long (40 caractères au plus)');
  }
  if (existing.some((p) => p.id !== exceptId && p.name.toLowerCase() === clean.toLowerCase())) {
    throw new Error(`Le profil « ${clean} » existe déjà`);
  }
  return clean;
}

/** Cree un profil ; renvoie la cle de recuperation (a montrer une seule fois). */
export async function createProfile<T>(name: string, password: string, data: T):
  Promise<{ vault: OpenVault<T>; recoveryKey: string }> {
  checkPassword(password);
  const clean = checkName(name, await listProfiles());
  const id = crypto.randomUUID();
  const extractable = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, true, ['encrypt', 'decrypt']);
  const recoveryKey = newRecoveryKey();
  const now = new Date().toISOString();
  const record: ProfileRecord = {
    id, name: clean, createdAt: now, updatedAt: now,
    password: await wrap(extractable, password, PASSWORD_ITERATIONS),
    recovery: await wrap(extractable, recoveryKey, RECOVERY_ITERATIONS),
    payload: await seal(extractable, id, data),
  };
  await putRecord(record);
  // En memoire, la cle n'est plus exportable.
  const key = await unwrap(record.password, password, false);
  return { vault: { id, name: clean, key, data }, recoveryKey };
}

export async function unlock<T>(id: string, password: string): Promise<OpenVault<T>> {
  const record = await getRecord(id);
  const key = await unwrap(record.password, password, false);
  return { id, name: record.name, key, data: await open<T>(key, id, record.payload) };
}

/** Deverrouille avec la cle de recuperation et definit un nouveau mot de passe. */
export async function recover<T>(id: string, recoveryKey: string, newPassword: string): Promise<OpenVault<T>> {
  checkPassword(newPassword);
  const record = await getRecord(id);
  const extractable = await unwrap(record.recovery, normalizeRecoveryKey(recoveryKey), true);
  record.password = await wrap(extractable, newPassword, PASSWORD_ITERATIONS);
  await putRecord(record);
  return unlock<T>(id, newPassword);
}

export async function save<T>(vault: OpenVault<T>, data: T): Promise<void> {
  const record = await getRecord(vault.id);
  record.payload = await seal(vault.key, vault.id, data);
  record.updatedAt = new Date().toISOString();
  await putRecord(record);
}

export async function changePassword(id: string, current: string, next: string): Promise<void> {
  checkPassword(next);
  const record = await getRecord(id);
  const extractable = await unwrap(record.password, current, true);
  record.password = await wrap(extractable, next, PASSWORD_ITERATIONS);
  await putRecord(record);
}

/** Nouvelle cle de recuperation (l'ancienne ne fonctionne plus). */
export async function renewRecoveryKey(id: string, password: string): Promise<string> {
  const record = await getRecord(id);
  const extractable = await unwrap(record.password, password, true);
  const recoveryKey = newRecoveryKey();
  record.recovery = await wrap(extractable, recoveryKey, RECOVERY_ITERATIONS);
  await putRecord(record);
  return recoveryKey;
}

export async function renameProfile(id: string, name: string): Promise<string> {
  const clean = checkName(name, await listProfiles(), id);
  const record = await getRecord(id);
  record.name = clean;
  await putRecord(record);
  return clean;
}

/** Supprime un profil apres verification de son mot de passe. */
export async function deleteProfile(id: string, password: string): Promise<void> {
  const record = await getRecord(id);
  await unwrap(record.password, password, false);
  await tx('readwrite', (s) => s.delete(id));
}

// ---------------------------------------------------------------- sauvegardes

const BACKUP_FORMAT = 'financeapp-web-backup';

/** Sauvegarde chiffree : le profil tel quel (illisible sans son mot de passe ou sa cle de recuperation). */
export async function exportBackup(id: string): Promise<string> {
  const record = await getRecord(id);
  return JSON.stringify({ format: BACKUP_FORMAT, version: 1, exportedAt: new Date().toISOString(), profile: record });
}

/**
 * Ajoute le profil d'une sauvegarde (sans jamais remplacer un profil existant) ;
 * il s'ouvre ensuite avec le mot de passe qu'il avait.
 */
export async function importBackup(text: string): Promise<ProfileSummary> {
  let parsed: { format?: string; version?: number; profile?: ProfileRecord };
  try {
    parsed = JSON.parse(text);
  } catch {
    throw new Error("Ce fichier n'est pas une sauvegarde FinanceApp");
  }
  const p = parsed.profile;
  if (parsed.format !== BACKUP_FORMAT || parsed.version !== 1 || !p || typeof p.id !== 'string'
    || !p.password?.key?.data || !p.recovery?.key?.data || !p.payload?.data) {
    throw new Error("Ce fichier n'est pas une sauvegarde FinanceApp (version web)");
  }
  const existing = await listProfiles();
  // Le contenu chiffre est lie a l'identifiant d'origine : on le garde ; un profil de meme
  // identifiant deja present n'est jamais ecrase.
  if (existing.some((e) => e.id === p.id)) {
    throw new Error('Ce profil est déjà présent dans ce navigateur. Supprimez-le d\'abord si vous voulez le '
      + 'remplacer par la sauvegarde.');
  }
  let name = String(p.name || 'Profil importé').slice(0, 40);
  for (let i = 2; existing.some((e) => e.name.toLowerCase() === name.toLowerCase()); i++) {
    name = `${String(p.name).slice(0, 34)} (${i})`;
  }
  await putRecord({ ...p, name });
  return { id: p.id, name };
}

/** Demande au navigateur de ne pas effacer ces donnees en cas de manque de place. */
export async function requestPersistence(): Promise<boolean> {
  try {
    return (await navigator.storage?.persist?.()) ?? false;
  } catch {
    return false;
  }
}
