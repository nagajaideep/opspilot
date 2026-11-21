import { api } from "../api";
import { Alert, Loading } from "../components/Alert";
import { useAsync } from "../useAsync";

export function Inventory() {
  const { data, error, loading } = useAsync(() => api.clusters(), []);
  return (
    <>
      <h1>Inventory</h1>
      {error && <Alert kind="error">{error}</Alert>}
      {loading && <Loading label="Loading clusters" />}
      {data && (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th scope="col">Cluster</th><th scope="col">Environment</th><th scope="col">Region</th>
                <th scope="col">Owner</th><th scope="col">Servers</th><th scope="col">Utilization</th>
              </tr>
            </thead>
            <tbody>
              {data.map((c) => (
                <tr key={c.id}>
                  <th scope="row">{c.name}</th>
                  <td>{c.environment}</td>
                  <td>{c.region}</td>
                  <td>{c.ownerTeam ?? <span className="badge risk-high">No owner</span>}</td>
                  <td>{c.activeServers} / {c.maxServers}</td>
                  <td>
                    <div className="actions" style={{ alignItems: "center" }}>
                      <div className={`meter ${c.utilization >= 100 ? "full" : c.utilization > 85 ? "warn" : ""}`}
                           role="meter" aria-valuemin={0} aria-valuemax={100} aria-valuenow={c.utilization}
                           aria-label={`${c.name} utilization`}>
                        <span style={{ width: `${Math.min(c.utilization, 100)}%` }} />
                      </div>
                      <span>{c.utilization}%</span>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}
