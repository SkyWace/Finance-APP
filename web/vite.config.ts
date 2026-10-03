import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// base './' : le site fonctionne dans n'importe quel dossier d'hebergement.
export default defineConfig({
  base: './',
  plugins: [react()],
  build: { outDir: 'dist', sourcemap: false },
  test: { environment: 'node' },
});
