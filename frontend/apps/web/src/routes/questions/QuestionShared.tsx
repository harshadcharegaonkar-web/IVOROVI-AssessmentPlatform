import type { IvoroviApi } from "@ivorovi/api-client";
import { ContentView, type ContentDoc } from "@ivorovi/content";
import { Badge, TabPanel, Tabs, type BadgeTone } from "@ivorovi/ui";
import { useQueries } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { useLocation, useNavigate } from "react-router";
import { useApi } from "../../api/ApiProvider";

export const TYPE_LABEL: Record<string, string> = {
  SINGLE_CHOICE: "Multiple choice (one answer)", MULTIPLE_CHOICE: "Multiple choice (several answers)", TRUE_FALSE: "True / false",
  SHORT_ANSWER: "Short answer", NUMERIC: "Numeric",
};

const STATE_TONE: Record<string, BadgeTone> = { DRAFT: "neutral", IN_REVIEW: "info", REVIEWED: "info", APPROVED: "success", SUPERSEDED: "neutral", RETIRED: "warning" };
export const STATE_LABEL: Record<string, string> = { DRAFT: "Draft", IN_REVIEW: "In review", REVIEWED: "Reviewed", APPROVED: "Approved", SUPERSEDED: "Superseded", RETIRED: "Retired" };

export function StateBadge({ state }: { state?: string | null }) {
  const s = state ?? "DRAFT";
  return <Badge tone={STATE_TONE[s] ?? "neutral"}>{STATE_LABEL[s] ?? s}</Badge>;
}

/** Question bank section switcher. */
export function QuestionTabs({ children }: { children: ReactNode }) {
  const { pathname } = useLocation();
  const navigate = useNavigate();
  const areas = ["/questions/review", "/questions/taxonomy", "/questions/policies"];
  const value = areas.find((p) => pathname.startsWith(p)) ?? "/questions";
  return (
    <Tabs label="Question bank areas" value={value} onValueChange={(v) => void navigate(v)}
      tabs={[{ value: "/questions", label: "Banks" }, { value: "/questions/review", label: "Review queue" },
        { value: "/questions/taxonomy", label: "Taxonomy" }, { value: "/questions/policies", label: "Policies & quotas" }]}>
      <TabPanel value={value}>{children}</TabPanel>
    </Tabs>
  );
}

/** Reads a clean image through the authenticated API and returns it as a data: URL (allowed by the CSP; no blob: needed). */
export async function fetchImage(api: IvoroviApi, id: string): Promise<string> {
  const r = await api.GET("/api/v1/question-assets/{id}/content", { params: { path: { id } }, parseAs: "blob" });
  const blob = r.data as Blob | undefined;
  if (!blob || !/^image\/(png|jpeg)$/.test(blob.type)) throw new Error("image unavailable");
  return await new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(new Error("image unreadable"));
    reader.readAsDataURL(blob);
  });
}

function imageIds(docs: (ContentDoc | null | undefined)[]): string[] {
  const ids = new Set<string>();
  for (const d of docs) for (const b of d?.blocks ?? []) if (b.type === "image") ids.add(b.asset);
  return [...ids];
}

/** Loads the images used by some content (cached per asset for the session). */
export function useImageSources(docs: (ContentDoc | null | undefined)[]): (id: string) => string | undefined {
  const api = useApi();
  const ids = imageIds(docs);
  const results = useQueries({ queries: ids.map((id) => ({ queryKey: ["question-asset", id], staleTime: 5 * 60_000, retry: false, queryFn: () => fetchImage(api, id) })) });
  const map = new Map(ids.map((id, i) => [id, results[i]?.data]));
  return (id) => map.get(id);
}

/** Content with its images resolved. */
export function Content({ doc, className }: { doc: ContentDoc | null | undefined; className?: string }) {
  const src = useImageSources([doc]);
  return <ContentView doc={doc} imageSrc={src} {...(className ? { className } : {})} />;
}

/** Option list of a choice question (body.options) rendered as content. */
export function OptionsView({ body }: { body: Record<string, unknown> | undefined }) {
  const options = (body?.options as { id: string; content: ContentDoc }[] | undefined) ?? [];
  const docs = options.map((o) => o.content);
  const src = useImageSources(docs);
  if (!options.length) return null;
  return (
    <ol className="mt-3 space-y-2" aria-label="Options">
      {options.map((o) => (
        <li key={o.id} className="flex gap-3 rounded-lg border border-border p-3">
          <span className="font-semibold" aria-hidden>{o.id}.</span><span className="sr-only">Option {o.id}:</span>
          <ContentView doc={o.content} imageSrc={src} className="min-w-0 flex-1 space-y-1" />
        </li>
      ))}
    </ol>
  );
}

export const asDoc = (v: unknown) => v as ContentDoc | undefined;
