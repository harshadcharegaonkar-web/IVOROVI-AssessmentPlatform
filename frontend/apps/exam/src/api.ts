/** Delivery API client (same origin; the exam host proxies /api/v1/delivery to the DELIVERY process only). */
export interface PortalInfo { portalKey: string; name: string; organizationName: string; mfaPolicy: "OFF" | "OPTIONAL" | "REQUIRED"; selfRegistration: boolean }
export interface SessionView { id: string; name: string; startsAt: string; endsAt: string; checkedInAt: string | null }
export interface CompensationView { id: string; sessionId: string; sessionName: string; action: string; extensionMinutes: number | null; validUntil: string | null; usedAt: string | null }
export interface Me {
  candidateId: string;
  email: string;
  displayName: string | null;
  mfa: { policy: string; enrolled: boolean; satisfied: boolean };
  sessions: SessionView[];
  compensations: CompensationView[];
}
export interface CheckInResult { sessionId: string; sessionName: string; checkedInAt: string }

export class ApiError extends Error {
  constructor(readonly status: number, readonly code: string, message: string) {
    super(message);
  }
}

const BASE = "/api/v1/delivery";

export async function call<T>(path: string, token: string | null, init: { method?: string; body?: unknown } = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: "application/json" };
  if (token) headers["Authorization"] = `Bearer ${token}`;
  if (init.body !== undefined) headers["Content-Type"] = "application/json";
  const r = await fetch(BASE + path, { method: init.method ?? "GET", headers, body: init.body === undefined ? undefined : JSON.stringify(init.body), credentials: "omit", referrerPolicy: "no-referrer" });
  if (!r.ok) {
    let code = "error";
    let message = `Request failed (${r.status})`;
    try {
      const p = (await r.json()) as { code?: string; detail?: string; message?: string };
      code = p.code ?? code;
      message = p.detail ?? p.message ?? message;
    } catch {
      /* not JSON */
    }
    throw new ApiError(r.status, code, message);
  }
  return (await r.json()) as T;
}

/**
 * KI-83: a question image of the caller's open attempt as a data URL (the CSP allows img-src data: only, and the
 * request carries the bearer token, which an <img src> could not). Only PNG and JPEG are accepted.
 */
export async function imageDataUrl(path: string, token: string): Promise<string> {
  const r = await fetch(BASE + path, { headers: { Authorization: `Bearer ${token}` }, credentials: "omit", referrerPolicy: "no-referrer", cache: "no-store" });
  if (!r.ok) throw new ApiError(r.status, "image_unavailable", `Image unavailable (${r.status})`);
  const blob = await r.blob();
  if (!/^image\/(png|jpeg)$/.test(blob.type)) throw new ApiError(415, "image_unavailable", "Unsupported image");
  return await new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(new ApiError(0, "image_unavailable", "Image unreadable"));
    reader.readAsDataURL(blob);
  });
}

/** Clock offset: server time minus local time, corrected by half the round trip (server time is authoritative, C-3). */
export async function measureClock(): Promise<{ offsetMs: number; rttMs: number }> {
  const t0 = Date.now();
  const r = await call<{ serverTime: string }>("/time", null);
  const t1 = Date.now();
  const rtt = t1 - t0;
  return { offsetMs: Math.round(Date.parse(r.serverTime) - (t0 + rtt / 2)), rttMs: rtt };
}
