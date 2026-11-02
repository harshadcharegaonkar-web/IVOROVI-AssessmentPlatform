import { Switch as RadixSwitch } from "radix-ui";
import { cn, focusRing } from "../lib/cn";

export function Switch({ checked, onCheckedChange, disabled, label, id }: {
  checked: boolean;
  onCheckedChange: (checked: boolean) => void;
  disabled?: boolean;
  /** Accessible name (visually hidden when the switch sits in a labelled row). */
  label: string;
  id?: string;
}) {
  return (
    <RadixSwitch.Root
      id={id}
      checked={checked}
      onCheckedChange={onCheckedChange}
      disabled={disabled}
      aria-label={label}
      className={cn(
        "relative inline-flex h-6 w-11 shrink-0 cursor-pointer items-center rounded-full border border-border transition-colors",
        "data-[state=checked]:bg-primary data-[state=unchecked]:bg-surface-muted disabled:cursor-not-allowed disabled:opacity-50",
        focusRing,
      )}
    >
      <RadixSwitch.Thumb className="block h-5 w-5 translate-x-0.5 rounded-full bg-white shadow transition-transform data-[state=checked]:translate-x-[1.3rem]" />
    </RadixSwitch.Root>
  );
}
