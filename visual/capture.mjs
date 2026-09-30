import { chromium } from "playwright";
import fs from "node:fs/promises";

const baseUrl = process.argv[2] || "http://127.0.0.1:4173/";
const outDir = process.argv[3] || "visual-results";
await fs.mkdir(outDir, { recursive: true });

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1280, height: 720 }, deviceScaleFactor: 1 });
await page.goto(baseUrl, { waitUntil: "networkidle", timeout: 60000 });
await page.evaluate(() => localStorage.clear());
await page.reload({ waitUntil: "networkidle", timeout: 60000 });
await page.waitForFunction(() => !!globalThis.__photoTvTest, null, { timeout: 30000 });
await page.waitForTimeout(4000);

const screens = ["preview", "photos", "editor", "settings"];
for (let i = 0; i < screens.length; i++) {
  await page.evaluate((index) => globalThis.__photoTvTest.setPage(index), i);
  await page.waitForTimeout(900);
  await page.locator("#tv").screenshot({ path: `${outDir}/${screens[i]}.png` });
  console.log(`captured ${screens[i]}`);
}
await browser.close();
