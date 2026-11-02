/**
 * Per-browser UI preferences (sidebar collapsed, selected institution context). Non-sensitive by design:
 * tokens and assessment data are never stored here. Storage can be unavailable (private mode, blocked site data),
 * so every access is guarded and the app works without it.
 */
const PREFIX = "ivorovi.pref.";

function store(): Storage | null {
  try {
    return globalThis.localStorage ?? null;
  } catch {
    return null;
  }
}

export function readPreference(key: string): string | null {
  try {
    return store()?.getItem(PREFIX + key) ?? null;
  } catch {
    return null;
  }
}

export function writePreference(key: string, value: string | null): void {
  try {
    const s = store();
    if (!s) return;
    if (value === null) s.removeItem(PREFIX + key);
    else s.setItem(PREFIX + key, value);
  } catch {
    // ignore: preference simply not remembered
  }
}
