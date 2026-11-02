/** RFC 9457 problem document as produced by the Ivorovi API. */
export interface Problem {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  code?: string;
  traceId?: string;
  errors?: { field: string; message: string }[];
}

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly traceId: string | undefined;
  readonly fieldErrors: { field: string; message: string }[];

  constructor(status: number, problem: Problem | undefined) {
    super(problem?.detail ?? defaultMessage(status));
    this.name = "ApiError";
    this.status = status;
    this.code = problem?.code ?? (status === 0 ? "network_error" : "http_" + status);
    this.traceId = problem?.traceId;
    this.fieldErrors = problem?.errors ?? [];
  }
}

function defaultMessage(status: number): string {
  if (status === 0) return "The server could not be reached. Check your connection and try again.";
  if (status === 401) return "Your session has expired. Please sign in again.";
  if (status === 403) return "You do not have permission for this action.";
  if (status === 404) return "The requested item was not found.";
  if (status >= 500) return "The server encountered an error. Please try again later.";
  return "The request failed.";
}

export async function toApiError(response: Response): Promise<ApiError> {
  let problem: Problem | undefined;
  const type = response.headers.get("content-type") ?? "";
  if (type.includes("json")) {
    try {
      problem = (await response.clone().json()) as Problem;
    } catch {
      problem = undefined;
    }
  }
  return new ApiError(response.status, problem);
}
