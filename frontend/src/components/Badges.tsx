import type { RequestStatus, Risk } from "../types";
import { humanize } from "../format";

const RISK_ICON: Record<Risk, string> = { LOW: "●", MEDIUM: "▲", HIGH: "■" };

/** Text + shape + color: color is never the only signal (WCAG 1.4.1). */
export function RiskBadge({ risk }: { risk: Risk }) {
  return (
    <span className={`badge risk-${risk.toLowerCase()}`}>
      <span aria-hidden="true">{RISK_ICON[risk]} </span>
      {humanize(risk)} risk
    </span>
  );
}

export function StatusBadge({ status }: { status: RequestStatus }) {
  return <span className={`badge status-${status.toLowerCase()}`}>{humanize(status)}</span>;
}
