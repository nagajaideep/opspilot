import { useState } from "react";
import { api } from "../api";
import { useAuth } from "../auth";
import { Alert, Loading } from "../components/Alert";
import { fmtDateTime, humanize } from "../format";
import { useAsync } from "../useAsync";

export function Policy() {
  const { user } = useAuth();
  const { data, error, loading, reload } = useAsync(() => api.violations(), []);
  const [status, setStatus] = useState<string>();
  const [busy, setBusy] = useState(false);

  async function scan() {
    setBusy(true);
    setStatus(undefined);
    try {
      const r = await api.runScan();
      setStatus(`Scan complete: ${r.opened} new, ${r.resolved} resolved, ${r.open} open.`);
      await reload();
    } catch (e) {
      setStatus(e instanceof Error ? e.message : "Scan failed");
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <h1>Policy violations</h1>
      <p className="muted">A scan runs nightly against the tagging and ownership standard (owner, cost-center and tier tags, supported OS).</p>
      {user?.role === "ADMIN" && (
        <p><button className="btn" onClick={scan} disabled={busy}>{busy ? "Scanning…" : "Run scan now"}</button></p>
      )}
      <div aria-live="polite">{status && <Alert kind="info">{status}</Alert>}</div>
      {error && <Alert kind="error">{error}</Alert>}
      {loading && !data && <Loading label="Loading violations" />}
      {data && data.length === 0 && <p>No open violations. {user?.role === "ADMIN" ? "Run a scan to check." : ""}</p>}
      {data && data.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption className="muted" style={{ textAlign: "left", padding: ".5rem .75rem" }}>{data.length} open</caption>
            <thead>
              <tr><th scope="col">Rule</th><th scope="col">Resource</th><th scope="col">Problem</th><th scope="col">Detected</th></tr>
            </thead>
            <tbody>
              {data.map((v) => (
                <tr key={v.id}>
                  <td>{humanize(v.rule)}</td>
                  <td>{v.entityName} <span className="muted">({v.entityType.toLowerCase()})</span></td>
                  <td>{v.message}</td>
                  <td>{fmtDateTime(v.detectedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}
