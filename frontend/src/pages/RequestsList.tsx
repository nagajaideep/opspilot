import { useState } from "react";
import { Link } from "react-router-dom";
import { api } from "../api";
import { Alert, Loading } from "../components/Alert";
import { RiskBadge, StatusBadge } from "../components/Badges";
import { fmtDateTime, humanize } from "../format";
import type { RequestStatus } from "../types";
import { useAsync } from "../useAsync";

const STATUSES: RequestStatus[] = ["DRAFT", "PENDING_APPROVAL", "APPROVED", "REJECTED", "EXECUTED"];

export function RequestsList() {
  const [status, setStatus] = useState<RequestStatus | "">("");
  const [mine, setMine] = useState(false);
  const [page, setPage] = useState(0);
  const { data, error, loading } = useAsync(() => api.listRequests({ status, mine, page }), [status, mine, page]);

  return (
    <>
      <h1>Change requests</h1>
      <div className="filters">
        <div className="field">
          <label htmlFor="status-filter">Status</label>
          <select id="status-filter" value={status}
                  onChange={(e) => { setStatus(e.target.value as RequestStatus | ""); setPage(0); }}>
            <option value="">All statuses</option>
            {STATUSES.map((s) => <option key={s} value={s}>{humanize(s)}</option>)}
          </select>
        </div>
        <label className="checkbox">
          <input type="checkbox" checked={mine} onChange={(e) => { setMine(e.target.checked); setPage(0); }} />
          Only my requests
        </label>
        <Link className="btn" to="/requests/new">New request</Link>
      </div>

      {error && <Alert kind="error">{error}</Alert>}
      {loading && !data && <Loading label="Loading requests" />}
      {data && data.items.length === 0 && <p>No change requests match these filters.</p>}
      {data && data.items.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption className="muted" style={{ textAlign: "left", padding: ".5rem .75rem" }}>
              {data.totalItems} request{data.totalItems === 1 ? "" : "s"}
            </caption>
            <thead>
              <tr>
                <th scope="col">#</th><th scope="col">Title</th><th scope="col">Cluster</th>
                <th scope="col">Status</th><th scope="col">Agent risk</th><th scope="col">Window start</th>
                <th scope="col">Requester</th>
              </tr>
            </thead>
            <tbody>
              {data.items.map((r) => (
                <tr key={r.id}>
                  <td>{r.id}</td>
                  <td><Link to={`/requests/${r.id}`}>{r.title}</Link></td>
                  <td>{r.clusterName} <span className="muted">({r.environment})</span></td>
                  <td><StatusBadge status={r.status} /></td>
                  <td>{r.agent ? <RiskBadge risk={r.agent.riskLevel} /> : <span className="muted">Not reviewed</span>}</td>
                  <td>{fmtDateTime(r.windowStart)}</td>
                  <td>{r.requester}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {data && data.totalPages > 1 && (
        <nav aria-label="Pagination" className="actions" style={{ marginTop: "1rem" }}>
          <button className="btn btn-ghost" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
          <span>Page {page + 1} of {data.totalPages}</span>
          <button className="btn btn-ghost" disabled={page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>Next</button>
        </nav>
      )}
    </>
  );
}
