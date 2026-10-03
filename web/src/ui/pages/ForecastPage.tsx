import { useState } from 'react';
import { formatLongDate } from '../../domain/dates';
import { forecastFor } from '../../domain/views';
import { useData } from '../../store/session';
import { BalanceChart } from '../components/BalanceChart';
import { Money, useToday } from '../common';

const RANGES: { days: number; label: string; history: number }[] = [
  { days: 7, label: '7 jours', history: 14 },
  { days: 30, label: '30 jours', history: 30 },
  { days: 90, label: '3 mois', history: 60 },
  { days: 365, label: '12 mois', history: 90 },
  { days: 730, label: '24 mois', history: 90 },
];

export function ForecastPage() {
  const data = useData();
  const today = useToday();
  const [range, setRange] = useState(1);
  const r = RANGES[range];
  const forecast = forecastFor(data, today, r.history, r.days);
  const end = forecast.projection[forecast.projection.length - 1];
  return (
    <>
      <div className="segmented" role="group" aria-label="Horizon">
        {RANGES.map((x, i) => (
          <button key={x.days} aria-pressed={i === range} onClick={() => setRange(i)}>{x.label}</button>
        ))}
      </div>
      <section className="card section">
        <h2>Solde des comptes inclus dans le disponible</h2>
        <BalanceChart forecast={forecast} />
      </section>
      <div className="kpis">
        <div className="card kpi"><div className="title">Solde prévu le {formatLongDate(end.date)}</div>
          <div className="value"><Money cents={end.balance} tone /></div></div>
        <div className="card kpi"><div className="title">Point bas</div>
          <div className="value"><Money cents={forecast.lowest.balance} tone /></div>
          <div className="sub">le {formatLongDate(forecast.lowest.date)}</div></div>
        <div className="card kpi"><div className="title">Solde négatif</div>
          <div className="value">{forecast.firstNegative ? '⚠ Oui' : 'Non'}</div>
          <div className="sub">{forecast.firstNegative ? `à partir du ${formatLongDate(forecast.firstNegative.date)}`
            : 'sur toute la période'}</div></div>
      </div>
      <p className="hint">La prévision ajoute au solde actuel les opérations prévues et les échéances récurrentes ;
        les dépenses variables non saisies (courses, loisirs…) n'y figurent pas.</p>
    </>
  );
}
