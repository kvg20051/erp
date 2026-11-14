import { UserManager, WebStorageStateStore } from "oidc-client-ts";
declare global {
  interface Window {
    NIXU_CONFIG: {
      authMode: string;
      issuer?: string;
      clientId?: string;
      appUrl?: string;
    };
  }
}
const config = window.NIXU_CONFIG;
let basic = "";
export const isDemo = config.authMode === "demo";
export const manager = isDemo
  ? null
  : new UserManager({
      authority: config.issuer!,
      client_id: config.clientId || "nixu-web",
      redirect_uri: config.appUrl + "/callback",
      post_logout_redirect_uri: config.appUrl,
      response_type: "code",
      scope: "openid profile email",
      automaticSilentRenew: true,
      userStore: new WebStorageStateStore({ store: window.sessionStorage }),
    });
export function demoLogin(user: string, password: string) {
  basic = "Basic " + btoa(user + ":" + password);
}
export async function logout() {
  basic = "";
  if (manager) await manager.signoutRedirect();
  else window.location.reload();
}
export async function request(
  path: string,
  options: RequestInit = {},
): Promise<Response> {
  const user = await manager?.getUser();
  const token = isDemo ? basic : user ? "Bearer " + user.access_token : "";
  const headers = new Headers(options.headers);
  if (token) headers.set("Authorization", token);
  if (
    options.body &&
    !(options.body instanceof FormData) &&
    !headers.has("Content-Type")
  )
    headers.set("Content-Type", "application/json");
  const r = await fetch("/api/v1" + path, { ...options, headers });
  if (!r.ok) {
    let msg = "Ошибка " + r.status;
    try {
      const p = await r.json();
      msg = p.detail || msg;
    } catch {}
    throw new Error(msg);
  }
  return r;
}
export async function api(
  path: string,
  method = "GET",
  body?: unknown,
  key?: string,
) {
  const r = await request(path, {
    method,
    body: body === undefined ? undefined : JSON.stringify(body),
    headers: key ? { "Idempotency-Key": key } : {},
  });
  const text = await r.text();
  return text ? JSON.parse(text) : null;
}
export async function download(path: string, name: string) {
  const r = await request(path);
  const url = URL.createObjectURL(await r.blob());
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
