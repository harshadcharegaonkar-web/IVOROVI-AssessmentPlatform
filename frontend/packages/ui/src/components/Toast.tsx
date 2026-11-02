import { X } from "lucide-react";
import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from "react";
import { cn, focusRing } from "../lib/cn";

type ToastTone = "success" | "danger" | "info";
interface ToastItem { id: number; tone: ToastTone; title: string; message?: string | undefined }
interface ToastApi { show: (tone: ToastTone, title: string, message?: string) => void }

const ToastContext = createContext<ToastApi | null>(null);
let nextId = 1;

export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([]);
  const dismiss = useCallback((id: number) => setItems((all) => all.filter((t) => t.id !== id)), []);
  const show = useCallback((tone: ToastTone, title: string, message?: string) => {
    const id = nextId++;
    setItems((all) => [...all.slice(-3), { id, tone, title, message }]);
    window.setTimeout(() => dismiss(id), tone === "danger" ? 10000 : 5000);
  }, [dismiss]);
  const api = useMemo(() => ({ show }), [show]);
  return (
    <ToastContext.Provider value={api}>
      {children}
      <div aria-live="polite" className="pointer-events-none fixed bottom-4 right-4 z-50 flex w-[calc(100vw-2rem)] max-w-sm flex-col gap-2">
        {items.map((t) => (
          <div key={t.id} role={t.tone === "danger" ? "alert" : "status"} className={cn(
            "pointer-events-auto flex items-start gap-3 rounded-md border border-border bg-surface px-4 py-3 text-sm shadow-lg",
            t.tone === "danger" && "border-l-4 border-l-danger",
            t.tone === "success" && "border-l-4 border-l-success",
          )}>
            <div className="min-w-0 flex-1">
              <p className="font-medium text-fg">{t.title}</p>
              {t.message && <p className="mt-0.5 text-muted">{t.message}</p>}
            </div>
            <button type="button" onClick={() => dismiss(t.id)} aria-label="Dismiss notification" className={cn("rounded text-muted hover:text-fg", focusRing)}>
              <X aria-hidden className="h-4 w-4" />
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast(): ToastApi {
  const ctx = useContext(ToastContext);
  if (!ctx) throw new Error("useToast must be used inside <ToastProvider>");
  return ctx;
}
