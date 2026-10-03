import { formatLongDate, formatShortDate } from '../../domain/dates';
import { HORIZON_LABELS } from '../../domain/types';
import { forecastFor, dashboard } from '../../domain/views';
import { useData } from '../../store/session';
import { BalanceChart } from '../components/BalanceChart';
import { Empty, Money, useToday } from '../common';
import { useNav } from '../nav';

export function DashboardPage() {
  const data = useData();
  const today = useToday();
  const nav = useNav();
  if (data.accounts.length === 0) {
    return (
      <div className="card">
        <h2 style={{ marginTop: 0 }}>Bienvenue</h2>
        <p>Commencez par créer votre compte courant : son solde actuel suffit. Ajoutez ensuite vos opérations
          récurrentes (salaire, loyer, abonnements) pour connaître votre <strong>disponible réel</strong>.</p>
        <p className="hint">Vos données restent dans ce navigateur, chiffrées avec votre mot de passe : aucune n'est
          envoyée sur Internet.</p>
        <div className="row">
          <button className="btn primary" onClick={() => nav.go('accounts')}>Créer un compte</button>
        </div>
      </div>
    );
  }
  const d = dashboard(data, today);
  const forecast = forecastFor(data, today, 30, 30);
  const accounts = new Map(data.accounts.map((a) => [a.id, a.name]));
  return (
    <>
      <div className="kpis">
        <div className="card kpi">
          <div className="title">Patrimoine financier</div>
          <div className="value"><Money cents={d.netWorth} /></div>
          <div className="sub">Tous les comptes actifs</div>
        </div>
        <div className="card kpi">
          <div className="title">Comptes courants</div>
          <div className="value"><Money cents={d.current} /></div>
        </div>
        <div className="card kpi">
          <div className="title">Épargne</div>
          <div className="value"><Money cents={d.savings} /></div>
        </div>
        <button className="card kpi clickable" style={{ textAlign: 'left' }} onClick={() => nav.go('available')}>
          <div className="title">Disponible réel</div>
          <div className="value"><Money cents={d.available.available} className="accent" /></div>
          <div className="sub">{HORIZON_LABELS[d.horizon.type]} : jusqu'au {formatLongDate(d.horizon.end)} · voir le
            détail ›</div>
        </button>
      </div>
      <div className="kpis">
        <div className="card kpi">
          <div className="title">Revenus ce mois</div>
          <div className="value"><Money cents={d.incomeThisMonth} signed tone /></div>
        </div>
        <div className="card kpi">
          <div className="title">Dépenses ce mois</div>
          <div className="value"><Money cents={d.expensesThisMonth} signed tone /></div>
        </div>
        <div className="card kpi">
          <div className="title">À venir d'ici la fin du mois</div>
          <div className="value"><Money cents={d.upcomingOutThisMonth} signed tone /></div>
          <div className="sub">Dépenses prévues et récurrentes</div>
        </div>
      </div>
      <div className="grid-2">
        <section className="card section">
          <h2>Solde des comptes courants — 30 jours</h2>
          <BalanceChart forecast={forecast} />
          <p className="hint">Point bas prévu : <Money cents={forecast.lowest.balance} /> le{' '}
            {formatLongDate(forecast.lowest.date)}
            {forecast.firstNegative && <strong className="negative"> · ⚠ solde négatif prévu le{' '}
              {formatLongDate(forecast.firstNegative.date)}</strong>}</p>
        </section>
        <section className="card section">
          <h2>Prochaines opérations</h2>
          {d.next.length === 0 ? <Empty>Aucune opération prévue dans les 30 jours.</Empty> : (
            <div className="rows">
              {d.next.map((i, n) => (
                <div className="op-row" key={n}>
                  <span className="date">{formatShortDate(i.date)}</span>
                  <div className="texts">
                    <div className="label">{i.label}</div>
                    <div className="detail">{accounts.get(i.accountId)}{i.date < today ? ' · en retard' : ''}</div>
                  </div>
                  <Money cents={i.amount} signed tone />
                </div>
              ))}
            </div>
          )}
          <button className="link" onClick={() => nav.go('upcoming')}>Tout voir ›</button>
        </section>
      </div>
      <section className="card section">
        <h2>Dernières opérations</h2>
        {d.recent.length === 0 ? <Empty>Aucune opération pour l'instant.</Empty> : (
          <div className="rows">
            {d.recent.map((t) => (
              <div className="op-row" key={t.id}>
                <span className="date">{formatShortDate(t.date)}</span>
                <div className="texts">
                  <div className="label">{t.label}</div>
                  <div className="detail">{accounts.get(t.accountId)}</div>
                </div>
                <Money cents={t.amount} signed tone />
              </div>
            ))}
          </div>
        )}
      </section>
    </>
  );
}
