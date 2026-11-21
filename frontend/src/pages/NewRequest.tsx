import { useEffect, useRef, useState, type FormEvent } from "react";
import { useNavigate } from "react-router-dom";
import { api, ApiError } from "../api";
import { Alert, Loading } from "../components/Alert";
import { humanize } from "../format";
import type { RequestType } from "../types";
import { useAsync } from "../useAsync";

const TYPES: RequestType[] = ["ADD_SERVERS", "REMOVE_SERVERS", "OS_UPGRADE", "CONFIG_CHANGE"];
type Field = "title" | "description" | "clusterId" | "serverCount" | "windowStart" | "windowEnd";
const LABELS: Record<Field, string> = {
  title: "Title", description: "Description", clusterId: "Cluster", serverCount: "Number of servers",
  windowStart: "Window start", windowEnd: "Window end",
};

/** Same rules as the server's Bean Validation, so users get instant feedback. The server still re-checks. */
export function validate(f: Record<Field, string>, type: RequestType): Partial<Record<Field, string>> {
  const e: Partial<Record<Field, string>> = {};
  if (!f.title.trim()) e.title = "Enter a title";
  else if (f.title.length > 140) e.title = "Title must be 140 characters or fewer";
  if (!f.description.trim()) e.description = "Describe the change and why it is needed";
  if (!f.clusterId) e.clusterId = "Choose a cluster";
  const n = Number(f.serverCount);
  const scaling = type === "ADD_SERVERS" || type === "REMOVE_SERVERS";
  if (scaling && (!Number.isInteger(n) || n < 1 || n > 50)) e.serverCount = "Enter a whole number from 1 to 50";
  if (!f.windowStart) e.windowStart = "Enter the window start";
  else if (new Date(f.windowStart) <= new Date()) e.windowStart = "Window must start in the future";
  if (!f.windowEnd) e.windowEnd = "Enter the window end";
  if (f.windowStart && f.windowEnd) {
    const hours = (new Date(f.windowEnd).getTime() - new Date(f.windowStart).getTime()) / 3_600_000;
    if (hours <= 0) e.windowEnd = "Window end must be after the start";
    else if (hours > 8) e.windowEnd = "Window can be at most 8 hours";
  }
  return e;
}

export function NewRequest() {
  const navigate = useNavigate();
  const clusters = useAsync(() => api.clusters(), []);
  const [type, setType] = useState<RequestType>("ADD_SERVERS");
  const [form, setForm] = useState<Record<Field, string>>({
    title: "", description: "", clusterId: "", serverCount: "1", windowStart: "", windowEnd: "",
  });
  const [errors, setErrors] = useState<Partial<Record<Field, string>>>({});
  const [serverError, setServerError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const summaryRef = useRef<HTMLDivElement>(null);
  // Bumped on every failed submit; the effect runs AFTER React has rendered the summary, then focuses it.
  const [focusSummary, setFocusSummary] = useState(0);
  useEffect(() => { if (focusSummary > 0) summaryRef.current?.focus(); }, [focusSummary]);
  const scaling = type === "ADD_SERVERS" || type === "REMOVE_SERVERS";

  const set = (k: Field) => (e: { target: { value: string } }) => setForm({ ...form, [k]: e.target.value });

  async function onSubmit(e: FormEvent, submitForReview: boolean) {
    e.preventDefault();
    setServerError(undefined);
    const found = validate(form, type);
    setErrors(found);
    if (Object.keys(found).length > 0) {
      setFocusSummary((n) => n + 1);   // jump to the error summary
      return;
    }
    setBusy(true);
    try {
      const created = await api.createRequest({
        title: form.title.trim(), description: form.description.trim(), type,
        clusterId: Number(form.clusterId), serverCount: scaling ? Number(form.serverCount) : 0,
        // datetime-local is the user's local time; send an unambiguous UTC instant.
        windowStart: new Date(form.windowStart).toISOString(), windowEnd: new Date(form.windowEnd).toISOString(),
      });
      if (submitForReview) await api.submit(created.id);
      navigate(`/requests/${created.id}`, { state: { flash: submitForReview ? "Request submitted. The agent's review is below." : "Draft saved." } });
    } catch (err) {
      if (err instanceof ApiError && Object.keys(err.fieldErrors).length > 0) {
        // Map server-side validation keys back to our inputs.
        const fe = err.fieldErrors;
        setErrors({
          title: fe.title, description: fe.description, clusterId: fe.clusterId,
          serverCount: fe.serverCount ?? fe.serverCountValid, windowStart: fe.windowStart, windowEnd: fe.windowEnd ?? fe.windowValid,
        });
        setFocusSummary((n) => n + 1);
      } else {
        setServerError(err instanceof Error ? err.message : "Could not create the request");
      }
    } finally {
      setBusy(false);
    }
  }

  // Same order as the fields appear on screen.
  const FIELD_ORDER: Field[] = ["title", "clusterId", "serverCount", "windowStart", "windowEnd", "description"];
  const errorList = FIELD_ORDER.filter((k) => errors[k]).map((k) => [k, errors[k]] as const);
  const fieldProps = (k: Field) => ({
    id: k, name: k, value: form[k], onChange: set(k),
    "aria-invalid": errors[k] ? true : undefined,
    "aria-describedby": [errors[k] ? `${k}-error` : "", `${k}-hint`].join(" ").trim(),
  });
  const err = (k: Field) => errors[k] && <p id={`${k}-error`} className="field-error">{errors[k]}</p>;

  return (
    <>
      <h1>New change request</h1>
      {serverError && <Alert kind="error">{serverError}</Alert>}
      {errorList.length > 0 && (
        <div className="alert alert-error" role="alert" tabIndex={-1} ref={summaryRef} aria-labelledby="err-sum">
          <h2 id="err-sum" style={{ marginTop: 0 }}>There {errorList.length === 1 ? "is a problem" : `are ${errorList.length} problems`}</h2>
          <ul>{errorList.map(([k, v]) => <li key={k}><a href={`#${k}`}>{LABELS[k]}: {v}</a></li>)}</ul>
        </div>
      )}
      {clusters.loading && <Loading label="Loading clusters" />}
      <form className="card" noValidate onSubmit={(e) => onSubmit(e, true)} aria-busy={busy}>
        <div className="field">
          <label htmlFor="title">Title</label>
          <input {...fieldProps("title")} maxLength={140} />
          <p id="title-hint" className="hint">A short summary, e.g. “Add 2 nodes to search-prod for re-indexing”.</p>
          {err("title")}
        </div>
        <div className="row">
          <div className="field">
            <label htmlFor="type">Change type</label>
            <select id="type" value={type} onChange={(e) => setType(e.target.value as RequestType)}>
              {TYPES.map((t) => <option key={t} value={t}>{humanize(t)}</option>)}
            </select>
          </div>
          <div className="field">
            <label htmlFor="clusterId">Cluster</label>
            <select {...fieldProps("clusterId")}>
              <option value="">Choose a cluster</option>
              {clusters.data?.map((c) => (
                <option key={c.id} value={c.id}>{c.name} ({c.environment}, {c.activeServers}/{c.maxServers})</option>
              ))}
            </select>
            <p id="clusterId-hint" className="hint">Shows active / maximum servers.</p>
            {err("clusterId")}
          </div>
          {scaling && (
            <div className="field">
              <label htmlFor="serverCount">Number of servers</label>
              <input {...fieldProps("serverCount")} type="number" min={1} max={50} inputMode="numeric" />
              <p id="serverCount-hint" className="hint">1 to 50.</p>
              {err("serverCount")}
            </div>
          )}
        </div>
        <fieldset className="row" style={{ border: 0, padding: 0, margin: 0 }}>
          <legend className="hint" style={{ marginBottom: ".5rem" }}>
            Maintenance window (your local time). Production: Tuesday–Thursday, 02:00–06:00 in the cluster’s region.
          </legend>
          <div className="field">
            <label htmlFor="windowStart">Window start</label>
            <input {...fieldProps("windowStart")} type="datetime-local" />
            <p id="windowStart-hint" className="hint">Must be in the future.</p>
            {err("windowStart")}
          </div>
          <div className="field">
            <label htmlFor="windowEnd">Window end</label>
            <input {...fieldProps("windowEnd")} type="datetime-local" />
            <p id="windowEnd-hint" className="hint">At most 8 hours after the start.</p>
            {err("windowEnd")}
          </div>
        </fieldset>
        <div className="field">
          <label htmlFor="description">Description</label>
          <textarea {...fieldProps("description")} maxLength={4000} />
          <p id="description-hint" className="hint">What changes, why, and any known risks.</p>
          {err("description")}
        </div>
        <div className="actions">
          <button className="btn" type="submit" disabled={busy}>{busy ? "Submitting…" : "Submit for review"}</button>
          <button className="btn btn-ghost" type="button" disabled={busy} onClick={(e) => onSubmit(e, false)}>Save as draft</button>
        </div>
      </form>
    </>
  );
}
