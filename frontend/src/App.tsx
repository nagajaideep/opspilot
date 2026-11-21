import { Navigate, Outlet, Route, Routes, useLocation } from "react-router-dom";
import { useAuth } from "./auth";
import { Layout } from "./components/Layout";
import { Inventory } from "./pages/Inventory";
import { Login } from "./pages/Login";
import { NewRequest } from "./pages/NewRequest";
import { Policy } from "./pages/Policy";
import { RequestDetail } from "./pages/RequestDetail";
import { RequestsList } from "./pages/RequestsList";

/** Redirect to /login when signed out. (UX only - the API enforces access on every call.) */
function RequireAuth() {
  const { user } = useAuth();
  const location = useLocation();
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  return <Outlet />;
}

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      {/* Nested routes: one guard and one Layout shared by every signed-in page. */}
      <Route element={<RequireAuth />}>
        <Route element={<Layout />}>
          <Route path="/requests" element={<RequestsList />} />
          <Route path="/requests/new" element={<NewRequest />} />
          <Route path="/requests/:id" element={<RequestDetail />} />
          <Route path="/inventory" element={<Inventory />} />
          <Route path="/policy" element={<Policy />} />
        </Route>
      </Route>
      <Route path="*" element={<Navigate to="/requests" replace />} />
    </Routes>
  );
}
