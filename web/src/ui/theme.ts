/** Theme d'affichage : retenu dans ce navigateur (reglage d'affichage, sans donnee financiere). */
export type ThemeChoice = 'system' | 'light' | 'dark';
const KEY = 'financeapp.theme';

export function getTheme(): ThemeChoice {
  try {
    const v = localStorage.getItem(KEY);
    return v === 'light' || v === 'dark' ? v : 'system';
  } catch {
    return 'system';
  }
}

export function applyTheme(choice: ThemeChoice = getTheme()): void {
  const light = choice === 'light'
    || (choice === 'system' && window.matchMedia?.('(prefers-color-scheme: light)').matches);
  document.documentElement.dataset.theme = light ? 'light' : 'dark';
}

export function setTheme(choice: ThemeChoice): void {
  try {
    localStorage.setItem(KEY, choice);
  } catch {
    // stockage indisponible (navigation privee) : le choix vaut pour cette page
  }
  applyTheme(choice);
}

export function isLight(): boolean {
  return document.documentElement.dataset.theme === 'light';
}
