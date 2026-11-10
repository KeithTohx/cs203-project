// Run with: node src/test/js/alerts-session.test.js
// A JavaScriptCore host can instead provide globalThis.alertModuleSource.
(async () => {
    const source = globalThis.alertModuleSource ?? require("node:fs").readFileSync(
        require("node:path").resolve(__dirname, "../../main/resources/static/js/alerts.js"), "utf8");
    const moduleSource = source.replace(/^import .*;\s*$/gm, "").replace(/\bexport\s+/g, "");
    const assert = (condition, message) => { if (!condition) throw new Error(message); };
    const deferred = () => {
        let resolve, reject;
        const promise = new Promise((done, fail) => { resolve = done; reject = fail; });
        return { promise, resolve, reject };
    };
    const fixture = (firstRead = null, history = null) => {
        let account = "A";
        const calls = [];
        let refreshCalls = 0;
        const api = {
            alerts: async () => { refreshCalls++; return history ? history.promise : { content: [], unreadCount: 0 }; },
            markAllAlertsRead: async upToId => {
                calls.push({ account, kind: "all", upToId });
                return { unreadCount: 0 };
            },
            markAlertGroupRead: async id => {
                calls.push({ account, kind: "one", id });
                if (id === 1 && firstRead) await firstRead.promise;
            },
        };
        const document = { addEventListener() {}, querySelectorAll: () => [] };
        const createModule = new Function("api", "document", `
            let intervals=0;
            let nextTimer=0, syncListener=null;
            const timers=new Map(), deadlines=new Map(); let virtualNow=0;
            const clearInterval=()=>{intervals=0;}, clearTimeout=id=>{timers.delete(id);deadlines.delete(id);}, setInterval=()=>{intervals++; return 1;},
                setTimeout=(callback,delay=0)=>{const id=++nextTimer; timers.set(id,callback); deadlines.set(id,virtualNow+delay); return id;};
            const advance=async ms=>{virtualNow+=ms; for(const [id,due] of [...deadlines]) {
                if(due<=virtualNow && timers.has(id)) {const callback=timers.get(id);timers.delete(id);deadlines.delete(id);await callback();}
            }};
            class EventSource {
                static CLOSED=2;
                constructor() { this.listeners={}; }
                addEventListener(name, listener) { this.listeners[name]=listener; }
                close() {}
            }
            const onAlertsInvalidated=listener=>{syncListener=listener; return ()=>{syncListener=null;};};
            const escapeHtml=x=>x, findToastShelf=()=>null, newsTitleOf=x=>x, timeTag=x=>x, icon=()=>'';
            ${moduleSource}
            let authLosses=0;
            state.onAuthLost=()=>{authLosses++;};
            return { markAllAlertsRead, markAlertsRead, stopAlerts, refresh, onAlertsChanged,
                mount: () => mountAlertBell(null, {id:2}, {onAuthLost:()=>{authLosses++;}, redraw:()=>{}}),
                invalidate: () => syncListener?.(),
                flushBurst: () => {const callback=timers.get(burstTimer); timers.delete(burstTimer); return callback?.();},
                snapshot: () => ({ userId: state.userId, alerts: state.alerts, unreadCount: state.unreadCount, authLosses }),
                connect: () => { api.alertStreamUrl='/api/alerts/stream'; connectStream(); },
                ready: () => state.stream.listeners.ready(), polling: () => intervals,
                openPanel: () => { state.isOpen=true; },
                disconnect: () => { state.stream.readyState=0; state.stream.listeners.error(); },
                closeStream: () => { state.stream.readyState=2; state.stream.listeners.error(); },
                checkAgain, incomingAlert:()=>state.stream.listeners.alert(), advance,
                scheduledRetries:()=>timers.size+intervals,
                hold: promise => queueSave(() => promise), setUser: id => { state.userId=id; } };
        `);
        const alerts = createModule(api, document);
        alerts.setUser(1);
        return { alerts, calls, refreshCalls: () => refreshCalls, switchAccount() { alerts.stopAlerts(); account = "B"; alerts.setUser(2); } };
    };
    const passed = [];
    const settle = async () => { for (let i=0; i<8; i++) await Promise.resolve(); };
    const readHistory = () => ({ content: Array.from({length:20}, (_,index) => ({
        id:100-index, readAt:"2026-11-10T00:00:00Z",
    })), unreadCount:0 });
    {
        const history={promise:Promise.resolve(readHistory())}, f=fixture(null,history);
        f.alerts.mount(); await settle();
        const changes=[]; f.alerts.onAlertsChanged(change=>changes.push(change));
        // Deleting/rejecting a read alert on an older page leaves this bell response identical.
        const before=f.refreshCalls();
        f.alerts.invalidate(); f.alerts.invalidate();
        await f.alerts.flushBurst();
        assert(f.refreshCalls() === before+1, "Cross-tab invalidations should share one history request");
        assert(changes.length === 1 && changes[0].outOfStep === true,
            "Older loaded pages must reload even when the newest 20 alerts and unread count are unchanged");
        await f.alerts.refresh();
        assert(changes.length === 1, "A successful reload must consume the history invalidation");
        passed.push("reload older alert pages after cross-tab changes outside the bell");
    }
    {
        const history={promise:Promise.resolve(readHistory())}, f=fixture(null,history), held=deferred();
        f.alerts.mount(); await settle();
        const changes=[]; f.alerts.onAlertsChanged(change=>changes.push(change));
        const save=f.alerts.hold(held.promise);
        f.alerts.invalidate(); await f.alerts.flushBurst();
        assert(changes.length === 0, "Invalidation must wait until local read saves finish");
        held.resolve(); await save; await settle();
        assert(changes.length === 1 && changes[0].outOfStep === true,
            "The post-save reload must retain an invalidation for older pages");
        passed.push("retain older-history invalidation while local saves are pending");
    }
    {
        const history={promise:Promise.resolve(readHistory())}, f=fixture(null,history), held=deferred();
        f.alerts.mount(); await settle();
        const changes=[]; f.alerts.onAlertsChanged(change=>changes.push(change));
        history.promise=held.promise;
        const stale=f.alerts.refresh();
        f.alerts.invalidate(); const current=f.alerts.flushBurst();
        held.resolve(readHistory()); await Promise.all([stale,current]);
        assert(changes.length === 1 && changes[0].outOfStep === true,
            "A superseded response must not consume the newer history invalidation");
        passed.push("preserve history invalidation across overlapping requests");
    }
    {
        const history={promise:Promise.resolve(readHistory())}, f=fixture(null,history), failed=deferred();
        f.alerts.mount(); await settle();
        const changes=[]; f.alerts.onAlertsChanged(change=>changes.push(change));
        history.promise=failed.promise;
        f.alerts.invalidate(); const refresh=f.alerts.flushBurst();
        failed.reject({status:500}); await refresh;
        assert(changes.length === 0, "A failed refresh must not publish stale history as current");
        history.promise=Promise.resolve(readHistory()); await f.alerts.refresh();
        assert(changes.length === 1 && changes[0].outOfStep === true,
            "The next successful refresh must retry the older-history invalidation");
        passed.push("retry older-history invalidation after a failed refresh");
    }
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
        const groupSave = f.alerts.markAlertsRead([{ id: 1, newsId: 1, readAt: null }, { id: 2, newsId: 2, readAt: null }]);
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
    {
        const f = fixture();
        f.alerts.connect();
        assert(f.alerts.polling() === 1, "Polling must start while SSE connects");
        f.alerts.ready();
        assert(f.alerts.polling() === 0, "A live stream must stop fallback polling");
        f.alerts.disconnect();
        assert(f.alerts.polling() === 1, "A reconnecting stream must resume polling");
        const firstFailureCalls = f.refreshCalls();
        f.alerts.disconnect();
        f.alerts.disconnect();
        assert(f.refreshCalls() === firstFailureCalls, "Repeated reconnect failures must wait for the polling interval");
        f.alerts.ready();
        assert(f.alerts.polling() === 0, "A recovered stream must stop polling again");
        passed.push("poll only while the live stream is unavailable");
    }
    {
        const history = { promise: Promise.resolve({ content: [], unreadCount: 0 }) };
        const f = fixture(null, history);
        f.alerts.connect();
        f.alerts.openPanel();
        f.alerts.ready();
        for (let i=0; i<8; i++) await Promise.resolve();
        assert(f.alerts.polling() === 0, "The connected stream should be healthy before overflow recovery");
        const before=f.refreshCalls();
        // No alert event arrives: only the server's repeated ready event signals recovery.
        history.promise=Promise.resolve({ content: [{ id: 77, readAt: null }], unreadCount: 1 });
        f.alerts.ready();
        for (let i=0; i<8; i++) await Promise.resolve();
        const saved=f.alerts.snapshot();
        assert(f.refreshCalls() === before+1, "A recovery ready event must fetch history on a healthy stream");
        assert(saved.alerts.length === 1 && saved.alerts[0].id === 77 && saved.unreadCount === 1,
            "The saved alert must become visible even though its live event was dropped");
        passed.push("recover a dropped alert from history on a healthy stream");
    }
    {
        const f = fixture();
        await f.alerts.markAlertsRead([
            { id: 20, itineraryId: 1, newsId: 2, readAt: null },
            { id: 19, itineraryId: 1, newsId: 2, readAt: null },
        ]);
        assert(f.calls.length === 1 && f.calls[0].id === 20,
            "A group read must use one group request bounded by its newest member");
        passed.push("mark persisted group through its newest displayed member");
    }
    for (const nextUser of [1, 2]) {
        const history = deferred(), f = fixture(null, history);
        const oldRefresh = f.alerts.refresh();
        f.alerts.stopAlerts(); f.alerts.setUser(nextUser);
        history.reject(Object.assign(new Error("expired session"), { status: 401 }));
        await oldRefresh;
        assert(f.alerts.snapshot().userId === nextUser && f.alerts.snapshot().authLosses === 0,
            "A previous login's 401 must not log out the current session");
        passed.push(`ignore previous-session 401 after login as user ${nextUser}`);
    }
    {
        const history = deferred(), f = fixture(null, history);
        const oldRefresh = f.alerts.refresh();
        f.alerts.stopAlerts(); f.alerts.setUser(1);
        history.resolve({ content: [{ id: 17, readAt: null }], unreadCount: 1 });
        await oldRefresh;
        const current = f.alerts.snapshot();
        assert(current.alerts.length === 0 && current.unreadCount === 0,
            "History from before logout must not populate a new login to the same account");
        passed.push("ignore previous-session history after same-account login");
    }
    {
        const history = deferred(), f = fixture(null, history);
        const refresh = f.alerts.refresh();
        history.reject(Object.assign(new Error("expired session"), { status: 401 }));
        await refresh;
        assert(f.alerts.snapshot().userId == null && f.alerts.snapshot().authLosses === 1,
            "A current-session 401 must still end the session");
        passed.push("handle current-session authentication failures");
    }
    {
        const f = fixture();
        f.alerts.connect();
        f.alerts.disconnect();
        assert(f.refreshCalls() === 1, "The first pre-ready failure must check the session immediately");
        f.alerts.disconnect();
        assert(f.refreshCalls() === 1, "Repeated failures must wait for the polling interval");
        passed.push("check authentication on the first stream failure before ready");
    }
    {
        const f = fixture();
        f.alerts.connect();
        f.alerts.closeStream();
        const before = f.refreshCalls();
        f.alerts.checkAgain();
        assert(f.refreshCalls() === before, "A reconnect attempt must leave history loading to ready or error");
        f.alerts.ready();
        assert(f.refreshCalls() === before + 1, "A successful reconnect must reload history exactly once");
        passed.push("avoid duplicate history loads when reconnecting");
    }
    {
        const f=fixture(); f.alerts.connect(); f.alerts.ready(); await settle();
        const before=f.refreshCalls();
        for(let n=0;n<300;n++) {f.alerts.incomingAlert();await f.alerts.advance(400);await settle();}
        assert(f.refreshCalls() >= before+100,"Continuous live events must refresh history throughout the two-minute stream");
        passed.push("bound history latency under sustained live traffic");
    }
    {
        const history={promise:Promise.resolve({content:[],unreadCount:0})}, f=fixture(null,history);
        f.alerts.connect();f.alerts.ready();await settle();
        history.promise=Promise.reject(Object.assign(new Error("temporary failure"),{status:500}));
        f.alerts.ready();await settle();
        assert(f.alerts.scheduledRetries()===1,"A failed recovery needs one retry even while SSE is healthy");
        history.promise=Promise.resolve({content:[],unreadCount:0});
        const before=f.refreshCalls(); await f.alerts.advance(5000);await settle();
        assert(f.refreshCalls()===before+1 && f.alerts.scheduledRetries()===0,"Successful retry must reload history and stop retrying");
        passed.push("retry a failed history recovery on a healthy stream");
    }
    {
        const history={promise:Promise.reject(Object.assign(new Error("temporary failure"),{status:500}))};
        const f=fixture(null,history);await f.alerts.refresh();
        assert(f.alerts.scheduledRetries()===1,"History failure should schedule recovery");
        f.switchAccount();const before=f.refreshCalls();await f.alerts.advance(5000);
        assert(f.refreshCalls()===before,"Logout must cancel the previous session's retry");
        passed.push("cancel history retry on account switch");
    }
    {
        const held=deferred(), history={promise:held.promise}, f=fixture(null,history);
        f.alerts.connect();f.alerts.ready();
        f.alerts.incomingAlert(); const first=f.alerts.advance(500); await settle();
        const before=f.refreshCalls();
        for(let n=0;n<10;n++) {f.alerts.incomingAlert();await f.alerts.advance(400);}
        assert(f.refreshCalls()===before,"Slow history requests must not be continually superseded by newer burst requests");
        held.resolve({content:[],unreadCount:0});await first;await settle();
        await f.alerts.advance(500);await settle();
        assert(f.refreshCalls()===before+1,"An event received during loading needs one follow-up refresh");
        passed.push("coalesce live events while a history request is slow");
    }
    globalThis.alertSessionTestResults = { ok: true, passed };
    globalThis.console?.log(`${passed.length} alert session tests passed`);
})().catch(error => {
    globalThis.alertSessionTestResults = { ok: false, error: String(error) };
    globalThis.console?.error(error);
    if (typeof process !== "undefined") process.exitCode = 1;
});
