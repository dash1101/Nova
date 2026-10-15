// Nova web — Security & privacy: everything about who and what can reach the server, in one place. This browser's key,
// how risky actions are approved (phone, authenticator codes, passkeys), devices, remote access, what Nova keeps
// private, and the latest security activity.
import { S, $, $$, esc, get, post, isAdmin, prefs } from "./core.js";
import { I, row, group, sec, note, links, toast, confirm, ask, sheet, closeSheet, switchRow, wireCommon, copyCmd } from "./ui.js";

// ── passkeys (WebAuthn) in the browser ──
const b2u = b => btoa(String.fromCharCode(...new Uint8Array(b))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const u2b = s => Uint8Array.from(atob(String(s).replace(/-/g, "+").replace(/_/g, "/") + "===".slice((String(s).length + 3) % 4)), c => c.charCodeAt(0));
export const passkeysHere = () => !!window.PublicKeyCredential && !/^(\d+\.){3}\d+$|^\[|^localhost$/.test(location.hostname);

/** Ask the device for a passkey answer to a challenge from the server (pairing or approving). */
export async function passkeyAnswer(opts) {
  const cred = await navigator.credentials.get({ publicKey: { challenge: u2b(opts.challenge), rpId: opts.rpId, timeout: opts.timeout, userVerification: "required", allowCredentials: [] } });
  return { id: cred.id, clientDataJSON: b2u(cred.response.clientDataJSON), authenticatorData: b2u(cred.response.authenticatorData), signature: b2u(cred.response.signature) };
}

async function addPasskey() {
  if (!passkeysHere()) return toast("Open Nova at its web address (not the IP address) to add a passkey");
  const name = await ask("Name this passkey", "My phone", "", "Next", 40); if (name == null) return;
  try {
    const o = await post("/api/v1/passkeys/register/start", {});          // approved on your phone first
    const cred = await navigator.credentials.create({ publicKey: { ...o, challenge: u2b(o.challenge), user: { ...o.user, id: u2b(o.user.id) },
      excludeCredentials: (o.excludeCredentials || []).map(c => ({ ...c, id: u2b(c.id) })) } });
    await post("/api/v1/passkeys/register/finish", { name: name || "Passkey", clientDataJSON: b2u(cred.response.clientDataJSON), attestationObject: b2u(cred.response.attestationObject) });
    toast("Passkey added");
  } catch (e) { toast(e.name === "NotAllowedError" ? "Cancelled" : e.message); }
}

export async function security(ctx) {
  const draw = () => {
    const me = S.me || {}, tp = S.cache["/api/v1/totp"], pk = S.cache["/api/v1/passkeys"], act = S.cache["/api/v1/security/activity"]?.events || [];
    const keys = pk?.passkeys || [];
    ctx.show(`${sec("This browser")}${group(
        row("Signing key", { sub: "Made in this browser and can't be copied out — every request is signed with it", icon: "key", tint: "var(--green)" })
        + row(me.role === "viewer" ? "View-only access" : "Admin", { sub: me.user ? "Paired for " + me.user : "", icon: "shield", tint: "var(--blue)" })
        + row("Remove this browser", { sub: "Erases its key here and its access on the server", icon: "del", tint: "var(--red)", click: "forget" }))}
      ${sec("Approving risky actions")}${group(
        row("Your phone", { sub: "Restarts, installs, unmounting, shells and the like wait for an admin phone's fingerprint (or sudo nova approve on the server)", icon: "phone", tint: "var(--blue)" })
        + (isAdmin() ? row("Authenticator codes", { sub: tp?.enabled ? `On since ${tp.since || "—"} · a 6-digit code approves a browser, or what a browser asks for` : "Off · for a computer with no phone or SSH at hand", blue: true, icon: "clock", click: "totp" }) : "")
        + (isAdmin() ? row("Passkeys", { sub: keys.length ? `${keys.length} set up · add a browser or approve a request with your fingerprint, face or PIN` : "None yet · use your password manager's passkey instead of the phone", blue: true, icon: "key", click: "pk" }) : ""))}
      ${isAdmin() && keys.length ? group(keys.map(k => row(k.name || "Passkey", { sub: `${k.rp} · added ${k.created || "—"}${k.last_used ? " · last used " + k.last_used : ""}`, icon: "key", tint: "var(--sub)", click: "pkrm:" + k.id,
          end: `<button class="rmbtn press" data-act="pkrm:${esc(k.id)}" aria-label="Remove">${I("del")}</button>` })).join("")) : ""}
      ${isAdmin() && !passkeysHere() ? note("Passkeys belong to a web address, so they work when Nova is opened at its name (your Cloudflare address), not at an IP address.") : ""}
      ${sec("Who can reach the server")}${group(
        row("Users & devices", { sub: "Phones, browsers and watches — roles, remove any of them", blue: true, icon: "group", click: "go:devices" })
        + row("Remote access", { sub: "Cloudflare: which addresses are open, and who can log in to them", blue: true, icon: "cloud", click: "go:access" })
        + row("Servers", { sub: "Other Nova servers this web can control", blue: true, icon: "dns", click: "go:servers" }))}
      ${sec("Privacy")}${group(
        switchRow("Search suggestions on the Start page", "Typing sends your words to the search engine for suggestions. Off: nothing leaves until you press Enter", prefs.startSuggest !== false, "sugg")
        + row("Privacy policy", { sub: "Nova collects nothing. The apps talk only to your server", icon: "shield", tint: "var(--green)", click: "policy" }))}
      ${isAdmin() ? sec("Recent security activity") + (act.length ? group(act.slice(0, 15).map(e => row(e.why || e.path, { sub: `${e.t} · ${e.device || "—"}${e.via ? " · " + e.via : ""}`, icon: e.result >= 400 ? "warn" : "okc",
          tint: e.result >= 400 ? "var(--amber)" : "var(--green)" })).join("")) : note("Nothing yet.")) : ""}`,
      { title: "Security & privacy" });
    wireCommon(ctx.root);
  };
  ctx.handlers({
    forget: async () => (await import("./views.js")).forgetBrowser(),
    totp: async () => { await (await import("./views.js")).totpSetup(); await get("/api/v1/totp").catch(() => {}); draw(); },
    pk: async () => { await addPasskey(); await get("/api/v1/passkeys").catch(() => {}); draw(); },
    pkrm: async id => {
      const k = (S.cache["/api/v1/passkeys"]?.passkeys || []).find(x => x.id === id);
      if (!(await confirm(`Remove ${k?.name || "this passkey"}?`, "It can't add browsers or approve requests any more. (It stays in your password manager — delete it there too.)", "Remove"))) return;
      try { await post("/api/v1/passkeys/remove", { id }); await get("/api/v1/passkeys"); draw(); } catch (e) { toast(e.message); }
    },
    sugg: () => { prefs.startSuggest = prefs.startSuggest === false; draw(); },
    policy: () => window.open("https://dash1101.github.io/Nova/privacy.html", "_blank", "noopener"),
  });
  draw();
  try { await Promise.all([isAdmin() ? get("/api/v1/totp").catch(() => {}) : null, isAdmin() ? get("/api/v1/passkeys").catch(() => {}) : null, isAdmin() ? get("/api/v1/security/activity").catch(() => {}) : null]); } catch {}
  if (ctx.alive()) draw();
}
