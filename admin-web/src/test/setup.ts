import '@testing-library/jest-dom/vitest';
import { afterEach } from 'vitest';

// jsdom localStorage/sessionStorage are live per test file; wipe between tests.
afterEach(() => {
  localStorage.clear();
  sessionStorage.clear();
});
