/**
 * Dates civiles "AAAA-MM-JJ", sans heure ni fuseau (equivalent de LocalDate) :
 * les calculs passent par le numero de jour, jamais par l'heure locale.
 */
export type IsoDate = string;

export function isValidDate(s: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(s)) {
    return false;
  }
  const [y, m, d] = parts(s);
  return m >= 1 && m <= 12 && d >= 1 && d <= daysInMonth(y, m);
}

export function parts(s: IsoDate): [number, number, number] {
  return [Number(s.slice(0, 4)), Number(s.slice(5, 7)), Number(s.slice(8, 10))];
}

export function iso(y: number, m: number, d: number): IsoDate {
  return `${String(y).padStart(4, '0')}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
}

export function daysInMonth(y: number, m: number): number {
  return new Date(Date.UTC(y, m, 0)).getUTCDate();
}

/** Numero de jour depuis le 1er janvier 1970. */
export function toEpochDay(s: IsoDate): number {
  const [y, m, d] = parts(s);
  return Math.round(Date.UTC(y, m - 1, d) / 86_400_000);
}

export function fromEpochDay(day: number): IsoDate {
  const date = new Date(day * 86_400_000);
  return iso(date.getUTCFullYear(), date.getUTCMonth() + 1, date.getUTCDate());
}

export function plusDays(s: IsoDate, days: number): IsoDate {
  return fromEpochDay(toEpochDay(s) + days);
}

/** Ajoute des mois en ramenant au dernier jour si besoin (31 janv. + 1 mois = 28/29 fevr.). */
export function plusMonths(s: IsoDate, months: number): IsoDate {
  const [y, m, d] = parts(s);
  const index = y * 12 + (m - 1) + months;
  const ny = Math.floor(index / 12);
  const nm = index - ny * 12 + 1;
  return iso(ny, nm, Math.min(d, daysInMonth(ny, nm)));
}

export function daysBetween(from: IsoDate, to: IsoDate): number {
  return toEpochDay(to) - toEpochDay(from);
}

/** Mois entiers ecoules (comme ChronoUnit.MONTHS.between). */
export function monthsBetween(from: IsoDate, to: IsoDate): number {
  const [y1, m1, d1] = parts(from);
  const [y2, m2, d2] = parts(to);
  let months = (y2 - y1) * 12 + (m2 - m1);
  if (months > 0 && d2 < d1) {
    months--;
  } else if (months < 0 && d2 > d1) {
    months++;
  }
  return months;
}

export function endOfMonth(s: IsoDate): IsoDate {
  const [y, m] = parts(s);
  return iso(y, m, daysInMonth(y, m));
}

export function startOfMonth(s: IsoDate): IsoDate {
  const [y, m] = parts(s);
  return iso(y, m, 1);
}

/** Dimanche de la semaine en cours (ou aujourd'hui si dimanche). */
export function endOfWeek(s: IsoDate): IsoDate {
  const weekday = new Date(toEpochDay(s) * 86_400_000).getUTCDay(); // 0 = dimanche
  return plusDays(s, weekday === 0 ? 0 : 7 - weekday);
}

export function minDate(a: IsoDate, b: IsoDate): IsoDate {
  return a < b ? a : b;
}

export function maxDate(a: IsoDate, b: IsoDate): IsoDate {
  return a > b ? a : b;
}

/** Date du jour sur l'ordinateur de l'utilisateur (heure locale). */
export function todayLocal(now: Date = new Date()): IsoDate {
  return iso(now.getFullYear(), now.getMonth() + 1, now.getDate());
}

const MONTHS = ['janvier', 'février', 'mars', 'avril', 'mai', 'juin', 'juillet', 'août', 'septembre', 'octobre',
  'novembre', 'décembre'];

/** "03/10/2026" */
export function formatDate(s: IsoDate): string {
  const [y, m, d] = parts(s);
  return `${String(d).padStart(2, '0')}/${String(m).padStart(2, '0')}/${y}`;
}

/** "3 octobre 2026" */
export function formatLongDate(s: IsoDate): string {
  const [y, m, d] = parts(s);
  return `${d} ${MONTHS[m - 1]} ${y}`;
}

/** "03/10" */
export function formatShortDate(s: IsoDate): string {
  const [, m, d] = parts(s);
  return `${String(d).padStart(2, '0')}/${String(m).padStart(2, '0')}`;
}

/** "oct. 26" */
export function formatMonthYear(s: IsoDate): string {
  const [y, m] = parts(s);
  return `${MONTHS[m - 1].slice(0, 4).replace(/\.$/, '')}${MONTHS[m - 1].length > 4 ? '.' : ''} ${String(y).slice(2)}`;
}

/** "octobre 2026" */
export function formatLongMonth(s: IsoDate): string {
  const [y, m] = parts(s);
  return `${MONTHS[m - 1]} ${y}`;
}
