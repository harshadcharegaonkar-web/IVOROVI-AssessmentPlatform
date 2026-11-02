import { forwardRef, type TextareaHTMLAttributes } from "react";
import { cn, focusRing } from "../lib/cn";

export const TextArea = forwardRef<HTMLTextAreaElement, TextareaHTMLAttributes<HTMLTextAreaElement>>(function TextArea({ className, ...rest }, ref) {
  return <textarea ref={ref} className={cn(
    "block min-h-20 w-full rounded-lg border border-border-strong bg-surface px-3 py-2 text-sm text-fg shadow-xs placeholder:text-muted aria-[invalid=true]:border-danger",
    focusRing, className)} {...rest} />;
});
