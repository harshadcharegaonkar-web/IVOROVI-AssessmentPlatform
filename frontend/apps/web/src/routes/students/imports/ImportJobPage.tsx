import { Card, Skeleton } from "@ivorovi/ui";
import { Navigate, useParams } from "react-router";
import { DetailError } from "../../academics/AcademicsShared";
import { continuePath, useImportJob } from "./ImportShared";

/** /students/imports/:id opens the step where the import continues. */
export function ImportJobPage() {
  const { id } = useParams();
  const q = useImportJob(id);
  if (q.isPending) return <Skeleton className="h-96 w-full" />;
  if (q.isError) return <Card><DetailError error={q.error} what="Import" onRetry={() => void q.refetch()} /></Card>;
  return <Navigate replace to={continuePath(q.data)} />;
}

export default ImportJobPage;
