/**
 * L4D2 游戏常量
 */

// 难度等级
export const DIFFICULTIES = {
  easy: {
    value: 'easy',
    label: '简单',
    color: '#52cf82'
  },
  normal: {
    value: 'normal',
    label: '普通',
    color: '#27b5f3'
  },
  hard: {
    value: 'hard',
    label: '困难',
    color: '#f2b84b'
  },
  impossible: {
    value: 'impossible',
    label: '专家',
    color: '#f0646a'
  }
} as const

// 游戏模式
export const GAME_MODES = {
  coop: {
    value: 'coop',
    label: '合作模式',
    description: '4人合作对抗AI感染者'
  },
  versus: {
    value: 'versus',
    label: '对抗模式',
    description: '8人对战，轮流扮演生还者和感染者'
  },
  survival: {
    value: 'survival',
    label: '生存模式',
    description: '尽可能长时间存活'
  },
  scavenge: {
    value: 'scavenge',
    label: '清道夫模式',
    description: '收集油桶加油'
  },
  realism: {
    value: 'realism',
    label: '写实模式',
    description: '更真实的合作模式'
  }
} as const

// 地图类型
export const MAP_TYPES = {
  campaign: {
    value: 'campaign',
    label: '战役',
    icon: 'Flag'
  },
  versus: {
    value: 'versus',
    label: '对抗',
    icon: 'Sword'
  },
  survival: {
    value: 'survival',
    label: '生存',
    icon: 'Timer'
  },
  scavenge: {
    value: 'scavenge',
    label: '清道夫',
    icon: 'Box'
  }
} as const

// 官方战役地图
export const OFFICIAL_CAMPAIGNS = [
  { name: 'c1m1_hotel', displayName: '死亡中心 - 酒店' },
  { name: 'c2m1_highway', displayName: '黑色狂欢节 - 公路' },
  { name: 'c3m1_plankcountry', displayName: '沼泽激战 - 木板路' },
  { name: 'c4m1_milltown_a', displayName: '暴风骤雨 - 小镇' },
  { name: 'c5m1_waterfront', displayName: '教区 - 码头' },
  { name: 'c6m1_riverbank', displayName: '短暂时刻 - 河岸' },
  { name: 'c7m1_docks', displayName: '牺牲 - 码头' },
  { name: 'c8m1_apartment', displayName: '毫不留情 - 公寓' },
  { name: 'c9m1_alleys', displayName: '坠机险途 - 小巷' },
  { name: 'c10m1_caves', displayName: '死亡丧钟 - 矿洞' },
  { name: 'c11m1_greenhouse', displayName: '寂静时分 - 温室' },
  { name: 'c12m1_hilltop', displayName: '血腥收获 - 山顶' },
  { name: 'c13m1_alpinecreek', displayName: '冷溪 - 高山溪流' }
]

// 服务器区域
export const SERVER_REGIONS = [
  { value: 0, label: '美国东部' },
  { value: 1, label: '美国西部' },
  { value: 2, label: '南美' },
  { value: 3, label: '欧洲' },
  { value: 4, label: '亚洲' },
  { value: 5, label: '澳洲' },
  { value: 6, label: '中东' },
  { value: 7, label: '非洲' },
  { value: 255, label: '世界' }
]

// 管理员权限标志
export const ADMIN_FLAGS = {
  reservation: { flag: 'a', label: '预留槽位' },
  generic: { flag: 'b', label: '通用管理' },
  kick: { flag: 'c', label: '踢人' },
  ban: { flag: 'd', label: '封禁' },
  unban: { flag: 'e', label: '解封' },
  slay: { flag: 'f', label: '处决' },
  changemap: { flag: 'g', label: '换图' },
  cvars: { flag: 'h', label: '修改CVar' },
  config: { flag: 'i', label: '执行配置' },
  chat: { flag: 'j', label: '管理员聊天' },
  vote: { flag: 'k', label: '投票' },
  password: { flag: 'l', label: '设置密码' },
  rcon: { flag: 'm', label: 'RCON权限' },
  cheats: { flag: 'n', label: '作弊权限' },
  root: { flag: 'z', label: '超级管理员' }
} as const

// 常用 RCON 命令
export const COMMON_RCON_COMMANDS = [
  { command: 'status', description: '查看服务器状态' },
  { command: 'listplayers', description: '列出所有玩家' },
  { command: 'sm_admins', description: '查看在线管理员' },
  { command: 'sm_plugins list', description: '列出所有插件' },
  { command: 'sm_cvarlist', description: '列出所有CVar' },
  { command: 'sm_maplist', description: '列出所有地图' },
  { command: 'sm_who', description: '查看玩家权限' },
  { command: 'meta version', description: '查看MetaMod版本' },
  { command: 'sm version', description: '查看SourceMod版本' }
]

// 服务器状态
export const SERVER_STATUS = {
  running: { value: 'running', label: '运行中', color: '#52cf82', type: 'success' },
  stopped: { value: 'stopped', label: '已停止', color: '#8997a8', type: 'info' },
  starting: { value: 'starting', label: '启动中', color: '#f2b84b', type: 'warning' },
  stopping: { value: 'stopping', label: '停止中', color: '#f2b84b', type: 'warning' },
  error: { value: 'error', label: '错误', color: '#f0646a', type: 'danger' }
} as const

export type Difficulty = keyof typeof DIFFICULTIES
export type GameMode = keyof typeof GAME_MODES
export type MapType = keyof typeof MAP_TYPES
export type ServerStatusType = keyof typeof SERVER_STATUS
