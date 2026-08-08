const { chromium } = require('playwright-core');
const path = require('path');
const fs = require('fs');
const http = require('http');

const screenshotDir = path.join(__dirname, 'screenshots');
if (!fs.existsSync(screenshotDir)) fs.mkdirSync(screenshotDir, { recursive: true });

// 辅助函数：HTTP 请求
async function apiRequest(path, method = 'GET', body = null, token = null) {
  return new Promise((resolve, reject) => {
    const options = {
      hostname: 'localhost',
      port: 8080,
      path: '/api' + path,
      method,
      headers: { 'Content-Type': 'application/json' }
    };
    if (token) options.headers['Authorization'] = 'Bearer ' + token;
    const req = http.request(options, (res) => {
      let data = '';
      res.on('data', chunk => data += chunk);
      res.on('end', () => {
        try { resolve(JSON.parse(data)); } catch (e) { resolve({ raw: data }); }
      });
    });
    req.on('error', reject);
    if (body) req.write(JSON.stringify(body));
    req.end();
  });
}

async function verify() {
  console.log('正在启动 Chrome...');
  const browser = await chromium.launch({
    channel: 'chrome',
    headless: false,
    args: ['--disable-blink-features=AutomationControlled']
  });

  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const page = await context.newPage();

  // 1. 登录获取 Token
  console.log('1. 调用后端 API 登录');
  const loginRes = await apiRequest('/auth/login', 'POST', { username: 'admin', password: 'admin123' });
  const token = loginRes.data?.token;
  if (!token) { console.log('登录失败', loginRes); await browser.close(); return; }
  console.log('   登录成功');

  // 2. 获取实例列表
  console.log('2. 获取实例列表');
  const instanceRes = await apiRequest('/instances?page=1&size=10', 'GET', null, token);
  const instances = instanceRes.data?.records || [];
  console.log('   实例数量:', instances.length);
  const l4d2Instance = instances.find(i => i.gameCode === 'l4d2' || i.gameName?.includes('L4D2') || i.gameName?.includes('求生'));
  if (!l4d2Instance) {
    console.log('   未找到 L4D2 实例');
    await browser.close();
    return;
  }
  console.log('   L4D2 实例:', l4d2Instance.id, l4d2Instance.instanceName);

  // 3. 打开前端并注入 Token
  console.log('3. 打开前端主应用');
  await page.goto('http://localhost:3000/login', { waitUntil: 'networkidle' });
  await page.evaluate((t) => {
    localStorage.setItem('token', t);
    localStorage.setItem('tokenType', 'Bearer');
  }, token);
  await page.goto('http://localhost:3000/dashboard', { waitUntil: 'networkidle' });
  await page.waitForTimeout(2000);
  await page.screenshot({ path: path.join(screenshotDir, '03-dashboard.png'), fullPage: true });
  console.log('   截图已保存: 03-dashboard.png');

  // 4. 直接访问 L4D2 实例详情页
  console.log('4. 访问 L4D2 实例详情页');
  const detailUrl = `http://localhost:3000/instance/detail/${l4d2Instance.id}`;
  await page.goto(detailUrl, { waitUntil: 'networkidle' });
  await page.waitForTimeout(3000);
  await page.screenshot({ path: path.join(screenshotDir, '04-instance-detail.png'), fullPage: true });
  console.log('   截图已保存: 04-instance-detail.png');

  // 5. 查找并点击插件 Tab
  console.log('5. 查找插件 Tab');
  const allTabs = await page.locator('.el-tabs__item, [role="tab"]').allInnerTexts();
  console.log('   所有 Tab:', allTabs);

  const pluginTab = await page.locator('.el-tabs__item, [role="tab"]').filter({ hasText: /插件|Plugin/i }).first();
  if (await pluginTab.count() > 0) {
    console.log('   找到插件 Tab，点击');
    await pluginTab.click();
    await page.waitForTimeout(6000);
    await page.screenshot({ path: path.join(screenshotDir, '05-plugin-tab.png'), fullPage: true });
    console.log('   截图已保存: 05-plugin-tab.png');

    // 6. 检查 Wujie 子应用加载状态
    console.log('6. 检查 Wujie 子应用加载状态');
    const wujieInfo = await page.evaluate(() => {
      const frames = document.querySelectorAll('iframe');
      const wujieApps = document.querySelectorAll('wujie-app, [data-wujie]');
      return {
        iframeCount: frames.length,
        wujieAppCount: wujieApps.length,
        iframeSrcs: Array.from(frames).map(f => f.src),
        poweredByWujie: window.__POWERED_BY_WUJIE__,
        hasWujieVue: !!document.querySelector('[class*="wujie"]')
      };
    });
    console.log('   Wujie 信息:', wujieInfo);

    // 7. 检查子应用 iframe 内容
    if (wujieInfo.iframeCount > 0) {
      console.log('7. 检查子应用 iframe 内容');
      const iframe = page.frameLocator('iframe').first();
      try {
        const bodyText = await iframe.locator('body').innerText({ timeout: 5000 });
        console.log('   iframe body 文本:', bodyText.substring(0, 300));
        await page.screenshot({ path: path.join(screenshotDir, '06-wujie-content.png'), fullPage: true });
        console.log('   截图已保存: 06-wujie-content.png');
      } catch (e) {
        console.log('   无法读取 iframe 内容:', e.message);
      }
    }
  } else {
    console.log('   未找到插件 Tab');
    await page.screenshot({ path: path.join(screenshotDir, '05-no-plugin-tab.png'), fullPage: true });
  }

  // 8. 后端资源验证
  console.log('8. 后端插件资源验证');
  const resPage = await context.newPage();
  const res = await resPage.goto('http://localhost:8080/api/plugins/l4d2/ui/index.html', { waitUntil: 'domcontentloaded' });
  console.log('   资源状态:', res?.status());
  await resPage.close();

  console.log('\n验证完成！截图保存在:', screenshotDir);
  await browser.close();
}

verify().catch(err => {
  console.error('验证失败:', err);
  process.exit(1);
});
