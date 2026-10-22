import { defineConfig } from '@playwright/test';
import { e2eEnv } from './e2e/support/env.js';

// E2E_JSON_REPORT 存在时额外挂一个 JSON reporter（票 03：结果落盘的数据源）。
// 默认不挂，避免日常 `npx playwright test` 往 e2e/.last-run 写文件。
const jsonReport = process.env.E2E_JSON_REPORT;

// E2E 配置：仅 Chromium；失败留存截图与 trace；报告输出 e2e/.report（npm run e2e:report 查看）。
// baseURL 等缺省值统一来自 e2e/support/env.js（环境契约唯一来源）。
export default defineConfig({
  testDir: './e2e',
  testMatch: '**/*.spec.js',
  timeout: 30_000,
  workers: e2eEnv.workers,
  forbidOnly: true,
  reporter: [
    ['list'],
    ['html', { outputFolder: 'e2e/.report', open: 'never' }],
    ...(jsonReport ? [['json', { outputFile: jsonReport }]] : []),
  ],
  outputDir: 'e2e/.artifacts',
  use: {
    baseURL: e2eEnv.baseUrl,
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
    actionTimeout: 10_000,
    viewport: { width: 1440, height: 900 },
  },
  projects: [{ name: 'chromium', use: { browserName: 'chromium' } }],
});
