// Run with: node src/test/js/alerts-sync.test.js
// A JavaScriptCore host may instead provide globalThis.alertTestSources.
(async () => {
    const read = name => globalThis.alertTestSources?.[name] ?? require("node:fs").readFileSync(
        require("node:path").resolve(__dirname, `../../main/resources/static/js/${name}.js`), "utf8");
    const plain = source => source.replace(/^import .*;\s*$/gm, "").replace(/\bexport\s+/g, "");
    const assert = (condition, message) => { if (!condition) throw new Error(message); };
    const passed = [];
    const mockFixture = () => new Function("sessionStorage", "setTimeout", "structuredClone", `
        ${plain(read("mock"))}
        return { api: mockApi, alerts: ALERTS, logout: () => { me=null; } };
    `)({ getItem: () => "1" }, callback => callback(), value => JSON.parse(JSON.stringify(value)));
    {
        const f = mockFixture();
        for (const alert of f.alerts) alert.readAt = null;
        f.alerts.push({ id: 8, impactId: 3, readAt: null, createdAt: new Date().toISOString() });
        const result = await f.api.markAlertGroupRead(3);
        assert(f.alerts.filter(a => a.id <= 3).every(a => a.readAt != null), "Read older group members");
        assert(f.alerts.find(a => a.id === 4).readAt == null, "Leave another news/trip group unread");
        assert(f.alerts.find(a => a.id === 8).readAt == null, "Leave later arrivals unread");
        assert(result.unreadCount === 2, "Return the owner's remaining unread count");
        await f.api.markAlertGroupRead(3);
        assert((await f.api.alerts()).unreadCount === 2, "Repeated group reads must be idempotent");
        passed.push("mock group read covers older members and preserves its boundary");
    }
    {
        const f = mockFixture();
        for (const id of [5, 999]) {
            const error = await f.api.markAlertGroupRead(id).then(() => null, error => error);
            assert(error?.status === 404, "Unknown and other-account anchors must be hidden");
        }
        f.logout();
        const error = await f.api.markAlertGroupRead(3).then(() => null, error => error);
        assert(error?.status === 401, "Group reads require login");
        passed.push("mock group read enforces ownership and login");
    }
    {
        let notifications = 0;
        const createApi = new Function("mockApi", "showMockBanner", "invalidateAlerts", "location", "URLSearchParams", `
            ${plain(read("api"))}
            return api;
        `);
        const api = createApi({
            markAlertGroupRead: async () => ({ unreadCount: 0 }),
            setImpactRejected: async () => { throw new Error("save failed"); },
        }, () => {}, () => { notifications++; }, { hostname: "localhost", search: "?mock" }, class { has() { return true; } });
        await api.markAlertGroupRead(3);
        assert(notifications === 1, "Successful mock writes must invalidate other tabs");
        await api.setImpactRejected(1, true).catch(() => {});
        assert(notifications === 1, "Failed writes must not broadcast a saved change");
        passed.push("API invalidates alert history only after a successful change");
    }
    {
        const channels = [];
        class Channel {
            constructor(name) { this.name=name; channels.push(this); }
            postMessage(data) { for (const other of channels) if (other !== this && other.name === this.name) other.onmessage?.({ data }); }
        }
        const createSync = new Function("BroadcastChannel", `${plain(read("alert-sync"))}; return { onAlertsInvalidated, invalidateAlerts };`);
        const first = createSync(Channel), second = createSync(Channel);
        let local = 0, remote = 0;
        first.onAlertsInvalidated(() => { local++; });
        const stop = second.onAlertsInvalidated(() => { remote++; });
        first.invalidateAlerts();
        assert(local === 1 && remote === 1, "Notify the current tab and another tab");
        stop(); first.invalidateAlerts();
        assert(local === 2 && remote === 1, "Unsubscribed tabs must stop receiving changes");
        passed.push("broadcast alert invalidations across tabs and unsubscribe cleanly");
    }
    globalThis.alertSyncTestResults = { ok: true, passed };
    globalThis.console?.log(`${passed.length} alert sync tests passed`);
})().catch(error => {
    globalThis.alertSyncTestResults = { ok: false, error: String(error) };
    globalThis.console?.error(error);
    if (typeof process !== "undefined") process.exitCode = 1;
});
