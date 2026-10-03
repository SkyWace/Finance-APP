import { formatDate, formatMonthYear, formatShortDate, toEpochDay } from '../../domain/dates';
import { formatMoney } from '../../domain/money';
import type { Forecast, ForecastPoint } from '../../domain/forecast';
import { usePrivacy } from '../common';

const W = 760;
const H = 260;
const PAD = { left: 64, right: 14, top: 12, bottom: 28 };

function niceStep(range: number): number {
  const raw = range / 5;
  const pow = 10 ** Math.floor(Math.log10(Math.max(raw, 1)));
  const n = raw / pow;
  return (n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10) * pow;
}

/**
 * Solde reel (trait plein) puis prevu (tirets) : les deux series se distinguent
 * par leur trait et la legende, pas seulement par la couleur.
 */
export function BalanceChart({ forecast }: { forecast: Forecast }) {
  const privacy = usePrivacy();
  const points: ForecastPoint[] = [...forecast.history, ...forecast.projection];
  const x0 = toEpochDay(points[0].date);
  const x1 = Math.max(toEpochDay(points[points.length - 1].date), x0 + 1);
  const values = points.map((p) => p.balance);
  let min = Math.min(...values, 0);
  let max = Math.max(...values, 0);
  if (min === max) {
    max = min + 10_000;
  }
  const step = niceStep(max - min);
  min = Math.floor(min / step) * step;
  max = Math.ceil(max / step) * step;
  const sx = (day: number) => PAD.left + ((day - x0) / (x1 - x0)) * (W - PAD.left - PAD.right);
  const sy = (v: number) => PAD.top + (1 - (v - min) / (max - min)) * (H - PAD.top - PAD.bottom);
  const path = (list: ForecastPoint[]) => list
    .map((p, i) => `${i === 0 ? 'M' : 'L'}${sx(toEpochDay(p.date)).toFixed(1)},${sy(p.balance).toFixed(1)}`).join('');
  const span = x1 - x0;
  const ticks = Array.from({ length: 7 }, (_, i) => Math.round(x0 + (span * i) / 6));
  const yTicks: number[] = [];
  for (let v = min; v <= max; v += step) {
    yTicks.push(v);
  }
  const lowest = forecast.lowest;
  return (
    <div className="chart">
      <svg viewBox={`0 0 ${W} ${H}`} role="img"
        aria-label={`Évolution du solde du ${formatDate(points[0].date)} au ${formatDate(points[points.length - 1].date)}`
          + (privacy ? '' : `, point bas ${formatMoney(lowest.balance)} le ${formatDate(lowest.date)}`)}>
        {yTicks.map((v) => (
          <g key={v}>
            <line className="grid" x1={PAD.left} x2={W - PAD.right} y1={sy(v)} y2={sy(v)} />
            {!privacy && <text className="axis" x={PAD.left - 8} y={sy(v) + 4} textAnchor="end">
              {Math.round(v / 100).toLocaleString('fr-FR')}</text>}
          </g>
        ))}
        {min < 0 && <line className="zero" x1={PAD.left} x2={W - PAD.right} y1={sy(0)} y2={sy(0)} />}
        {ticks.map((d) => {
          const date = new Date(d * 86_400_000).toISOString().slice(0, 10);
          return <text key={d} className="axis" x={sx(d)} y={H - 8} textAnchor="middle">
            {span > 200 ? formatMonthYear(date) : formatShortDate(date)}</text>;
        })}
        <path className="real" d={path(forecast.history)} />
        <path className="planned" d={path(forecast.projection)} />
      </svg>
      <div className="legend">
        <span><span className="swatch" />Réel</span>
        <span><span className="swatch dashed" />Prévision</span>
      </div>
    </div>
  );
}
