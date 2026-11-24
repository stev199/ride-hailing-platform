/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// P1-4: admin shell. VITE_API_BASE (build-time) points the SPA at the backend
// (compose: http://localhost:8080; dev default in AuthContext). Vite dev server
// proxies nothing - the API client uses absolute URLs so tests can route-mock them.
export default defineConfig({
  plugins: [react()],
  server: { port: 5173, strictPort: true },
  preview: { port: 4173, strictPort: true },
  build: { outDir: 'dist', sourcemap: true },
  test: {
    environment: 'jsdom',
    setupFiles: ['src/test/setup.ts'],
    globals: true,
    include: ['src/**/*.test.{ts,tsx}'],
    css: false,
  },
});
