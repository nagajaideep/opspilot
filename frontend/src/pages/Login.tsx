import { useState, type FormEvent } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { useAuth } from "../auth";
import { Alert } from "../components/Alert";

export function Login() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(undefined);
    try {
      await login(username.trim(), password);
      const from = (location.state as { from?: string } | null)?.from ?? "/requests";
      navigate(from, { replace: true });
    } catch (err) {
      setError(err instanceof Error ? err.message : "Sign-in failed");
    } finally {
      setBusy(false);
    }
  }

  return (
    <main id="main" className="login">
      <div className="card">
        <h1>Sign in to OpsPilot</h1>
        {error && <Alert kind="error">{error}</Alert>}
        <form onSubmit={onSubmit} noValidate>
          <div className="field">
            <label htmlFor="username">Username</label>
            <input id="username" autoComplete="username" required value={username}
                   onChange={(e) => setUsername(e.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="password">Password</label>
            <input id="password" type="password" autoComplete="current-password" required value={password}
                   onChange={(e) => setPassword(e.target.value)} />
          </div>
          <button className="btn" type="submit" disabled={busy || !username || !password}>
            {busy ? "Signing in…" : "Sign in"}
          </button>
        </form>
      </div>
      <section className="card demo-users" aria-labelledby="demo-h">
        <h2 id="demo-h">Demo accounts</h2>
        <p>Password for all: <code>opspilot-demo</code></p>
        <ul>
          <li><code>riya</code>, <code>sam</code> – Requester</li>
          <li><code>alex</code> – Approver</li>
          <li><code>ada</code> – Admin</li>
        </ul>
      </section>
    </main>
  );
}
