// Playwright checks for the landing site (handover §7.4). Build first: node tools/build-site.mjs [--offline].
// Serves site/ under /harkara/ (like GitHub Pages) with tests/site/serve.mjs; tests never touch the internet.
import { defineConfig } from '@playwright/test';

const PORT = Number(process.env.SITE_PORT || 4391);

export default defineConfig({
  testDir: 'tests/site',
  outputDir: 'test-results/site',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['list'], ['github']] : [['list']],
  use: {
    baseURL: `http://127.0.0.1:${PORT}/harkara/`,
    browserName: 'chromium',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { browserName: 'chromium' } }],
  webServer: {
    command: `node tests/site/serve.mjs ${PORT}`,
    url: `http://127.0.0.1:${PORT}/harkara/`,
    reuseExistingServer: !process.env.CI,
    timeout: 30_000,
  },
});
