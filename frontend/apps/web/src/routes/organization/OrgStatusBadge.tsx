import { Badge } from "@ivorovi/ui";

export function OrgStatusBadge({ status }: { status: string | undefined }) {
  return <Badge tone={status === "ACTIVE" ? "success" : "neutral"}>{status === "ACTIVE" ? "Active" : "Inactive"}</Badge>;
}
