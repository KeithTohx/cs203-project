import { mockApi, showMockBanner } from "./mock.js";
// The ONLY file that knows URLs. Relative paths work locally and on Azure.
export class ApiError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}
const csrfToken = () => {
  const cookie = document.cookie.split("; ").find(entry => entry.startsWith("XSRF-TOKEN="));
  return cookie ? decodeURIComponent(cookie.split("=")[1]) : null;
};
// spring security deletes the csrf cookie on logout and when a session is ended (e.g. after a password
// change), and only sends a new one with the next response. a post sent before that is rejected with 403,
// so when the cookie is missing, ask for the page headers first just to get a fresh one
async function ensureCsrfToken() {
  if (csrfToken()) return;
  try { await fetch("/", { method: "HEAD", credentials: "same-origin" }); } catch {}
}
// turns a failed response into text a user can act on. the backend's own message is kept when it's
// meant for people (validation, "already taken", "not found"), but not for server errors or missing
// endpoints, where it's internal detail like "No static resource api/user."
function friendlyMessage(status, backendMessage) {
  if (status >= 500) return "Something went wrong on the server. Please try again.";

  const isMissingEndpoint = status === 405
    || (status === 404 && (!backendMessage || backendMessage.startsWith("No static resource")));
  if (isMissingEndpoint) return "This feature isn't available yet.";

  return backendMessage || `Request failed (${status})`;
}
// body is sent as json, except form fields (URLSearchParams), which go as a normal form post
async function request(path, method = "GET", body) {
  const headers = {};
  const isForm = body instanceof URLSearchParams;
  if (body && !isForm) headers["Content-Type"] = "application/json";
  if (method !== "GET") {
    await ensureCsrfToken();
    if (csrfToken()) headers["X-XSRF-TOKEN"] = csrfToken();
  }

  let requestBody;
  if (isForm) requestBody = body;   // fetch sets the form content type itself
  else if (body) requestBody = JSON.stringify(body);

  const response = await fetch(path, {method, headers, credentials: "same-origin", body: requestBody});
  if (!response.ok) {
    let backendMessage;
    try { backendMessage = (await response.json()).message; } catch {}
    throw new ApiError(response.status, friendlyMessage(response.status, backendMessage));
  }
  return response.status === 204 ? null : response.json().catch(() => null);
}
const realApi = {
  // spring security's formLogin reads form fields, not json
  login:      (email, password) => request("/api/auth/login", "POST", new URLSearchParams({email, password})),
  logout:     () => request("/api/auth/logout", "POST"),
  register:   details => request("/api/user/register", "POST", details),   // {username, email, password}; role is always USER
  me:         () => request("/api/auth/me"),
  // the account is always the logged-in one, from the session. the email is the login, so only the username changes
  updateMe:   details => request("/api/user/me", "PATCH", details),                   // {username} -> the account
  changePassword: passwords => request("/api/user/me/password", "PUT", passwords),    // {currentPassword, newPassword} -> 204, then every session is ended
  deleteMe:   currentPassword => request("/api/user/me", "DELETE", {currentPassword}),  // -> 204, the account and everything in it is gone and every session is ended
  itineraries:userId => request(`/api/user/${userId}/itinerary`),   // that user's own itineraries
  itinerary:  id => request(`/api/itinerary/${id}`),
  addItinerary:itinerary => request("/api/itinerary", "POST", itinerary),
  updateItinerary:(id, itinerary) => request(`/api/itinerary/${id}`, "PUT", itinerary),
  deleteItinerary:id => request(`/api/itinerary/${id}`, "DELETE"),
  countries:  () => request("/api/country"),
  itineraryActivities:id => request(`/api/itinerary/${id}/activity`),
  activity:   id => request(`/api/activity/${id}`),
  addActivity:activity => request("/api/activity", "POST", activity),
  updateActivity:(id, activity) => request(`/api/activity/${id}`, "PUT", activity),
  deleteActivity:id => request(`/api/activity/${id}`, "DELETE"),
  itineraryImpacts:id => request(`/api/itinerary/${id}/impact`),
  chat:       (itineraryId, message) => request(`/api/itinerary/${itineraryId}/chat`, "POST", {message}),   // -> {role: "assistant", content}
  chatHistory:itineraryId => request(`/api/itinerary/${itineraryId}/chat/history`),   // -> [{role: "user" | "assistant", content}], oldest first
  resetChat:  itineraryId => request(`/api/itinerary/${itineraryId}/chat`, "DELETE"),
  // planned, not on the backend yet
  adminUsers: () => request("/api/user"),                                              // admin only: every account, without passwords
};

// Mock mode only on localhost, via http://localhost:8080/?mock
const isLocalhost = ["localhost", "127.0.0.1"].includes(location.hostname);
const useMock = isLocalhost && new URLSearchParams(location.search).has("mock");
if (useMock) showMockBanner();
export const api = useMock ? mockApi : realApi;
