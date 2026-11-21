import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { axeViolations, mockFetch, renderAt, signInAs } from "../test-utils";
import { Login } from "./Login";
import { NewRequest, validate } from "./NewRequest";
import { RequestDetail } from "./RequestDetail";
import { Inventory } from "./Inventory";
import type { RequestDetail as Detail } from "../types";

const detail = (allowedActions: Detail["allowedActions"]): Detail => ({
  request: {
    id: 7, title: "Add 5 payment nodes", description: "Peak traffic", type: "ADD_SERVERS", clusterId: 1,
    clusterName: "payments-prod", environment: "PROD", serverCount: 5, windowStart: "2030-01-08T10:00:00Z",
    windowEnd: "2030-01-08T12:00:00Z", status: "PENDING_APPROVAL", requester: "riya", createdAt: "2030-01-01T00:00:00Z",
    version: 2,
    agent: {
      riskLevel: "HIGH", recommendation: "REJECT", summary: "Exceeds max_servers by 3.", source: "LLM",
      guardrailApplied: true, plan: ["Split the change", "Rollback: remove servers"], citations: ["scale-out-cluster"],
      trace: [{ tool: "checkCapacity", arguments: "{}", result: "{\"fits\":false}" }],
    },
  },
  history: [{ id: 1, actor: "riya", action: "CREATED", at: "2030-01-01T00:00:00Z" }],
  allowedActions,
});

describe("validate (client-side rules mirror the server)", () => {
  const ok = { title: "t", description: "d", clusterId: "1", serverCount: "2",
               windowStart: "2099-01-01T02:00", windowEnd: "2099-01-01T04:00" };
  it("accepts a valid form", () => expect(validate(ok, "ADD_SERVERS")).toEqual({}));
  it("rejects a window longer than 8 hours", () =>
    expect(validate({ ...ok, windowEnd: "2099-01-01T11:00" }, "ADD_SERVERS").windowEnd).toMatch(/8 hours/));
  it("requires 1-50 servers for scaling changes", () =>
    expect(validate({ ...ok, serverCount: "0" }, "ADD_SERVERS").serverCount).toBeDefined());
  it("ignores server count for config changes", () =>
    expect(validate({ ...ok, serverCount: "0" }, "CONFIG_CHANGE")).toEqual({}));
});

describe("Login", () => {
  it("is accessible and shows a server error", async () => {
    mockFetch({ "POST /api/auth/login": { status: 401, body: { detail: "Invalid username or password." } } });
    const { container } = renderAt("/login", "/login", <Login />);
    expect(await axeViolations(container)).toEqual([]);

    await userEvent.type(screen.getByLabelText("Username"), "riya");
    await userEvent.type(screen.getByLabelText("Password"), "wrong");
    await userEvent.click(screen.getByRole("button", { name: "Sign in" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Invalid username or password.");
  });
});

describe("NewRequest", () => {
  it("shows a focused error summary linking to each invalid field", async () => {
    signInAs("REQUESTER", "riya");
    mockFetch({ "GET /api/inventory/clusters": { body: [] } });
    const { container } = renderAt("/new", "/new", <NewRequest />);
    expect(await axeViolations(container)).toEqual([]);

    await userEvent.click(screen.getByRole("button", { name: "Submit for review" }));
    const summary = await screen.findByRole("alert");
    expect(summary).toHaveTextContent(/problems/);
    await waitFor(() => expect(summary).toHaveFocus());
    expect(screen.getByRole("link", { name: /Title: Enter a title/ })).toHaveAttribute("href", "#title");
    expect(screen.getByLabelText("Title")).toHaveAttribute("aria-invalid", "true");
    expect(await axeViolations(container)).toEqual([]);   // still accessible in the error state
  });
});

describe("RequestDetail", () => {
  it("shows the agent review and decision controls for an approver", async () => {
    signInAs("APPROVER");
    mockFetch({ "GET /api/requests/7": { body: detail(["APPROVE", "REJECT"]) } });
    const { container } = renderAt("/requests/7", "/requests/:id", <RequestDetail />);

    expect(await screen.findByRole("heading", { level: 1, name: "#7 Add 5 payment nodes" })).toBeInTheDocument();
    expect(screen.getByText(/High risk/)).toBeInTheDocument();
    expect(screen.getByText(/Recommends: Reject/)).toBeInTheDocument();
    expect(screen.getByText(/deterministic safety check/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Approve" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Execute" })).not.toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);
  });

  it("requires a reason before rejecting", async () => {
    signInAs("APPROVER");
    const fetchMock = mockFetch({ "GET /api/requests/7": { body: detail(["APPROVE", "REJECT"]) } });
    renderAt("/requests/7", "/requests/:id", <RequestDetail />);
    await userEvent.click(await screen.findByRole("button", { name: "Reject" }));
    expect(screen.getByText("Enter a reason for rejecting")).toBeInTheDocument();
    expect(screen.getByLabelText("Comment")).toHaveFocus();
    expect(fetchMock.mock.calls.some(([u, i]) => String(u).includes("reject") && i?.method === "POST")).toBe(false);
  });

  it("shows no decision controls when the server allows none", async () => {
    signInAs("REQUESTER", "riya");
    mockFetch({ "GET /api/requests/7": { body: detail([]) } });
    renderAt("/requests/7", "/requests/:id", <RequestDetail />);
    await screen.findByRole("heading", { level: 1, name: /Add 5 payment nodes/ });
    expect(screen.queryByRole("button", { name: "Approve" })).not.toBeInTheDocument();
  });
});

describe("Inventory", () => {
  it("renders an accessible table with text utilization", async () => {
    signInAs("REQUESTER", "riya");
    mockFetch({ "GET /api/inventory/clusters": { body: [
      { id: 1, name: "edge-cache-prod", environment: "PROD", region: "eu-west-1", ownerTeam: "edge",
        maxServers: 4, activeServers: 4, utilization: 100 },
      { id: 2, name: "ml-train-dev", environment: "DEV", region: "us-west-2", maxServers: 6, activeServers: 2, utilization: 33.3 },
    ] } });
    const { container } = renderAt("/inventory", "/inventory", <Inventory />);
    expect(await screen.findByRole("rowheader", { name: "edge-cache-prod" })).toBeInTheDocument();
    expect(screen.getByText("100%")).toBeInTheDocument();
    expect(screen.getByText("No owner")).toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);
  });
});
