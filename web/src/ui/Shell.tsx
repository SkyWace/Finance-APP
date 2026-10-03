import { useCallback, useEffect, useState } from 'react';
import type { TransactionType } from '../domain/types';
import { lock, mutate, useData, useSession } from '../store/session';
import { TransactionDialog } from './components/dialogs';
import { NavContext, type PageId } from './nav';
import { AccountsPage } from './pages/AccountsPage';
import { AvailablePage } from './pages/AvailablePage';
import { CategoriesPage } from './pages/CategoriesPage';
import { DashboardPage } from './pages/DashboardPage';
import { ForecastPage } from './pages/ForecastPage';
import { RecurringPage } from './pages/RecurringPage';
import { SettingsPage } from './pages/SettingsPage';
import { TransactionsPage } from './pages/TransactionsPage';
import { UpcomingPage } from './pages/UpcomingPage';
import { isLight, setTheme } from './theme';

const PAGES: { id: PageId; icon: string; label: string }[] = [
  { id: 'dashboard', icon: '◉', label: 'Tableau de bord' },
  { id: 'accounts', icon: '▣', label: 'Comptes' },
  { id: 'transactions', icon: '≡', label: 'Transactions' },
  { id: 'upcoming', icon: '◷', label: 'À venir' },
  { id: 'available', icon: '◎', label: 'Disponible réel' },
  { id: 'forecast', icon: '↗', label: 'Prévisions' },
  { id: 'recurring', icon: '↻', label: 'Récurrences' },
  { id: 'categories', icon: '▦', label: 'Catégories' },
  { id: 'settings', icon: '⚙', label: 'Paramètres' },
];

function pageFromHash(): PageId {
  const id = window.location.hash.replace(/^#\/?/, '');
  return PAGES.some((p) => p.id === id) ? (id as PageId) : 'dashboard';
}

/** Verrouillage apres N minutes sans activite (souris, clavier, toucher). */
function useAutoLock(minutes: number) {
  useEffect(() => {
    if (minutes <= 0) {
      return undefined;
    }
    let last = Date.now();
    const touch = () => {
      last = Date.now();
    };
    const events = ['pointerdown', 'keydown', 'wheel', 'touchstart', 'mousemove'];
    events.forEach((e) => window.addEventListener(e, touch, { passive: true }));
    const timer = window.setInterval(() => {
      if (Date.now() - last > minutes * 60_000) {
        void lock();
      }
    }, 10_000);
    return () => {
      events.forEach((e) => window.removeEventListener(e, touch));
      window.clearInterval(timer);
    };
  }, [minutes]);
}

export function Shell() {
  const data = useData();
  const session = useSession();
  const [page, setPage] = useState<PageId>(pageFromHash());
  const [menuOpen, setMenuOpen] = useState(false);
  const [newOp, setNewOp] = useState<TransactionType | null>(null);
  const [light, setLight] = useState(isLight());
  useAutoLock(data.settings.autoLockMinutes);

  useEffect(() => {
    const onHash = () => setPage(pageFromHash());
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);
  // Enregistrement en cours a la fermeture de l'onglet : le navigateur demande confirmation.
  useEffect(() => {
    const onLeave = (e: BeforeUnloadEvent) => {
      if (session.saving) {
        e.preventDefault();
      }
    };
    window.addEventListener('beforeunload', onLeave);
    return () => window.removeEventListener('beforeunload', onLeave);
  }, [session.saving]);

  const go = useCallback((id: PageId) => {
    window.location.hash = `/${id}`;
    setPage(id);
    setMenuOpen(false);
  }, []);
  const newOperation = useCallback((type?: TransactionType) => setNewOp(type ?? 'EXPENSE'), []);
  const current = PAGES.find((p) => p.id === page)!;
  const privacy = data.settings.privacy;

  return (
    <NavContext.Provider value={{ go, newOperation }}>
      <div className={`app${menuOpen ? ' menu-open' : ''}`} onClick={(e) => {
        if (menuOpen && e.target === e.currentTarget) {
          setMenuOpen(false);
        }
      }}>
        <nav className="sidebar" aria-label="Menu principal">
          <div className="brand">FinanceApp</div>
          <div className="tagline">Finances personnelles</div>
          <div className="nav">
            {PAGES.map((p) => (
              <button key={p.id} aria-current={p.id === page ? 'page' : undefined} onClick={() => go(p.id)}>
                <span className="icon" aria-hidden="true">{p.icon}</span>{p.label}
              </button>
            ))}
          </div>
          <div className="sidebar-footer">● Données dans ce navigateur · chiffrées</div>
        </nav>
        <div className="main">
          <header className="header">
            <button className="btn ghost menu-toggle" aria-label="Menu" aria-expanded={menuOpen}
              onClick={() => setMenuOpen(!menuOpen)}>☰</button>
            <h1>{current.label}</h1>
            <span className="muted hide-mobile" title="Profil ouvert">◯ {session.profile?.name}</span>
            <button className="btn ghost" onClick={() => void lock()} title="Verrouiller : la clé de vos données est effacée de la mémoire">
              ⊘ <span className="hide-mobile">Verrouiller</span></button>
            <button className="btn ghost" aria-label={light ? 'Passer au thème sombre' : 'Passer au thème clair'}
              title={light ? 'Passer au thème sombre' : 'Passer au thème clair'}
              onClick={() => { setTheme(light ? 'dark' : 'light'); setLight(!light); }}>{light ? '☾' : '☀'}</button>
            <button className="btn ghost" aria-pressed={privacy}
              onClick={() => mutate((d) => { d.settings.privacy = !d.settings.privacy; })}
              title={privacy ? 'Afficher les montants' : 'Masquer les montants (mode confidentialité)'}>
              {privacy ? '◌' : '◍'} <span className="hide-mobile">{privacy ? 'Afficher' : 'Masquer'}</span></button>
            <button className="btn primary" onClick={() => newOperation()} disabled={data.accounts.length === 0}>
              + <span className="hide-mobile">Nouvelle opération</span></button>
          </header>
          {session.saveError && (
            <div className="banner error" role="alert" style={{ margin: '12px 28px 0' }}>
              ⚠ La dernière modification n'a pas pu être enregistrée dans ce navigateur : {session.saveError}.
              Téléchargez une sauvegarde depuis les Paramètres.
            </div>
          )}
          <main className="content" id="contenu">
            {page === 'dashboard' && <DashboardPage />}
            {page === 'accounts' && <AccountsPage />}
            {page === 'transactions' && <TransactionsPage />}
            {page === 'upcoming' && <UpcomingPage />}
            {page === 'available' && <AvailablePage />}
            {page === 'forecast' && <ForecastPage />}
            {page === 'recurring' && <RecurringPage />}
            {page === 'categories' && <CategoriesPage />}
            {page === 'settings' && <SettingsPage />}
          </main>
        </div>
        {newOp && <TransactionDialog initialType={newOp} onClose={() => setNewOp(null)} />}
      </div>
    </NavContext.Provider>
  );
}
