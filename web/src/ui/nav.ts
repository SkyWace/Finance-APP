import { createContext, useContext } from 'react';
import type { TransactionType } from '../domain/types';

export type PageId = 'dashboard' | 'accounts' | 'transactions' | 'upcoming' | 'recurring' | 'available' | 'forecast'
  | 'budgets' | 'goals' | 'categories' | 'settings';

export interface Nav {
  go: (page: PageId) => void;
  newOperation: (type?: TransactionType) => void;
}

export const NavContext = createContext<Nav>({ go: () => {}, newOperation: () => {} });

export function useNav(): Nav {
  return useContext(NavContext);
}
