// Mirrors the backend DTOs (RequestDtos.java, InventoryDtos.java, ...).
export type Role = "REQUESTER" | "APPROVER" | "ADMIN";
export type RequestType = "ADD_SERVERS" | "REMOVE_SERVERS" | "OS_UPGRADE" | "CONFIG_CHANGE";
export type RequestStatus = "DRAFT" | "AGENT_REVIEW" | "PENDING_APPROVAL" | "APPROVED" | "REJECTED" | "EXECUTED";
export type Risk = "LOW" | "MEDIUM" | "HIGH";
export type Action = "SUBMIT" | "APPROVE" | "REJECT" | "EXECUTE";

export interface User { id: number; username: string; displayName: string; role: Role }
export interface LoginResponse { token: string; expiresAt: string; user: User }

export interface Cluster {
  id: number; name: string; environment: "DEV" | "STAGING" | "PROD"; region: string;
  ownerTeam?: string; maxServers: number; activeServers: number; utilization: number;
}

export interface ToolCall { tool: string; arguments: string; result: string }

export interface AgentView {
  riskLevel: Risk; recommendation?: "APPROVE" | "REJECT"; summary: string; plan: string[];
  citations: string[]; source: "LLM" | "FALLBACK" | "RULES"; guardrailApplied: boolean; trace: ToolCall[];
}

export interface ChangeRequest {
  id: number; title: string; description: string; type: RequestType; clusterId: number; clusterName: string;
  environment: string; serverCount: number; windowStart: string; windowEnd: string; status: RequestStatus;
  requester: string; approver?: string; decisionComment?: string; agent?: AgentView;
  createdAt: string; submittedAt?: string; decidedAt?: string; executedAt?: string; version: number;
}

export interface AuditEntry { id: number; requestId?: number; actor: string; action: string; details?: string; at: string }
export interface RequestDetail { request: ChangeRequest; history: AuditEntry[]; allowedActions: Action[] }
export interface Page<T> { items: T[]; page: number; size: number; totalItems: number; totalPages: number }

export interface Violation {
  id: number; entityType: string; entityId: number; entityName: string; rule: string; message: string;
  detectedAt: string; resolvedAt?: string;
}

export interface CreateRequestBody {
  title: string; description: string; type: RequestType; clusterId: number; serverCount: number;
  windowStart: string; windowEnd: string;
}
