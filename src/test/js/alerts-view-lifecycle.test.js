// Run with: node src/test/js/alerts-view-lifecycle.test.js
// A JavaScriptCore host may instead provide globalThis.alertViewSource.
(async () => {
    const source = globalThis.alertViewSource ?? require("node:fs").readFileSync(
        require("node:path").resolve(__dirname, "../../main/resources/static/js/views/alerts.js"), "utf8");
    const plain = source.replace(/^import[\s\S]*?from\s*["'][^"']+["'];\s*/gm, "").replace(/\bexport\s+/g, "");
    const assert = (condition, message) => { if (!condition) throw new Error(message); };
    const deferred = () => {
        let resolve, reject;
        const promise = new Promise((done, fail) => { resolve=done; reject=fail; });
        return { promise, resolve, reject };
    };
    const fixture = (history, saves = Promise.resolve()) => {
        const nodes = new Map();
        const node = () => ({ innerHTML: "", textContent: "", disabled: false, hidden: false,
            isConnected: true, contains: () => false, addEventListener() {}, querySelector: () => null });
        const root = node();
        root.querySelector = selector => {
            if (!nodes.has(selector)) nodes.set(selector, node());
            return nodes.get(selector);
        };
        let leave, notify, requests = 0, errors = 0, authLosses = 0, unsubscribed = false;
        const api = { alerts: () => { requests++; return history.promise; } };
        const create = new Function("api", "document", "onAlertsChanged", "waitForSaves", "showError", `
            const groupAlerts=()=>[], isGroupUnread=()=>false, findGroupWithAlert=()=>null;
            const markAlertsRead=async()=>{}, markAllAlertsRead=async()=>{};
            const alertMetaParts=()=>[], alertTitle=()=>'', emptyState=()=>'', escapeHtml=x=>x,
                hostnameOf=()=>'', safeUrl=()=>null, icon=()=>'';
            ${plain}
            return showAlerts;
        `);
        const show = create(api, { activeElement: null }, listener => {
            notify=listener;
            return () => { unsubscribed=true; };
        }, () => saves, (element, error, onAuthLost) => {
            errors++;
            if (error.status === 401) onAuthLost();
        });
        const done = show(root, { id: 1 }, () => { authLosses++; }, callback => { leave=callback; });
        return {
            done, leave: () => leave(), notify: change => notify(change),
            snapshot: () => JSON.stringify([...nodes].map(([id, n]) => [id, n.innerHTML, n.textContent, n.disabled, n.hidden])),
            counts: () => ({ requests, errors, authLosses, unsubscribed }),
        };
    };
    const passed = [];
    {
        const history = deferred(), saves = deferred(), f = fixture(history, saves.promise);
        f.leave();
        saves.resolve();
        await f.done;
        assert(f.counts().requests === 0, "Leaving while waiting for saves must prevent the history request");
        assert(f.counts().unsubscribed, "Leaving must remove the change listener");
        passed.push("cancel history load before its request starts");
    }
    {
        const history = deferred(), f = fixture(history);
        await Promise.resolve();
        assert(f.counts().requests === 1, "History request should have started");
        f.leave();
        const before = f.snapshot();
        history.resolve({ content: [], unreadCount: 0, hasMore: false });
        await f.done;
        f.notify({ newAlerts: [], unreadCount: 99 });
        assert(f.snapshot() === before, "Disposed views must ignore responses and queued change callbacks");
        passed.push("ignore history responses after leaving the view");
    }
    for (const status of [401, 500]) {
        const history = deferred(), f = fixture(history);
        await Promise.resolve();
        f.leave();
        const before = f.snapshot();
        history.reject(Object.assign(new Error("history failed"), { status }));
        await f.done;
        assert(f.snapshot() === before && f.counts().errors === 0 && f.counts().authLosses === 0,
            "Disposed views must ignore errors, including authentication failures");
        passed.push(`ignore ${status} after leaving the view`);
    }
    {
        const history = deferred(), f = fixture(history);
        await Promise.resolve();
        history.reject(Object.assign(new Error("session expired"), { status: 401 }));
        await f.done;
        assert(f.counts().errors === 1 && f.counts().authLosses === 1,
            "Active views must still handle authentication failures");
        passed.push("handle authentication failures while the view is active");
    }
    {
        const history = deferred(), f = fixture(history);
        await Promise.resolve();
        f.notify({ newAlerts: [{ id: 9, readAt: null, itineraryId: 1, newsId: 2 }], unreadCount: 1 });
        history.resolve({ content: [], unreadCount: 0, hasMore: false });
        await f.done;
        const controls = JSON.parse(f.snapshot()).find(row => row[0] === "#alerts-switch")[1];
        assert(controls.includes("Unread (1)") && controls.includes("data-read-all"),
            "An older history response must retain the live unread count and mark-all button");
        passed.push("preserve a newer live unread count while loading history");
    }
    {
        const history = deferred(), f = fixture(history);
        await Promise.resolve();
        history.resolve({ content: [], unreadCount: 3, hasMore: false });
        await f.done;
        const controls = JSON.parse(f.snapshot()).find(row => row[0] === "#alerts-switch")[1];
        assert(controls.includes("Unread (3)"), "A history response must supply the count when no live update intervenes");
        passed.push("accept the history unread count when no newer update exists");
    }
    globalThis.alertViewTestResults = { ok: true, passed };
    globalThis.console?.log(`${passed.length} alert view lifecycle tests passed`);
})().catch(error => {
    globalThis.alertViewTestResults = { ok: false, error: String(error) };
    globalThis.console?.error(error);
    if (typeof process !== "undefined") process.exitCode = 1;
});
