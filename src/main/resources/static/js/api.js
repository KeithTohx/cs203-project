import { mockApi, showMockBanner } from "./mock.js";
import { invalidateAlerts } from "./alert-sync.js";
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
  preferences:() => request("/api/user/me/preferences"),                               // -> the saved preferences, or the defaults if none were set
  updatePreferences:changes => request("/api/user/me/preferences", "PATCH", changes),  // only the fields sent change -> the preferences. solo with children or elderly is a 400
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
  news:       ({page = 0, size = 20} = {}) => request(`/api/news?${new URLSearchParams({page, size})}`),   // one page of stored news, newest first -> [{id, title, description, source, url, publishedAt}]
  itineraryImpacts:id => request(`/api/itinerary/${id}/impact`),
  // newest first -> {content, hasMore, nextBeforeId, unreadCount}. unreadCount covers every alert, not just this page.
  // send nextBeforeId back as beforeId to load older ones, it is null once there are none left
  alerts:     ({unreadOnly = false, beforeId = null, size = 20} = {}) => {
    const params = new URLSearchParams({unreadOnly, size});
    if (beforeId != null) params.set("beforeId", beforeId);
    return request(`/api/alerts?${params}`);
  },
  markAlertGroupRead:id => request(`/api/alerts/${id}/group`, "PATCH", {read: true}),
  markAlertRead:id => request(`/api/alerts/${id}`, "PATCH", {read: true}),            // -> the alert. marking twice is fine
  // every unread alert up to upToId (the newest one shown), not just the ones loaded, so one arriving meanwhile stays unread.
  // null means no limit -> {unreadCount} left afterwards
  markAllAlertsRead:(upToId = null) => {
    const query = upToId == null ? "" : `?${new URLSearchParams({upToId})}`;
    return request(`/api/alerts${query}`, "PATCH", {read: true});
  },
  alertStreamUrl:"/api/alerts/stream",                                                // server-sent events: "ready" on connect, "alert" when one is saved, "admin-change" when an admin changed your trips
  // what an admin changed in your trips, shown next to the news alerts with an "Admin" tag
  adminChanges:() => request("/api/user/me/admin-changes"),                           // -> {content: [{id, message, createdAt, readAt, itineraryId, itineraryName, itineraryExists, newsId, newsTitle}], unreadCount}, newest 20
  markAdminChangeRead:id => request(`/api/user/me/admin-changes/${id}`, "PATCH"),     // -> {unreadCount}, just that one
  markAdminChangesRead:(upToId = null) => {
    const query = upToId == null ? "" : `?${new URLSearchParams({upToId})}`;
    return request(`/api/user/me/admin-changes${query}`, "PATCH");                    // -> {unreadCount}
  },
  setImpactRejected:(id, rejected) => request(`/api/impact/${id}`, "PATCH", {rejected}),   // true = "not relevant to me", false = restore -> the impact
  itineraryRecommendations:(id, includeDismissed = false) => request(`/api/itinerary/${id}/recommendation?${new URLSearchParams({includeDismissed})}`),   // -> the newest attempt per impact, worst first. never calls the model
  generateRecommendations:itineraryId => request("/api/recommendation/generate", "POST", {itineraryId}),   // slow: asks the model once per impact without one -> every current one, worst first
  decideRecommendation:(id, status) => request(`/api/recommendation/${id}`, "PATCH", {status}),   // "ACCEPTED" | "DISMISSED" | "PENDING", back to pending clears the decision time -> the recommendation
  regenerateRecommendation:id => request(`/api/recommendation/${id}/regenerate`, "POST"),   // slow: asks the model again, skipping every place already offered -> a new one, the old stays as history
  impactRecommendations:impactId => request(`/api/impact/${impactId}/recommendation`),   // -> every attempt for one impact, oldest first
  chat:       (itineraryId, message) => request(`/api/itinerary/${itineraryId}/chat`, "POST", {message}),   // -> {role: "assistant", content}
  chatHistory:itineraryId => request(`/api/itinerary/${itineraryId}/chat/history`),   // -> [{role: "user" | "assistant", content}], oldest first
  chatStatus: itineraryId => request(`/api/itinerary/${itineraryId}/chat/status`),    // -> {answering}, true while an answer is still being written
  resetChat:  itineraryId => request(`/api/itinerary/${itineraryId}/chat`, "DELETE"),
  // admin only, one page at a time -> {content, page, size, totalElements, totalPages}
  adminUsers: ({search = "", page = 0, size = 20} = {}) => request(`/api/admin/users?${new URLSearchParams({search, page, size})}`),   // search matches part of the email or username
  adminUser:  id => request(`/api/admin/users/${id}`),                                 // -> {id, username, email, role, enabled}
  disableUser:id => request(`/api/admin/users/${id}/disable`, "POST"),                 // the account cannot log in and its sessions end, but its data stays
  enableUser: id => request(`/api/admin/users/${id}/enable`, "POST"),
  changeUserRole:(id, role) => request(`/api/admin/users/${id}/role`, "POST", {role}),   // "USER" | "ADMIN", logs them out. not allowed on yourself or the last enabled admin
  adminDeleteUser:id => request(`/api/admin/users/${id}`, "DELETE"),                   // -> 204, the account and everything in it is gone. not allowed on yourself
  userActivity:(id, {action = "", page = 0} = {}) => request(`/api/admin/users/${id}/actions?${new URLSearchParams({action, page})}`),   // what the account did and what was done to it, newest first
  auditLog:   ({email = "", action = "", page = 0} = {}) => request(`/api/admin/audit?${new URLSearchParams({email, action, page})}`),   // everyone's actions, newest first
  // admin console: any account's trip data, mostly for testing. every change is in the audit log
  adminCreateUser:details => request("/api/admin/users", "POST", details),             // {username, email, password, role} -> the account
  adminItineraries:userId => request(`/api/admin/users/${userId}/itineraries`),
  adminAddItinerary:(userId, itinerary) => request(`/api/admin/users/${userId}/itineraries`, "POST", itinerary),   // {name, countryId, dateStart, dateEnd}
  adminUpdateItinerary:(id, itinerary) => request(`/api/admin/itineraries/${id}`, "PUT", itinerary),
  adminDeleteItinerary:id => request(`/api/admin/itineraries/${id}`, "DELETE"),        // its activities, impacts, alerts and recommendations go too
  adminDeleteAllItineraries:userId => request(`/api/admin/users/${userId}/itineraries`, "DELETE"),   // -> {deleted}, every trip they have
  adminActivities:itineraryId => request(`/api/admin/itineraries/${itineraryId}/activities`),
  adminUserActivities:userId => request(`/api/admin/users/${userId}/activities`),      // every activity on all their trips, earliest first
  adminAddActivity:(itineraryId, activity) => request(`/api/admin/itineraries/${itineraryId}/activities`, "POST", activity),   // {name, address, dateStart, dateEnd, category}
  adminUpdateActivity:(id, activity) => request(`/api/admin/activities/${id}`, "PUT", activity),
  adminDeleteActivity:id => request(`/api/admin/activities/${id}`, "DELETE"),
  adminDeleteAllActivities:itineraryId => request(`/api/admin/itineraries/${itineraryId}/activities`, "DELETE"),   // -> {deleted}, whole trip impacts stay
  adminImpacts:userId => request(`/api/admin/users/${userId}/impacts`),                // newest first, rejected ones included
  adminAddImpact:(userId, impact) => request(`/api/admin/users/${userId}/impacts`, "POST", impact),   // {newsId, itineraryId, activityId, impacted, reason, rejected, sendAlert}
  adminUpdateImpact:(id, changes) => request(`/api/admin/impacts/${id}`, "PUT", changes),   // {impacted, reason, rejected}
  adminDeleteImpact:id => request(`/api/admin/impacts/${id}`, "DELETE"),
  adminDeleteAllImpacts:itineraryId => request(`/api/admin/itineraries/${itineraryId}/impacts`, "DELETE"),   // -> {deleted}, whole trip and activity impacts
  adminDeleteWholeTripImpacts:itineraryId => request(`/api/admin/itineraries/${itineraryId}/impacts?wholeTripOnly=true`, "DELETE"),   // -> {deleted}, the activity ones stay
  adminDeleteActivityImpacts:activityId => request(`/api/admin/activities/${activityId}/impacts`, "DELETE"),   // -> {deleted}
  adminRecommendations:userId => request(`/api/admin/users/${userId}/recommendations`),   // every attempt, newest first
  adminAddRecommendation:(impactId, recommendation) => request(`/api/admin/impacts/${impactId}/recommendations`, "POST", recommendation),   // {severity, action, advice, optionId, status}
  adminUpdateRecommendation:(id, recommendation) => request(`/api/admin/recommendations/${id}`, "PUT", recommendation),
  adminDeleteRecommendation:id => request(`/api/admin/recommendations/${id}`, "DELETE"),
  adminDeleteAllRecommendations:userId => request(`/api/admin/users/${userId}/recommendations`, "DELETE"),   // -> {deleted}, the impacts stay
};

// Mock mode only on localhost, via http://localhost:8080/?mock
const isLocalhost = ["localhost", "127.0.0.1"].includes(location.hostname);
const useMock = isLocalhost && new URLSearchParams(location.search).has("mock");
if (useMock) showMockBanner();
const selectedApi = useMock ? mockApi : realApi;
export const api = { ...selectedApi };
// Invalidate other tabs; queued alert reads handle their own local refresh on completion.
for (const method of ["markAlertRead", "markAlertGroupRead", "markAllAlertsRead", "setImpactRejected",
    "deleteActivity", "deleteItinerary"]) {
  api[method] = async (...args) => {
    const result = await selectedApi[method](...args);
    // The alert save queue already refreshes these reads in the originating tab.
    invalidateAlerts({ local: !["markAlertGroupRead", "markAllAlertsRead"].includes(method) });
    return result;
  };
}
