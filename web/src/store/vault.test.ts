import 'fake-indexeddb/auto';
import { describe, expect, it } from 'vitest';
import {
  changePassword, createProfile, deleteProfile, exportBackup, importBackup, listProfiles, normalizeRecoveryKey,
  recover, renewRecoveryKey, save, unlock, WrongSecretError,
} from './vault';

const PASSWORD = 'mot de passe solide';

describe('coffre chiffre', () => {
  it('cree, chiffre, deverrouille ; mauvais mot de passe refuse', async () => {
    const { vault, recoveryKey } = await createProfile('Alice', PASSWORD, { secret: 'SOLDE-1234' });
    expect(recoveryKey).toMatch(/^([A-Z2-9]{4}-){7}[A-Z2-9]{4}$/);
    const raw = JSON.stringify(await (await import('./vault')).exportBackup(vault.id));
    expect(raw).not.toContain('SOLDE-1234');
    expect(raw).not.toContain(PASSWORD);
    await expect(unlock(vault.id, 'pas le bon mot de passe')).rejects.toBeInstanceOf(WrongSecretError);
    const opened = await unlock<{ secret: string }>(vault.id, PASSWORD);
    expect(opened.data.secret).toBe('SOLDE-1234');
    await expect(createProfile('alice', PASSWORD, {})).rejects.toThrow('existe déjà');
    await expect(createProfile('Bob', 'court', {})).rejects.toThrow('10 caractères');
  });

  it('enregistre, change le mot de passe, recupere avec la cle', async () => {
    const { vault, recoveryKey } = await createProfile('Chloé', PASSWORD, { n: 1 });
    await save(vault, { n: 2 });
    await changePassword(vault.id, PASSWORD, 'nouveau mot de passe');
    await expect(unlock(vault.id, PASSWORD)).rejects.toBeInstanceOf(WrongSecretError);
    expect((await unlock<{ n: number }>(vault.id, 'nouveau mot de passe')).data.n).toBe(2);
    const typed = recoveryKey.toLowerCase().replace(/-/g, ' ');
    expect(normalizeRecoveryKey(typed)).toBe(recoveryKey);
    const recovered = await recover<{ n: number }>(vault.id, typed, 'encore un autre mot');
    expect(recovered.data.n).toBe(2);
    const fresh = await renewRecoveryKey(vault.id, 'encore un autre mot');
    await expect(recover(vault.id, recoveryKey, 'peu importe ici')).rejects.toBeInstanceOf(WrongSecretError);
    expect((await recover<{ n: number }>(vault.id, fresh, 'dernier mot de passe')).data.n).toBe(2);
  });

  it('sauvegarde : import sans ecraser, suppression avec mot de passe', async () => {
    const { vault } = await createProfile('Damien', PASSWORD, { v: 'x' });
    const backup = await exportBackup(vault.id);
    await expect(importBackup(backup)).rejects.toThrow('déjà présent');
    await expect(deleteProfile(vault.id, 'mauvais mot de passe')).rejects.toBeInstanceOf(WrongSecretError);
    await deleteProfile(vault.id, PASSWORD);
    expect((await listProfiles()).some((p) => p.id === vault.id)).toBe(false);
    const restored = await importBackup(backup);
    expect(restored.name).toBe('Damien');
    expect((await unlock<{ v: string }>(restored.id, PASSWORD)).data.v).toBe('x');
    await expect(importBackup('{"format":"autre"}')).rejects.toThrow('sauvegarde');
    await expect(importBackup('pas du json')).rejects.toThrow('sauvegarde');
  });

  it('un contenu altere est refuse', async () => {
    const { vault } = await createProfile('Eve', PASSWORD, { v: 1 });
    const backup = JSON.parse(await exportBackup(vault.id));
    await deleteProfile(vault.id, PASSWORD);
    const bytes = atob(backup.profile.payload.data);
    backup.profile.payload.data = btoa(String.fromCharCode(bytes.charCodeAt(0) ^ 1) + bytes.slice(1));
    const imported = await importBackup(JSON.stringify(backup));
    await expect(unlock(imported.id, PASSWORD)).rejects.toBeInstanceOf(WrongSecretError);
  });
});
