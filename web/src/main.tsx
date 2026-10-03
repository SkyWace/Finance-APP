import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './ui/App';
import { applyTheme } from './ui/theme';
import './ui/styles.css';

// Jamais affichee dans le cadre d'un autre site (saisie du mot de passe detournee).
if (window.top !== window.self) {
  document.body.textContent = 'FinanceApp ne peut pas être affiché dans une autre page. Ouvrez-le directement.';
  throw new Error('Affichage dans un cadre refusé');
}

applyTheme();
window.matchMedia?.('(prefers-color-scheme: light)').addEventListener?.('change', () => applyTheme());

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
