import { z } from "zod";

export const auditFilterSchema = z
  .object({
    action: z.string().trim().regex(/^[a-z0-9_.]*$/, "Use lowercase letters, digits, dots and underscores").max(100),
    outcome: z.enum(["", "SUCCESS", "DENIED", "FAILURE"]),
    from: z.string(),
    to: z.string(),
  })
  .refine((v) => !v.from || !v.to || new Date(v.from) < new Date(v.to), { path: ["to"], message: "'To' must be after 'From'" });

export type AuditFilterInput = z.infer<typeof auditFilterSchema>;

/** Converts form values (local datetime) into API query parameters (ISO-8601 instants). */
export function toQuery(f: AuditFilterInput) {
  return {
    action: f.action || undefined,
    outcome: (f.outcome || undefined) as "SUCCESS" | "DENIED" | "FAILURE" | undefined,
    from: f.from ? new Date(f.from).toISOString() : undefined,
    to: f.to ? new Date(f.to).toISOString() : undefined,
  };
}
