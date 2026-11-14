import { chromium } from "@playwright/test";
import fs from "node:fs/promises";
const browser = await chromium.launch({
  headless: true,
  ...(process.env.BROWSER_CHANNEL
    ? { channel: process.env.BROWSER_CHANNEL }
    : {}),
});
const page = await browser.newPage({ viewport: { width: 1500, height: 1000 } });
const errors = [];
page.on("pageerror", (e) => errors.push(e.message));
await page.goto("http://127.0.0.1:5173");
await page
  .getByLabel("Пароль", { exact: true })
  .fill(process.env.DEMO_PASSWORD || "nixu-local-test-2026");
await page.getByRole("button", { name: "Войти", exact: true }).click();
await page
  .getByRole("heading", { name: "От заявки — к результату." })
  .waitFor();
await page.screenshot({
  path: "../../docs/screenshots/dashboard.png",
  fullPage: true,
});
await page.getByRole("menuitem", { name: "Документы" }).click();
await page
  .getByRole("button", { name: "Создать документ", exact: true })
  .click();
await page.getByRole("button", { name: "Сохранить черновик" }).click();
await page.getByRole("dialog").filter({ hasText: "PO-" }).waitFor();
await page.getByRole("button", { name: "Провести", exact: true }).click();
await page
  .getByText("Документ требует согласования руководителя", { exact: true })
  .waitFor();
await page.screenshot({
  path: "../../docs/screenshots/document.png",
  fullPage: true,
});
await page
  .getByRole("button", { name: "На согласование", exact: true })
  .click();
await page
  .getByRole("dialog")
  .getByText("На согласовании", { exact: true })
  .waitFor();
await page.getByRole("dialog").getByLabel("Close", { exact: true }).click();
await page.getByRole("dialog").waitFor({state:"hidden"});
await page.getByRole("menuitem", { name: "Персонал" }).click();
await page
  .getByRole("button", { name: "Новый сотрудник", exact: true })
  .waitFor();
await page.screenshot({
  path: "../../docs/screenshots/employees.png",
  fullPage: true,
});
if (errors.length) throw Error(errors.join("\n"));
console.log(
  "Browser smoke PASS: login, dashboard, create PO, approval enforcement, submit, HR navigation. No JS exceptions.",
);
await browser.close();
