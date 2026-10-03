/**
 * Montants en CENTIMES entiers (jamais de nombre a virgule pour un calcul).
 * Les nombres JavaScript sont exacts jusqu'a 2^53 : soit 90 000 milliards d'euros.
 */
export type Cents = number;

/** Lit un montant saisi en francais ("1 234,56", "-12.5", "12 €") ; null si illisible. */
export function parseAmount(text: string): Cents | null {
  const cleaned = text.replace(/[\s  €]/g, '').replace(',', '.');
  if (!/^[-+]?\d+(\.\d{0,2})?$/.test(cleaned)) {
    return null;
  }
  const negative = cleaned.startsWith('-');
  const [whole, fraction = ''] = cleaned.replace(/^[-+]/, '').split('.');
  const cents = Number(whole) * 100 + Number((fraction + '00').slice(0, 2));
  if (!Number.isSafeInteger(cents)) {
    return null;
  }
  return negative ? -cents : cents;
}

const formatter = new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'EUR' });

/** "1 234,56 €" (signe moins si negatif). */
export function formatMoney(cents: Cents): string {
  return formatter.format(cents / 100);
}

/** "+1 234,56 €" / "-12,00 €" : le signe est toujours ecrit (jamais la couleur seule). */
export function formatSigned(cents: Cents): string {
  return (cents > 0 ? '+' : '') + formatMoney(cents);
}

/** Valeur a modifier dans un champ : "1234,56". */
export function toEditable(cents: Cents): string {
  const abs = Math.abs(cents);
  return (cents < 0 ? '-' : '') + Math.floor(abs / 100) + ',' + String(abs % 100).padStart(2, '0');
}

/**
 * Division entiere arrondie "au plus proche, egalite vers le pair" (HALF_EVEN),
 * comme le BigDecimal de l'application desktop.
 */
export function divideHalfEven(numerator: number, denominator: number): number {
  if (denominator === 0) {
    throw new Error('Division par zero');
  }
  const sign = Math.sign(numerator) * Math.sign(denominator);
  const n = Math.abs(numerator);
  const d = Math.abs(denominator);
  const q = Math.floor(n / d);
  const r = n - q * d;
  let result = q;
  if (r * 2 > d || (r * 2 === d && q % 2 === 1)) {
    result = q + 1;
  }
  return sign * result;
}

export function sum(values: Cents[]): Cents {
  return values.reduce((a, b) => a + b, 0);
}
