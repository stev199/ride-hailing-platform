import i18next from 'i18next';
import { initReactI18next } from 'react-i18next';
import ar from './ar';
import en from './en';
import fr from './fr';

export const LANGS = ['en', 'fr', 'ar'] as const;
export type Lang = (typeof LANGS)[number];

/** Languages that flip the document direction (spec: Arabic = full RTL). */
export const RTL_LANGS: ReadonlySet<string> = new Set(['ar']);
export const LANG_STORAGE_KEY = 'admin.lang';

export function dirOf(lang: string): 'rtl' | 'ltr' {
  return RTL_LANGS.has(lang) ? 'rtl' : 'ltr';
}

/** Persist + reflect the language onto <html> (lang + dir drive the whole RTL layout). */
export function applyLang(lang: string): void {
  document.documentElement.lang = lang;
  document.documentElement.dir = dirOf(lang);
  localStorage.setItem(LANG_STORAGE_KEY, lang);
}

/** Pinned-fixture drift test (i18n.test.tsx) asserts fr/ar carry exactly these keys. */
export const resources = {
  en: { translation: en },
  fr: { translation: fr },
  ar: { translation: ar },
} as const;

export const i18n = i18next.createInstance();

const storedLang = localStorage.getItem(LANG_STORAGE_KEY);
void i18n.use(initReactI18next).init({
  resources,
  lng: storedLang && (LANGS as readonly string[]).includes(storedLang) ? storedLang : 'en',
  fallbackLng: 'en',
  interpolation: { escapeValue: false },
  returnNull: false,
});
