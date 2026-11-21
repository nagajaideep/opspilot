import { useState } from "react";
import { Link, useLocation, useParams } from "react-router-dom";
import { api } from "../api";
import { Alert, Loading } from "../components/Alert";
import { RiskBadge, StatusBadge } from "../components/Badges";
import { fmtDateTime, humanize } from "../format";
import type { AgentView, Action } from "../types";
import { useAsync } from "../useAsync";

const SOURCE_TEXT: Record<AgentView["source"], string> = {
  LLM: "Reviewed by the AI agent (LLM with tool use)",
  RULES: "Reviewed by the rule-based planner (no LLM configured)",
  FALLBACK: "The AI agent was unavailable; reviewed by the rule-based fallback",
};

function AgentPanel({ agent }: { agent: AgentView }) {
  return (
    <section className="card" aria-labelledby="agent-h">
      <h2 id="agent-h" style={{ marginTop: 0 }}>Agent review</h2>
      <p className="actions">
        <RiskBadge risk={agent.riskLevel} />
        {agent.recommendation && (
          <span className={`badge ${agent.recommendation === "REJECT" ? "risk-high" : "risk-low"}`}>
            Recommends: {humanize(agent.recommendation)}
          </span>
        )}
      </p>
      <p className="muted">{SOURCE_TEXT[agent.source]}</p>
      {agent.guardrailApplied && (
        <Alert kind="info">A deterministic safety check raised the agent's assessment. See the summary for why.</Alert>
      )}
      <h3>Summary</h3>
      <p>{agent.summary}</p>
      <h3>Proposed plan</h3>
      <ol className="plan">{agent.plan.map((s, i) => <li key={i}>{s}</li>)}</ol>
      {agent.citations.length > 0 && (
        <>
          <h3>Runbooks cited</h3>
          <ul>{agent.citations.map((c) => <li key={c}><code>{c}</code></li>)}</ul>
        </>
      )}
      <details>
        <summary>What the agent looked at ({agent.trace.length} tool call{agent.trace.length === 1 ? "" : "s"})</summary>
        <ol>
          {agent.trace.map((t, i) => (
            <li key={i}>
              <strong>{t.tool}</strong> <code>{t.arguments}</code>
              <pre>{t.result}</pre>
            </li>
          ))}
        </ol>
      </details>
    </section>
  );
}

export function RequestDetail() {
  const id = Number(useParams().id);
  const location = useLocation();
  const { data, error, loading, reload } = useAsync(() => api.getRequest(id), [id]);
  const [comment, setComment] = useState("");
  const [busy, setBusy] = useState<Action | null>(null);
  const [message, setMessage] = useState<{ kind: "success" | "error"; text: string } | undefined>(
    (location.state as { flash?: string } | null)?.flash ? { kind: "success", text: (location.state as { flash: string }).flash } : undefined,
  );
  const [commentError, setCommentError] = useState<string>();

  async function act(action: Action) {
    setMessage(undefined);
    setCommentError(undefined);
    if (action === "REJECT" && !comment.trim()) {
      setCommentError("Enter a reason for rejecting");
      document.getElementById("comment")?.focus();
      return;
    }
    if (action === "EXECUTE" && !window.confirm("Execute this change against the inventory now?")) return;
    setBusy(action);
    try {
      if (action === "SUBMIT") await api.submit(id);
      if (action === "APPROVE") await api.approve(id, comment.trim());
      if (action === "REJECT") await api.reject(id, comment.trim());
      if (action === "EXECUTE") await api.execute(id);
      setComment("");
      setMessage({ kind: "success", text: `${humanize(action)} succeeded.` });
      await reload();
    } catch (e) {
      setMessage({ kind: "error", text: e instanceof Error ? e.message : "Action failed" });
    } finally {
      setBusy(null);
    }
  }

  if (loading && !data) return <><h1>Change request #{id}</h1><Loading /></>;
  if (error || !data) return <><h1>Change request #{id}</h1><Alert kind="error">{error ?? "Not found"}</Alert></>;

  const r = data.request;
  const canDecide = data.allowedActions.includes("APPROVE");

  return (
    <>
      <p><Link to="/requests">← All requests</Link></p>
      <h1>#{r.id} {r.title}</h1>
      <div aria-live="polite">{message && <Alert kind={message.kind}>{message.text}</Alert>}</div>

      <div className="grid-2">
        <div>
          {r.agent ? <AgentPanel agent={r.agent} /> : (
            <section className="card"><h2 style={{ marginTop: 0 }}>Agent review</h2>
              <p className="muted">Not reviewed yet. Submit the draft to start the automated review.</p></section>
          )}

          {data.allowedActions.length > 0 && (
            <section className="card" aria-labelledby="decide-h">
              <h2 id="decide-h" style={{ marginTop: 0 }}>{canDecide ? "Your decision" : "Next step"}</h2>
              {canDecide && (
                <div className="field">
                  <label htmlFor="comment">Comment</label>
                  <textarea id="comment" value={comment} onChange={(e) => setComment(e.target.value)} maxLength={1000}
                            aria-invalid={commentError ? true : undefined}
                            aria-describedby={commentError ? "comment-error comment-hint" : "comment-hint"} />
                  <p id="comment-hint" className="hint">Optional when approving, required when rejecting.</p>
                  {commentError && <p id="comment-error" className="field-error">{commentError}</p>}
                </div>
              )}
              <div className="actions">
                {data.allowedActions.map((a) => (
                  <button key={a} type="button" disabled={busy !== null}
                          className={`btn ${a === "REJECT" ? "btn-danger" : ""}`} onClick={() => act(a)}>
                    {busy === a ? `${humanize(a)}…` : a === "SUBMIT" ? "Submit for review" : humanize(a)}
                  </button>
                ))}
              </div>
              {busy === "SUBMIT" && <p role="status" className="muted">The agent is reviewing this request…</p>}
            </section>
          )}
        </div>

        <div>
          <section className="card" aria-labelledby="facts-h">
            <h2 id="facts-h" style={{ marginTop: 0 }}>Details</h2>
            <dl className="facts">
              <dt>Status</dt><dd><StatusBadge status={r.status} /></dd>
              <dt>Type</dt><dd>{humanize(r.type)}{r.serverCount > 0 && ` (${r.serverCount})`}</dd>
              <dt>Cluster</dt><dd>{r.clusterName} ({r.environment})</dd>
              <dt>Window</dt><dd>{fmtDateTime(r.windowStart)} – {fmtDateTime(r.windowEnd)}</dd>
              <dt>Requester</dt><dd>{r.requester}</dd>
              {r.approver && <><dt>Decided by</dt><dd>{r.approver}</dd></>}
              {r.decisionComment && <><dt>Comment</dt><dd>{r.decisionComment}</dd></>}
            </dl>
            <h3>Description</h3>
            <p style={{ whiteSpace: "pre-wrap" }}>{r.description}</p>
          </section>
          <section className="card" aria-labelledby="hist-h">
            <h2 id="hist-h" style={{ marginTop: 0 }}>Audit trail</h2>
            <ol className="timeline">
              {data.history.map((h) => (
                <li key={h.id}>
                  <div className="when">{fmtDateTime(h.at)}</div>
                  <strong>{humanize(h.action)}</strong> by {h.actor}
                  {h.details && <div className="muted">{h.details}</div>}
                </li>
              ))}
            </ol>
          </section>
        </div>
      </div>
    </>
  );
}
