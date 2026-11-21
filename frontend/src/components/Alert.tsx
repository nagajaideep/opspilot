import type { ReactNode } from "react";

/** role="alert" interrupts screen readers (errors); role="status" is polite (success/info). */
export function Alert({ kind, children }: { kind: "error" | "success" | "info"; children: ReactNode }) {
  return (
    <div className={`alert alert-${kind}`} role={kind === "error" ? "alert" : "status"}>
      {children}
    </div>
  );
}

export function Loading({ label = "Loading" }: { label?: string }) {
  return <p className="muted" role="status">{label}…</p>;
}
