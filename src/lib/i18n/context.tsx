"use client";

import { createContext, useContext, useState, useEffect, useCallback, type ReactNode } from "react";
import { cs, type CsTranslations } from "./translations/cs";
import { en, type EnTranslations } from "./translations/en";

type TranslationMap = CsTranslations & EnTranslations;
type Lang = "cs" | "en";

const STORAGE_KEY = "diarium-lang";
const DEFAULT_LANG: Lang = "cs";

interface I18nContextValue {
  lang: Lang;
  setLang: (l: Lang) => void;
  t: (path: string, params?: Record<string, string | number>) => string;
}

const I18nContext = createContext<I18nContextValue | null>(null);

const translations: Record<Lang, TranslationMap> = { cs: cs as any, en: en as any };

/**
 * Deeply resolve a dot-separated path into the translations object.
 * e.g. t("common.save") → "Uložit"
 * Supports interpolation: t("dashboard.tooltip_mood", { mood: "Skvěle" })
 */
function resolvePath(obj: any, path: string): string | undefined {
  const parts = path.split(".");
  let current = obj;
  for (const part of parts) {
    if (current == null || typeof current !== "object") return undefined;
    current = current[part];
  }
  return typeof current === "string" ? current : undefined;
}

function interpolate(str: string, params?: Record<string, string | number>): string {
  if (!params) return str;
  return str.replace(/\{(\w+)\}/g, (_, key) => {
    const val = params[key];
    return val != null ? String(val) : `{${key}}`;
  });
}

function detectBrowserLang(): Lang {
  if (typeof window === "undefined") return DEFAULT_LANG;
  // URL query param override (for screenshots, e.g. ?lang=en)
  const urlLang = new URLSearchParams(window.location.search).get("lang");
  if (urlLang === "en" || urlLang === "cs") {
    localStorage.setItem(STORAGE_KEY, urlLang);
    return urlLang;
  }
  const stored = localStorage.getItem(STORAGE_KEY) as Lang | null;
  if (stored === "cs" || stored === "en") return stored;
  const navLang = navigator.language?.toLowerCase().startsWith("cs") ? "cs" : "en";
  return navLang;
}

export function I18nProvider({ children }: { children: ReactNode }) {
  const [lang, setLangState] = useState<Lang>(DEFAULT_LANG);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    const detected = detectBrowserLang();
    setLangState(detected);
    // Keep the document language in sync with the detected language, not just
    // after a manual switch, so screen readers and hyphenation use the right one.
    document.documentElement.lang = detected;
    setReady(true);
  }, []);

  const setLang = useCallback((l: Lang) => {
    setLangState(l);
    try {
      localStorage.setItem(STORAGE_KEY, l);
    } catch {}
    // Update HTML lang attribute
    document.documentElement.lang = l;
  }, []);

  const t = useCallback(
    (path: string, params?: Record<string, string | number>): string => {
      const map = translations[lang];
      const resolved = resolvePath(map, path);
      if (resolved === undefined) {
        // Czech may fall back to English while a translation is being added.
        // English must never fall back to Czech: that would silently ship a
        // Czech string in the English UI.
        if (lang !== "en") {
          const enResolved = resolvePath(translations.en, path);
          if (enResolved !== undefined) return interpolate(enResolved, params);
        }
        console.warn(`[i18n] Missing translation key: ${path}`);
        return path;
      }
      return interpolate(resolved, params);
    },
    [lang]
  );

  if (!ready) {
    return <>{children}</>;
  }

  return (
    <I18nContext.Provider value={{ lang, setLang, t }}>
      {children}
    </I18nContext.Provider>
  );
}

export function useTranslation(): I18nContextValue {
  const ctx = useContext(I18nContext);
  if (!ctx) {
    // Fallback when used outside provider (shouldn't happen in normal usage)
    return {
      lang: DEFAULT_LANG,
      setLang: () => {},
      t: (path: string, params?: Record<string, string | number>) => {
        const map = translations[DEFAULT_LANG] as any;
        const resolved = resolvePath(map, path);
        if (resolved === undefined) {
          const enResolved = resolvePath(translations.en, path);
          if (enResolved !== undefined) return interpolate(enResolved, params);
          return path;
        }
        return interpolate(resolved, params);
      },
    };
  }
  return ctx;
}
