// Run with: node src/test/js/alerts-session.test.js
// A JavaScriptCore host can instead provide globalThis.alertModuleSource.
(async () => {
    const source = globalThis.alertModuleSource ?? require("node:fs").readFileSync(
        require("node:path").resolve(__dirname, "../../main/resources/static/js/alerts.js"), "utf8");
    const moduleSource = source.replace(/^import .*;\s*$/gm, "").replace(/\bexport\s+/g, "");
    const assert = (condition, message) => { if (!condition) throw new Error(message); };
    const deferred = () => {
        let resolve;
        const promise = new Promise(done => { resolve = done; });
        return { promise, resolve };
    };
    const fixture = (firstRead = null) => {
        let account = "A";
        const calls = [];
        const api = {
            alerts: async () => ({ content: [], unreadCount: 0 }),
            markAllAlertsRead: async upToId => {
                calls.push({ account, kind: "all", upToId });
                return { unreadCount: 0 };
            },
            markAlertRead: async id => {
                calls.push({ account, kind: "one", id });
                if (id === 1 && firstRead) await firstRead.promise;
            },
        };
        const document = { addEventListener() {}, querySelectorAll: () => [] };
        const createModule = new Function("api", "document", `
            const clearInterval=()=>{}, clearTimeout=()=>{}, setInterval=()=>0, setTimeout=()=>0;
            const escapeHtml=x=>x, findToastShelf=()=>null, newsTitleOf=x=>x, timeTag=x=>x, icon=()=>'';
            ${moduleSource}
            return { markAllAlertsRead, markAlertsRead, stopAlerts,
                hold: promise => queueSave(() => promise), setUser: id => { state.userId=id; } };
        `);
        const alerts = createModule(api, document);
        alerts.setUser(1);
        return { alerts, calls, switchAccount() { alerts.stopAlerts(); account = "B"; alerts.setUser(2); } };
    };
    const passed = [];
    {
        const f = fixture(), held = deferred();
        f.alerts.hold(held.promise);
        await Promise.resolve();
        const oldSave = f.alerts.markAllAlertsRead(999);
        f.switchAccount(); held.resolve();
        await oldSave;
        assert(f.calls.length === 0, "Account A's queued mark-all must not execute as B");
        passed.push("cancel queued mark-all after account switch");
    }
    {
        const held = deferred(), f = fixture(held);
        const groupSave = f.alerts.markAlertsRead([{ id: 1, readAt: null }, { id: 2, readAt: null }]);
        await Promise.resolve();
        assert(f.calls.length === 1 && f.calls[0].account === "A", "First read should start as A");
        f.switchAccount(); held.resolve();
        await groupSave;
        assert(f.calls.length === 1, "Remaining group reads must stop after logout");
        passed.push("stop in-progress group before its next request");
    }
    {
        const f = fixture();
        await Promise.all([f.alerts.markAllAlertsRead(10), f.alerts.markAllAlertsRead(20)]);
        assert(f.calls.length === 2 && f.calls.every(call => call.account === "A"), "Same-session saves should complete");
        assert(f.calls[0].upToId === 10 && f.calls[1].upToId === 20, "Saves should retain queue order");
        passed.push("preserve same-session queue order");
    }
    {
        const f = fixture(), held = deferred();
        f.alerts.hold(held.promise);
        await Promise.resolve();
        const oldSave = f.alerts.markAllAlertsRead(10);
        f.switchAccount();
        await f.alerts.markAllAlertsRead(20);
        assert(f.calls.length === 1 && f.calls[0].account === "B", "B must not wait for A's old queue");
        held.resolve(); await oldSave;
        assert(f.calls.length === 1, "A's queued save must remain cancelled");
        passed.push("allow the next account's own saves immediately");
    }
    globalThis.alertSessionTestResults = { ok: true, passed };
    globalThis.console?.log(`${passed.length} alert session tests passed`);
})().catch(error => {
    globalThis.alertSessionTestResults = { ok: false, error: String(error) };
    globalThis.console?.error(error);
    if (typeof process !== "undefined") process.exitCode = 1;
});
