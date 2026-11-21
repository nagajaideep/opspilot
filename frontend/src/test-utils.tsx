import axe from "axe-core";
import { render } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import type { ReactElement } from "react";
import { vi } from "vitest";
import { AuthProvider } from "./auth";

/** Run axe-core on rendered DOM. Color contrast needs real rendering, so it's checked in the browser run instead. */
export async function axeViolations(container: Element) {
  const results = await axe.run(container, { rules: { "color-contrast": { enabled: false } } });
  return results.violations.map((v) => `${v.id}: ${v.help} (${v.nodes.length} node(s))`);
}

/** Fake fetch: map "METHOD /path" prefixes to JSON responses. */
export function mockFetch(routes: Record<string, { status?: number; body: unknown }>) {
  const fn = vi.fn(async (url: string, init?: RequestInit) => {
    const key = `${init?.method ?? "GET"} ${url}`;
    const match = Object.keys(routes).find((k) => key.startsWith(k));
    if (!match) return new Response(JSON.stringify({ detail: `No mock for ${key}` }), { status: 500 });
    const r = routes[match];
    return new Response(JSON.stringify(r.body), { status: r.status ?? 200 });
  });
  vi.stubGlobal("fetch", fn);
  return fn;
}

export function signInAs(role: "REQUESTER" | "APPROVER" | "ADMIN", username = "alex") {
  sessionStorage.setItem("opspilot.token", "test-token");
  sessionStorage.setItem("opspilot.user", JSON.stringify({ id: 3, username, displayName: username, role }));
}

export function renderAt(path: string, routePattern: string, element: ReactElement) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <Routes><Route path={routePattern} element={element} /></Routes>
      </AuthProvider>
    </MemoryRouter>,
  );
}
