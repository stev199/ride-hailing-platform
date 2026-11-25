import '@testing-library/jest-dom/vitest';
import { afterEach } from 'vitest';

// i18n init for every component test (main.tsx does it in prod; vitest imports pages directly).
import { applyLang, i18n } from '../i18n';

// jsdom localStorage/sessionStorage are live per test file; wipe between tests.
afterEach(() => {
  localStorage.clear();
  sessionStorage.clear();
});

afterEach(() => {
  // back to a deterministic language/dir after each test (localStorage is cleared above).
  void i18n.changeLanguage('en');
  applyLang('en');
});
