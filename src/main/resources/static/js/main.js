import { api } from "./api.js";
import { escapeHtml } from "./util.js";
import { showLogin } from "./views/login.js";
import { showRegister } from "./views/register.js";
import { showItineraries } from "./views/itineraries.js";
import { showAccount } from "./views/account.js";
import { showEditDetails, showChangePassword, showDeleteAccount, showEditPreferences } from "./views/account-edit.js";
import { showEditItinerary } from "./views/itinerary-edit.js";
import { showAdminUsers } from "./views/admin-users.js";
import { showAdminUser } from "./views/admin-user.js";
import { showAdminAudit } from "./views/admin-audit.js";
import { showNews } from "./views/news.js";

const root = document.getElementById("root");
const nav = document.getElementById("nav");

// ---------------------------------------------------------------------------
// ROUTE TABLE: to add a screen, add one line here. Nothing else in this file changes.
//   path  - URL after the "#". ":name" parts become context.params.name
//   view  - function that draws into context.root. context = { root, user, params, reauth }
//   nav   - text for the top bar link (leave out to hide it from the nav)
//   role  - only users with this role may open it (optional)
//   public - shown only to logged-out visitors (optional); logged-in users are sent home
// ---------------------------------------------------------------------------
const routes = [
	{ path: "/register", public: true, view: context => showRegister(context.root, context.reauth) },
	{ path: "/itineraries", nav: "Itineraries", role: "USER", view: context => showItineraries(context.root, context.user, context.reauth) },
	{ path: "/itineraries/new", role: "USER", view: context => showEditItinerary(context.root, context.user, context.reauth, null) },
	{ path: "/itineraries/:id/edit", role: "USER", view: context => showEditItinerary(context.root, context.user, context.reauth, context.params.id) },
	{ path: "/itineraries/:id/activities/new", role: "USER", view: context => showEditItinerary(context.root, context.user, context.reauth, context.params.id, true) },
	{ path: "/admin/users", nav: "Users", role: "ADMIN", view: context => showAdminUsers(context.root, context.user, context.reauth) },
	{ path: "/admin/users/:id", role: "ADMIN", view: context => showAdminUser(context.root, context.user, context.reauth, context.params.id) },
	{ path: "/admin/audit", nav: "Activity", role: "ADMIN", view: context => showAdminAudit(context.root, context.user, context.reauth) },
	{ path: "/news", nav: "News", view: context => showNews(context.root, context.user, context.reauth) },
	{ path: "/account", nav: "Account", view: context => showAccount(context.root, context.user, context.reauth) },
	{ path: "/account/preferences", role: "USER", view: context => showEditPreferences(context.root, context.user, context.reauth) },
	{ path: "/account/details", view: context => showEditDetails(context.root, context.user, context.reauth) },
	{ path: "/account/password", view: context => showChangePassword(context.root, context.user, context.reauth) },
	{ path: "/account/delete", view: context => showDeleteAccount(context.root, context.user, context.reauth) },
	// { path: "/itineraries/:id", view: context => showItinerary(context.root, context.user, context.reauth, context.params.id) },
];
// Where each role starts. Admins manage accounts and don't have itineraries.
const home = () => user?.role === "ADMIN"
	? { path: "/admin/users", label: "Back to users" }
	: { path: "/itineraries", label: "Back to itineraries" };

// "/itineraries/:id" -> /^\/itineraries\/(?<id>[^/]+)$/
for (const route of routes) route.pattern = new RegExp("^" + route.path.replace(/:(\w+)/g, "(?<$1>[^/]+)") + "$");

const currentPath = () => location.hash.slice(1) || home().path;
function findRoute(path) {
	for (const route of routes) {
		const result = route.pattern.exec(path);
		if (result) return { route, params: result.groups ?? {} };
	}
	return null;
}

// ---------------------------------------------------------------------------
// Session: asked once at start, and again only after login/logout or a 401.
// ---------------------------------------------------------------------------
let user = null;
// set when the session ends without the user logging out, so the login screen can say why
let sessionExpired = false;

// Views call this to mean "something changed, re-check me". Optional `nextPath` = path to open afterwards.
async function reauth(nextPath) {
	const wasLoggedIn = user !== null;
	try { user = await api.me(); } catch { user = null; }

	if (wasLoggedIn && !user) sessionExpired = true;
	if (user) sessionExpired = false;

	if (user && nextPath && location.hash !== "#" + nextPath) { location.hash = nextPath; return; }  // hashchange redraws
	render();
}

async function logout() {
	try { await api.logout(); } catch { }
	user = null;
	sessionExpired = false;
	location.hash = "";
	render();
}

// ---------------------------------------------------------------------------
// Drawing
// ---------------------------------------------------------------------------
function drawNav() {
	const path = currentPath();
	const links = routes
		.filter(route => route.nav && (!route.role || route.role === user.role))
		.map(route => {
			const isCurrent = path === route.path || path.startsWith(route.path + "/");
			return `<a href="#${route.path}"${isCurrent ? ' aria-current="page"' : ""}>${escapeHtml(route.nav)}</a>`;
		}).join("");
	nav.innerHTML = `
	<nav>
		<div><strong style="font-size: 1.3rem">TripSense</strong> &nbsp;${links}</div>
    	<div>${escapeHtml(user.username)} &nbsp; <button class="ghost" id="logout-button">Log out</button></div>
	</nav>`;
	document.getElementById("logout-button").onclick = logout;
}

// why the login screen is showing, if it isn't just a fresh visit or a logout
function loginNotice() {
	if (sessionExpired) return "Your session has expired. Please log in again.";

	const openedASpecificPage = location.hash !== "" && location.hash !== "#";
	if (openedASpecificPage) return "Please log in to continue.";

	return null;
}

const showMessage = (text) => { root.innerHTML = `<div class="msg">${text} <a href="#${home().path}">${home().label}</a></div>`; };

function render() {
	const matched = findRoute(currentPath());
	if (!user) {
		nav.innerHTML = "";
		if (matched?.route.public) return matched.route.view({ root, user, params: matched.params, reauth });
		return showLogin(root, reauth, loginNotice());   // the url is kept, so logging in returns to that page
	}
	if (matched?.route.public) { location.hash = home().path; return; }   // hashchange redraws
	drawNav();
	if (!matched) return showMessage("Page not found.");
	if (matched.route.role && matched.route.role !== user.role) return showMessage("You don't have access to this page.");
	try {
		matched.route.view({ root, user, params: matched.params, reauth });
	} catch (error) {                      // a bug in one view shouldn't blank the whole app
		console.error(error);
		root.innerHTML = `<div class="msg err" role="alert">Something went wrong showing this page.</div>`;
	}
}

addEventListener("hashchange", render);   // URL changed: redraw (no server call)
root.innerHTML = `<div class="msg">Loading…</div>`;
reauth();
