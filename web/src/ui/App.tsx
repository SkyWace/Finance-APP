import { useState } from 'react';
import { useSession } from '../store/session';
import { LockScreen, RecoveryKeyScreen } from './LockScreen';
import { Shell } from './Shell';

export function App() {
  const session = useSession();
  const [recoveryKey, setRecoveryKey] = useState<string | null>(null);
  if (!session.data) {
    return <LockScreen onCreated={setRecoveryKey} />;
  }
  if (recoveryKey) {
    return <RecoveryKeyScreen recoveryKey={recoveryKey} onDone={() => setRecoveryKey(null)} />;
  }
  return <Shell />;
}
