import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';
import { applyLang, i18n } from './i18n';
import './styles/global.css';

// Reflect the persisted language onto <html lang/dir> before the first render so the
// RTL layout never flashes LTR (Arabic boots RTL directly).
applyLang(i18n.language);

createRoot(document.getElementById('root') as HTMLElement).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
