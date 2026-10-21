// Nova web's service worker: only so browsers offer to install Nova as an app (Chrome and Edge ask for one).
// It caches nothing and changes nothing — every request goes to the server as usual, so pages are never stale.
self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", e => e.waitUntil(self.clients.claim()));
self.addEventListener("fetch", () => {});
