import { useState } from 'react';
import { SECTION_TITLES } from '../../domain/available';
import { formatDate, formatLongDate, type IsoDate } from '../../domain/dates';
import { HORIZON_LABELS, type HorizonType } from '../../domain/types';
import { availableFor } from '../../domain/views';
import { mutate, useData } from '../../store/session';
import { Money, useToday } from '../common';

export function AvailablePage() {
  const data = useData();
  const today = useToday();
  const [type, setType] = useState<HorizonType>(data.settings.defaultHorizon);
  const [custom, setCustom] = useState<IsoDate>(today);
  const { horizon, result } = availableFor(data, today, type, custom);
  return (
    <>
      <div className="row">
        <label htmlFor="av-horizon">Échéance</label>
        <select id="av-horizon" value={type} onChange={(e) => setType(e.target.value as HorizonType)} style={{ width: 'auto' }}>
          {Object.entries(HORIZON_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
        </select>
        {type === 'CUSTOM_DATE' && <input type="date" value={custom} min={today} onChange={(e) => setCustom(e.target.value)}
          style={{ width: 'auto' }} aria-label="Date" />}
        <label className="check">
          <input type="checkbox" checked={data.settings.includeCertainIncome}
            onChange={(e) => mutate((d) => { d.settings.includeCertainIncome = e.target.checked; })} />
          Compter les revenus prévus jugés certains
        </label>
      </div>
      <div className="card kpi">
        <div className="title">Disponible réel jusqu'au {formatLongDate(horizon.end)}</div>
        <div className="value" style={{ fontSize: 40 }}><Money cents={result.available} signed tone /></div>
        <div className="sub">
          {type === 'NEXT_PAYDAY' && (horizon.payday ? `Prochaine paie le ${formatLongDate(horizon.payday)} : ce qui doit tenir jusqu'à la veille.`
            : 'Aucun revenu récurrent trouvé : fin du mois utilisée.')}
          {' '}C'est ce qui reste si aucune autre dépense variable n'a lieu d'ici là.
        </div>
      </div>
      {result.available < 0 && (
        <div className="banner warning" role="status">⚠ Les dépenses prévues dépassent l'argent disponible
          d'ici le {formatLongDate(horizon.end)}.</div>
      )}
      {result.sections.map((s) => (
        <section className="card section" key={s.kind}>
          <div className="row">
            <h2 style={{ margin: 0 }}>{SECTION_TITLES[s.kind]}</h2>
            {!s.counted && <span className="badge">non compté</span>}
            <span className="spacer" />
            <Money cents={s.total} signed tone />
          </div>
          <div className="rows" style={{ marginTop: 8 }}>
            {s.lines.length === 0 && <p className="muted">Aucun compte inclus dans le disponible : cochez « Inclus dans
              le disponible » sur votre compte courant.</p>}
            {s.lines.map((l, i) => (
              <div className="op-row" key={i}>
                <span className="date">{l.date ? formatDate(l.date).slice(0, 5) : ''}</span>
                <div className="texts"><div className="label">{l.label} {l.overdue && <span className="badge warning">en
                  retard</span>}</div></div>
                <Money cents={l.amount} signed tone />
              </div>
            ))}
          </div>
        </section>
      ))}
      <p className="hint">Calcul : solde actuel des comptes inclus − dépenses prévues jusqu'à l'échéance (retards
        compris) ± virements vers l'épargne − reste des budgets et effort des objectifs marqués « à réserver » + revenus
        prévus jugés certains. Un virement entre deux comptes inclus est neutre.</p>
    </>
  );
}
