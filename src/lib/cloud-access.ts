let localOnlyMode = false;
let originalFetch: typeof fetch | null = null;

export class LocalOnlyCloudAccessError extends Error {
  constructor() {
    super("Cloud access is disabled in local-only mode");
    this.name = "LocalOnlyCloudAccessError";
  }
}

export function isLocalOnlyMode(): boolean {
  return localOnlyMode;
}

/** Install a page-wide fetch interlock as defense in depth. */
export function installCloudNetworkGate(): void {
  if (typeof window === "undefined" || originalFetch) return;

  originalFetch = window.fetch.bind(window);
  window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
    if (localOnlyMode) return Promise.reject(new LocalOnlyCloudAccessError());
    return originalFetch!(input, init);
  }) as typeof fetch;
}

export function setLocalOnlyMode(enabled: boolean): void {
  if (enabled) installCloudNetworkGate();
  localOnlyMode = enabled;
}

/** Supabase's Auth, REST, and Storage clients all use this guarded transport. */
export const cloudFetch: typeof fetch = (input, init) => {
  if (localOnlyMode) return Promise.reject(new LocalOnlyCloudAccessError());
  const fetcher = originalFetch ?? globalThis.fetch;
  return fetcher(input, init);
};
