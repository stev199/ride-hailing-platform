import { describe, expect, it } from 'vitest';
import { applyLang, dirOf, i18n, LANGS } from '../index';
import ar from '../ar';
import en from '../en';
import fr from '../fr';

/** Recursively flatten a nested string table into dot keys. */
function flatten(obj: Record<string, unknown>, prefix = ''): string[] {
  return Object.entries(obj).flatMap(([k, v]) => {
    const key = prefix ? `${prefix}.${k}` : k;
    if (typeof v === 'string') return [key];
    return flatten(v as Record<string, unknown>, key);
  });
}

/** Look up a dot key in a string table. */
function get(table: Record<string, unknown>, key: string): string {
  let cur: unknown = table;
  for (const part of key.split('.')) cur = (cur as Record<string, unknown>)[part];
  return cur as string;
}

describe('admin i18n resources (P1-4c drift discipline, D67-style)', () => {
  const enKeys = flatten(en).sort();

  it('fr and ar carry EXACTLY the same key set as en', () => {
    expect(flatten(fr).sort()).toEqual(enKeys);
    expect(flatten(ar).sort()).toEqual(enKeys);
  });

  it('every ar value differs from en (script guarantees translation happened)', () => {
    // Arabic shares no script with English, so ANY equality would mean an untranslated
    // string - except the lang.* codes/autonym which are identical BY DESIGN everywhere.
    const byDesign = new Set(['lang.en', 'lang.fr', 'lang.ar']);
    for (const key of enKeys) {
      if (byDesign.has(key)) continue;
      expect(get(ar as unknown as Record<string, unknown>, key), `ar key ${key}`).not.toBe(
        get(en as unknown as Record<string, unknown>, key),
      );
    }
  });

  it('fr values differ where French has its own wording (spot-checked, not exhaustive)', () => {
    // French legitimately keeps some English words ('Actions', 'Type', 'Version'), so an
    // exhaustive not-equal sweep would false-positive; these spot checks pin the core
    // chrome to real French. fr QUALITY evidence: locale.spec.ts French screenshots.
    expect(fr.nav.users).toBe('Utilisateurs');
    expect(fr.auth.signIn).toBe('Se connecter');
    expect(fr.users.title).toBe('Utilisateurs');
    expect(fr.audit.title).not.toBe(en.audit.title);
    expect(fr.config.title).not.toBe(en.config.title);
  });

  it('interpolation placeholders survive translation', () => {
    const placeholders = (s: string): string[] => (s.match(/{{\s*\w+\s*}}/g) ?? []).sort();
    const enKeys = flatten(en).sort();
    for (const [table, strings] of [['fr', fr], ['ar', ar]] as const) {
      for (const key of enKeys) {
        expect(
          placeholders(get(strings as unknown as Record<string, unknown>, key)),
          `${table} ${key}`,
        ).toEqual(placeholders(get(en as unknown as Record<string, unknown>, key)));
      }
    }
  });
});

describe('language + direction plumbing', () => {
  it('dirOf marks only ar as rtl', () => {
    expect(dirOf('ar')).toBe('rtl');
    expect(dirOf('fr')).toBe('ltr');
    expect(dirOf('en')).toBe('ltr');
    expect(dirOf('zz')).toBe('ltr');
  });

  it('applyLang reflects lang+dir onto <html> and persists the choice', () => {
    applyLang('ar');
    expect(document.documentElement.lang).toBe('ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(localStorage.getItem('admin.lang')).toBe('ar');
    applyLang('en');
    expect(document.documentElement.dir).toBe('ltr');
  });

  it('i18n boots with the persisted or default language', async () => {
    await i18n.changeLanguage('fr');
    expect(i18n.language).toBe('fr');
    expect(i18n.t('nav.users')).toBe('Utilisateurs');
    expect(i18n.t('users.notice.created', { name: 'Ada' })).toBe('Utilisateur « Ada » créé');
    await i18n.changeLanguage('ar');
    expect(i18n.t('nav.users')).toBe('المستخدمون');
    // missing interpolation params keep the placeholders visible (never crashes)
    expect(i18n.t('audit.title')).toContain('{{count}}');
    expect(LANGS).toEqual(['en', 'fr', 'ar']);
    await i18n.changeLanguage('en');
  });
});
