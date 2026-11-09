// Only invalidations cross tabs. Each tab reloads alerts through its own authenticated session.
const listeners = new Set();
const notify = () => {
    for (const listener of listeners) {
        try { listener(); } catch (error) { console.error(error); }
    }
};
const channel = typeof BroadcastChannel === "undefined" ? null : new BroadcastChannel("tripsense-alerts");
if (channel) channel.onmessage = event => {
    if (event.data === "changed") notify();
};

// Storage events cover browsers where BroadcastChannel is unavailable.
const STORAGE_KEY = "tripsense-alerts-changed";
if (!channel) globalThis.addEventListener?.("storage", event => {
    if (event.key === STORAGE_KEY) notify();
});

export function onAlertsInvalidated(listener) {
    listeners.add(listener);
    return () => listeners.delete(listener);
}

export function invalidateAlerts() {
    notify();
    if (channel) {
        try { channel.postMessage("changed"); } catch { /* A closed tab must not fail a saved read. */ }
    }
    else {
        try { localStorage.setItem(STORAGE_KEY, `${Date.now()}:${Math.random()}`); }
        catch { /* Visibility and reconnection still refresh history when storage is unavailable. */ }
    }
}
