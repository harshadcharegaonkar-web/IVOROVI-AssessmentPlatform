import { measureClock } from "./api";
import { deviceKey } from "./device";

export interface CheckItem { id: string; label: string; ok: boolean | null; detail: string }
export interface SystemCheck { items: CheckItem[]; summary: Record<string, string | number | boolean>; clockOffsetMs: number | null }

function browser(): { name: string; supported: boolean } {
  const ua = navigator.userAgent;
  const m = /(Edg|Chrome|Firefox|Version)\/(\d+)/.exec(ua);
  const name = /Edg\//.test(ua) ? "Edge" : /Chrome\//.test(ua) ? "Chrome" : /Firefox\//.test(ua) ? "Firefox" : /Safari\//.test(ua) ? "Safari" : "Other";
  const major = m ? Number(m[2]) : 0;
  const min: Record<string, number> = { Chrome: 110, Edge: 110, Firefox: 115, Safari: 16 };
  return { name, supported: major >= (min[name === "Safari" ? "Safari" : name] ?? 999) };
}

export async function runSystemCheck(): Promise<SystemCheck> {
  const items: CheckItem[] = [];
  const b = browser();
  items.push({ id: "browser", label: "Browser", ok: b.supported, detail: b.supported ? `${b.name}, supported` : `${b.name}: use a current Chrome, Edge, Firefox or Safari` });
  const w = window.screen?.width ?? window.innerWidth;
  const h = window.screen?.height ?? window.innerHeight;
  items.push({ id: "screen", label: "Screen", ok: Math.min(w, h) >= 320, detail: `${w} × ${h}` });
  items.push({ id: "network", label: "Network", ok: navigator.onLine, detail: navigator.onLine ? "Online" : "Offline" });
  let clockOffsetMs: number | null = null;
  let latency = -1;
  try {
    const c = await measureClock();
    clockOffsetMs = c.offsetMs;
    latency = c.rttMs;
    items.push({ id: "latency", label: "Connection to the exam service", ok: c.rttMs < 1500, detail: `${c.rttMs} ms round trip` });
    items.push({
      id: "clock",
      label: "Device clock",
      ok: Math.abs(c.offsetMs) < 120_000,
      detail: `${c.offsetMs >= 0 ? "+" : ""}${(c.offsetMs / 1000).toFixed(1)} s from server time (the server clock decides all times)`,
    });
  } catch {
    items.push({ id: "latency", label: "Connection to the exam service", ok: false, detail: "Not reachable" });
  }
  let camera: string;
  try {
    const p = await navigator.permissions.query({ name: "camera" as PermissionName });
    camera = p.state;
  } catch {
    camera = "unsupported";
  }
  items.push({ id: "camera", label: "Camera permission", ok: null, detail: camera === "granted" ? "Allowed" : camera === "denied" ? "Blocked (needed only for proctored exams)" : "Will be asked when an exam needs it" });
  const fullscreen = typeof document.documentElement.requestFullscreen === "function";
  items.push({ id: "fullscreen", label: "Full screen", ok: fullscreen ? true : null, detail: fullscreen ? "Available" : "Not available on this device" });
  let storage = false;
  try {
    const k = await deviceKey();
    storage = !k.extractable;
    items.push({ id: "device", label: "Device key", ok: !k.extractable, detail: `Created on this device (${k.fingerprint}), cannot be copied` });
  } catch {
    items.push({ id: "device", label: "Device key", ok: false, detail: "Private browsing or storage blocked" });
  }
  return {
    items,
    clockOffsetMs,
    summary: { browser: b.name, browserSupported: b.supported, screen: `${w}x${h}`, online: navigator.onLine, latencyMs: latency, cameraPermission: camera, fullscreen, storage },
  };
}
