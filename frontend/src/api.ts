import type {
  ChangeRequest, Cluster, CreateRequestBody, LoginResponse, Page, RequestDetail, RequestStatus, Violation,
} from "./types";

const TOKEN_KEY = "opspilot.token";

/** Error carrying the server's RFC 7807 problem details (title, detail, per-field errors). */
export class ApiError extends Error {
  constructor(public status: number, message: string, public fieldErrors: Record<string, string> = {}) {
    super(message);
  }
}

export const tokenStore = {
  get: () => sessionStorage.getItem(TOKEN_KEY),
  set: (t: string) => sessionStorage.setItem(TOKEN_KEY, t),
  clear: () => sessionStorage.removeItem(TOKEN_KEY),
};

/** Called on 401 so the app can return to the login page. Set by AuthProvider. */
let onUnauthorized: () => void = () => {};
export function setUnauthorizedHandler(fn: () => void) { onUnauthorized = fn; }

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { Accept: "application/json" };
  const token = tokenStore.get();
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers["Content-Type"] = "application/json";

  let res: Response;
  try {
    res = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  } catch {
    throw new ApiError(0, "Cannot reach the server. Check your connection and try again.");
  }

  if (res.status === 401 && token) {
    tokenStore.clear();
    onUnauthorized();
  }
  const text = await res.text();
  const data = text ? JSON.parse(text) : undefined;
  if (!res.ok) {
    const message = data?.detail ?? data?.title ?? `Request failed (${res.status})`;
    throw new ApiError(res.status, message, data?.errors ?? {});
  }
  return data as T;
}

export const api = {
  login: (username: string, password: string) =>
    request<LoginResponse>("POST", "/api/auth/login", { username, password }),

  listRequests: (opts: { status?: RequestStatus | ""; mine?: boolean; page?: number }) => {
    const q = new URLSearchParams();
    if (opts.status) q.set("status", opts.status);
    if (opts.mine) q.set("mine", "true");
    q.set("page", String(opts.page ?? 0));
    return request<Page<ChangeRequest>>("GET", `/api/requests?${q}`);
  },
  getRequest: (id: number) => request<RequestDetail>("GET", `/api/requests/${id}`),
  createRequest: (body: CreateRequestBody) => request<ChangeRequest>("POST", "/api/requests", body),
  submit: (id: number) => request<ChangeRequest>("POST", `/api/requests/${id}/submit`),
  approve: (id: number, comment: string) => request<ChangeRequest>("POST", `/api/requests/${id}/approve`, { comment }),
  reject: (id: number, comment: string) => request<ChangeRequest>("POST", `/api/requests/${id}/reject`, { comment }),
  execute: (id: number) => request<ChangeRequest>("POST", `/api/requests/${id}/execute`),

  clusters: () => request<Cluster[]>("GET", "/api/inventory/clusters"),
  violations: () => request<Violation[]>("GET", "/api/policy/violations"),
  runScan: () => request<{ opened: number; resolved: number; open: number }>("POST", "/api/policy/scan"),
};
