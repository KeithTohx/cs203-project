// Admin SQL console: read-only queries against the whole database.
// Laid out like the reference: statement and result on the left, the tables on the right.
//
// Everything that comes back from the server is put on the page with textContent, never innerHTML,
// because the database holds text from outside (news articles) and must not be able to add markup.
// The only innerHTML below is the fixed page skeleton, which has no data in it.
 
const STYLE_ID = "sql-console-style";
 
// scoped to .sql-console so nothing here can change another page. colours are the app's own tokens
const CSS = `
.sql-console { display: block; gap: 24px; align-items: center; }
.sql-console .sql-main { min-width: 0; }
.sql-console h2 { font-weight: 400; font-size: 26px; }
.sql-console textarea {
	display: block; width: 100%; height: 200px; padding: 10px; resize: vertical;
	font: 15px/1.45 ui-monospace, Consolas, "Courier New", monospace;
	border: 1px solid var(--border); border-radius: 6px; background: var(--panel); color: var(--text);
}
.sql-console .sql-hint { color: var(--muted); margin: 14px 0 0; }
.sql-console button.run { background: #04aa6d; border-color: #04aa6d; font-size: 17px; padding: 10px 22px; margin-bottom: 8px; }
.sql-console .sql-result { min-height: 320px; overflow: auto; margin-bottom: 0; }
.sql-console .sql-meta { color: var(--muted); margin: 0 0 8px; }
.sql-console .sql-result .table-scroll { margin-top: 0; }
.sql-console table.sql-rows th { position: sticky; top: 0; background: var(--panel); white-space: nowrap; padding-right: 10px; }
.sql-console table.sql-rows td { max-width: 800px; vertical-align: top; font-size: 16px; padding-right: 10px; }
.sql-console td.null, .sql-console td.hidden { color: var(--muted); font-style: italic; }
.sql-console .sql-note { color: var(--muted); font-size: 14px; margin: 14px 0 0; }
@media (max-width: 900px) {
	.sql-console { flex-direction: column; }
}`;
 
function addStyles() {
	if (document.getElementById(STYLE_ID)) return;
	const style = document.createElement("style");
	style.id = STYLE_ID;
	style.textContent = CSS;
	document.head.append(style);
}
 
// The XSRF-TOKEN cookie is readable on purpose (see SecurityConfig). It has to be sent back as a
// header on the POST, or the server answers 403.
function csrfToken() {
	const prefix = "XSRF-TOKEN=";
	const cookie = document.cookie.split("; ").find(c => c.startsWith(prefix));
	return cookie ? decodeURIComponent(cookie.substring(prefix.length)) : "";
}
 
// a plain name is used as it is, anything else is quoted so the statement still runs
const identifier = name => /^[A-Za-z_][A-Za-z0-9_]*$/.test(name) ? name : '"' + name.replaceAll('"', '""') + '"';
 
export function showAdminSql(root, user, reauth, onLeave) {
	addStyles();
	// the page is normally 860px wide, which is too narrow for a result table next to the table list.
	// widen it while the console is open and put it back when the admin leaves
	root.style.maxWidth = "860px";
	onLeave?.(() => { root.style.maxWidth = ""; });
 
	root.innerHTML = `
	<h1>Database Console</h1>
	<div class="sql-console">
    	<section class="card sql-main">
            <h2>Overview of TripSense Database:</h2>
			<table class="sql-rows">
				<thead><tr><th>Table Names</th><th>Records</th></tr></thead>
				<tbody id="sql-tables"><tr><td colspan="2">Loading…</td></tr></tbody>
			</table>
			<p class="sql-note">Click a table to see its rows. The password column of the user table is never shown.
				Every query you run is recorded in the activity log.</p>
		</section>
		<section class="sql-main">
			<h2>SQL Statement:</h2>
			<textarea id="sql" spellcheck="false" autocomplete="off" aria-label="SQL statement">SELECT * FROM recommendation;</textarea>
			<p class="sql-hint">Edit the SQL Statement, and click "Run SQL" to see the result.
				Read-only: one SELECT at a time. Ctrl+Enter runs it.</p>
			<button class="run" id="sql-run" type="button">Run SQL &raquo;</button>
			<h2>Result:</h2>
			<div class="card sql-result" id="sql-result">Click <b>'Run SQL'</b> to execute the SQL statement above.</div>
		</section>
	</div>`;
 
	const sqlBox = root.querySelector("#sql");
	const runButton = root.querySelector("#sql-run");
	const resultBox = root.querySelector("#sql-result");
	const tablesBody = root.querySelector("#sql-tables");
 
	function showError(message) {
		resultBox.replaceChildren();
		const error = document.createElement("p");
		error.className = "err";
		error.textContent = "Error: " + message;
		resultBox.append(error);
	}
 
	function explain(response, body) {
		if (response.status === 403) return "Only admin accounts can use the SQL console. If you are an admin, reload the page and try again.";
		return body.message || response.statusText || "The request failed.";
	}
 
	function showResult(data) {
		resultBox.replaceChildren();
 
		const meta = document.createElement("p");
		meta.className = "sql-meta";
		let text = "Number of Records: " + data.rowCount;
		if (data.truncated) text += " (the query has more rows, only the first " + data.rowCount + " are shown)";
		meta.textContent = text + "  ·  " + data.elapsedMs + " ms";
		resultBox.append(meta);
 
		const scroll = document.createElement("div");
		scroll.className = "table-scroll";
		const table = document.createElement("table");
		table.className = "users sql-rows";
 
		const headRow = table.createTHead().insertRow();
		for (const name of data.columns) {
			const th = document.createElement("th");
			th.textContent = name;
			headRow.append(th);
		}
 
		const body = table.createTBody();
		for (const row of data.rows) {
			const tr = body.insertRow();
			for (const value of row) {
				const td = tr.insertCell();
				if (value === null) {
					td.className = "null";
					td.textContent = "NULL";
				} else {
					if (value === "[hidden]") td.className = "hidden";
					td.textContent = String(value);
				}
			}
		}
		scroll.append(table);
		resultBox.append(scroll);
	}
 
	async function run() {
		runButton.disabled = true;
		resultBox.textContent = "Running…";
		try {
			const response = await fetch("/api/admin/db/query", {
				method: "POST",
				credentials: "same-origin",
				headers: { "Content-Type": "application/json", "X-XSRF-TOKEN": csrfToken() },
				body: JSON.stringify({ sql: sqlBox.value })
			});
			if (response.status === 401) { reauth(); return; }   // session ended: back to the login page
			const body = await response.json().catch(() => ({}));
			if (!response.ok) { showError(explain(response, body)); return; }
			showResult(body);
		} catch {
			showError("Could not reach the server.");
		} finally {
			runButton.disabled = false;
		}
	}
 
	async function loadTables() {
		try {
			const response = await fetch("/api/admin/db/tables", { credentials: "same-origin" });
			if (response.status === 401) { reauth(); return; }
			const body = await response.json().catch(() => ({}));
			tablesBody.replaceChildren();
			if (!response.ok) {
				const cell = tablesBody.insertRow().insertCell();
				cell.colSpan = 2;
				cell.textContent = explain(response, body);
				return;
			}
			for (const table of body) {
				const row = tablesBody.insertRow();
				const link = document.createElement("a");
				link.href = "#/admin/db";
				link.textContent = table.name;
				link.addEventListener("click", event => {
					event.preventDefault();   // stay on this page, only fill the box in
					sqlBox.value = "SELECT * FROM " + identifier(table.name) + ";";
					run();
				});
				row.insertCell().append(link);
				row.insertCell().textContent = table.rows;
			}
		} catch {
			tablesBody.replaceChildren();
			tablesBody.insertRow().insertCell().textContent = "Could not load the table list.";
		}
	}
 
	runButton.addEventListener("click", run);
	sqlBox.addEventListener("keydown", event => {
		if (event.key === "Enter" && (event.ctrlKey || event.metaKey)) {
			event.preventDefault();
			run();
		}
	});
	loadTables();
}
 