import { useEffect, useRef } from "react";
import { NavLink, Outlet, useLocation, useNavigate, useNavigationType } from "react-router-dom";
import { useAuth } from "../auth";
import { humanize } from "../format";

/** Shared shell for every signed-in page; the current page renders into <Outlet />. */
export function Layout() {
  const { user, logout } = useAuth();
  const mainRef = useRef<HTMLElement>(null);
  const location = useLocation();
  const navigate = useNavigate();
  const navigationType = useNavigationType();
  // On in-app navigation (PUSH/REPLACE), move focus to the page's <h1> so screen readers announce the new page.
  // On a fresh page load or back/forward (POP) leave focus alone, so the first Tab reaches the skip link.
  useEffect(() => {
    if (navigationType === "POP") return;
    const h1 = mainRef.current?.querySelector("h1");
    if (h1) {
      h1.setAttribute("tabindex", "-1");
      h1.focus();
    }
  }, [location.key, navigationType]);

  return (
    <>
      <a className="skip-link" href="#main">Skip to main content</a>
      <header className="topbar">
        <div className="brand"><span aria-hidden="true">◆</span> OpsPilot</div>
        <nav aria-label="Main">
          <ul>
            <li><NavLink to="/requests" end>Requests</NavLink></li>
            <li><NavLink to="/requests/new">New request</NavLink></li>
            <li><NavLink to="/inventory">Inventory</NavLink></li>
            <li><NavLink to="/policy">Policy</NavLink></li>
          </ul>
        </nav>
        {user && (
          <div className="user">
            <span>{user.displayName} <span className="muted">({humanize(user.role)})</span></span>
            <button type="button" className="btn btn-ghost" onClick={() => { logout(); navigate("/login", { replace: true }); }}>Sign out</button>
          </div>
        )}
      </header>
      <main id="main" ref={mainRef} tabIndex={-1}><Outlet /></main>
    </>
  );
}
