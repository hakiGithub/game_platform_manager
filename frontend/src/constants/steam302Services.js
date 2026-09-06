/**
 * Steam302 服务开关元数据（key → 显示名 → 分组）
 *
 * 数据来源：Steamcommunity 302 GUI「服务列表」页（2026/08/09 规则版本）。
 * 分组顺序：Steam → EA → 其他服务。
 * 未收录的 ini 键在前端兜底显示键名（工具升级新增开关时不丢项）。
 */

export const SERVICE_GROUPS = [
  { key: "steam", label: "Steam" },
  { key: "ea", label: "EA" },
  { key: "misc", label: "其他服务" },
];

export const SERVICE_META = {
  // ---- Steam ----
  Steam_store: { name: "商店", group: "steam" },
  Steam_store_unlock: { name: "商店解锁", group: "steam" },
  Steam_community: { name: "社区", group: "steam" },
  Steam_API: { name: "API（登录/好友使用 Steam China）", group: "steam" },
  Steam_API_unlock: { name: "API 解锁", group: "steam" },
  Steam_community_unlock: { name: "社区解锁", group: "steam" },
  steamchat: { name: "好友聊天", group: "steam" },
  steamchat_unlock: { name: "好友聊天 · 图片发送修复", group: "steam" },
  baishan2ali: { name: "白山云重定向阿里云", group: "steam" },
  workshop_fix: { name: "创意工坊 / UGC 修复", group: "steam" },
  imgfix: { name: "网页布局/图片修复（CH+Akamai）", group: "steam" },
  imgfix_fastly: { name: "网页布局/图片修复（Fastly）", group: "steam" },
  Steam_cloud_google: { name: "云同步（仅 Google）", group: "steam" },
  steam_update: { name: "客户端更新", group: "steam" },
  Steam_broadcast_redir: { name: "直播修复 · 大主播列表", group: "steam" },
  Steam_broadcast_redir_unlock: { name: "直播修复 · 重启 CM（Valve 香港）", group: "steam" },
  // ---- EA ----
  EA_CloudSync: { name: "云同步", group: "ea" },
  EA_Download_Akamai: { name: "下载重定向（Akamai）", group: "ea" },
  // ---- 其他服务 ----
  recaptcha: { name: "Google 验证码", group: "misc" },
  discord: { name: "Discord 语音", group: "misc" },
  twitch: { name: "Twitch 直播", group: "misc" },
  modio: { name: "Mod.io", group: "misc" },
  minecraft: { name: "我的世界（API/官网修复）", group: "misc" },
  github: { name: "Github", group: "misc" },
  vercel: { name: "Vercel", group: "misc" },
  huggingface: { name: "Huggingface", group: "misc" },
  huggingface_download: { name: "Huggingface 模型下载", group: "misc" },
  artstation: { name: "ArtStation", group: "misc" },
  pinterest: { name: "Pinterest", group: "misc" },
  blockbench: { name: "Blockbench", group: "misc" },
  fandom_imgfix: { name: "Fandom 图片修复", group: "misc" },
  imgur: { name: "Imgur", group: "misc" },
  megaionz: { name: "Mega.io/nz", group: "misc" },
  onedrive: { name: "OneDrive 网页版", group: "misc" },
  jsdelivr: { name: "jsDelivr", group: "misc" },
  googleapis_ajax: { name: "Googleapis 重定向（Ajax）", group: "misc" },
  greasyfork: { name: "Greasy Fork", group: "misc" },
  docker: { name: "Docker（镜像加速）", group: "misc" },
  chrome_translate: { name: "Chrome/Google 翻译", group: "misc" },
  Scholar: { name: "Google 学术状况", group: "misc" },
  parsec: { name: "Parsec", group: "misc" },
  gamespot: { name: "GameSpot", group: "misc" },
  Yandex_disk: { name: "Yandex Disk", group: "misc" },
  Dropbox: { name: "Dropbox", group: "misc" },
  pixeldrain: { name: "Pixeldrain（下载默认线路代理可用）", group: "misc" },
  Spotify: { name: "Spotify", group: "misc" },
  Fallout76_respond: { name: "辐射76 服务器", group: "misc" },
  Epic_DL_redir: { name: "Epic 下载重定向", group: "misc" },
  Xbox_DL_redir: { name: "Xbox 下载重定向", group: "misc" },
  Uplay_DL_redir: { name: "Uplay 下载重定向", group: "misc" },
  csgo_demo_redir: { name: "CSGO Demo 重定向", group: "misc" },
  Monster_hunter_wilds: { name: "怪物猎人：荒野", group: "misc" },
  GDevelop: { name: "GDevelop", group: "misc" },
  Gravatar: { name: "Gravatar", group: "misc" },
};

/** 各服务的代理域名清单（由 docker/steam302/extract-services.py 离线提取） */
import serviceDomains from "./steam302-services.json";

export function serviceDomainsOf(key) {
  return serviceDomains[key] || [];
}

/**
 * 将 S302.ini 键值表整理为分组服务列表 + 高级设置
 * @param {Object<string,string>} config - ini 键值表
 * @returns {{groups: Array<{key:string,label:string,items:Array<{key:string,name:string,enabled:boolean,domains:string[],known:boolean}>}>, advanced: Array<{key:string,value:string}>}}
 */
export function buildServiceList(config) {
  const groups = SERVICE_GROUPS.map((g) => ({ ...g, items: [] }));
  const groupByKey = Object.fromEntries(groups.map((g) => [g.key, g]));
  const advanced = [];
  const seen = new Set();

  for (const [key, value] of Object.entries(config)) {
    seen.add(key);
    const meta = SERVICE_META[key];
    if (meta) {
      groupByKey[meta.group].items.push({
        key,
        name: meta.name,
        enabled: value === "1",
        domains: serviceDomainsOf(key),
        known: true,
      });
    } else {
      // 未收录键（系统设置布尔键、字符串配置）归入高级设置，不混入服务列表
      advanced.push({ key, value });
    }
  }
  // 已知但 ini 里缺失的布尔键也展示（视为关闭），避免不同版本丢项
  for (const [key, meta] of Object.entries(SERVICE_META)) {
    if (!seen.has(key)) {
      groupByKey[meta.group].items.push({ key, name: meta.name, enabled: false, domains: serviceDomainsOf(key), known: true });
    }
  }
  return { groups, advanced };
}
