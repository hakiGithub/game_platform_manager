/**
 * 服务器状态解析工具
 */

export interface ParsedStatus {
  hostname: string
  version: string
  map: string
  players: number
  maxPlayers: number
  bots: number
  difficulty: string
  gameMode: string
  fps: number
  uptime: number
  ip: string
  port: number
  steamId: string
}

/**
 * 解析 status 命令输出
 */
export function parseStatusOutput(output: string): ParsedStatus | null {
  try {
    const lines = output.split('\n')
    const result: Partial<ParsedStatus> = {}

    for (const line of lines) {
      const trimmed = line.trim()
      
      // 解析主机名
      if (trimmed.startsWith('hostname:')) {
        result.hostname = trimmed.replace('hostname:', '').trim()
      }
      
      // 解析版本
      if (trimmed.startsWith('version :')) {
        const match = trimmed.match(/version\s*:\s*(\S+)/)
        if (match) {
          result.version = match[1]
        }
      }
      
      // 解析地图
      if (trimmed.startsWith('map     :')) {
        const match = trimmed.match(/map\s*:\s*(\S+)/)
        if (match) {
          result.map = match[1]
        }
      }
      
      // 解析玩家数
      if (trimmed.startsWith('players :')) {
        const match = trimmed.match(/players\s*:\s*(\d+)\s*\((\d+)\s*max\)/)
        if (match) {
          result.players = parseInt(match[1])
          result.maxPlayers = parseInt(match[2])
        }
      }
      
      // 解析 bots
      if (trimmed.includes('bots')) {
        const match = trimmed.match(/bots\s*:\s*(\d+)/)
        if (match) {
          result.bots = parseInt(match[1])
        }
      }
      
      // 解析 IP 和端口
      if (trimmed.startsWith('udp/ip  :')) {
        const match = trimmed.match(/udp\/ip\s*:\s*(\d+\.\d+\.\d+\.\d+):(\d+)/)
        if (match) {
          result.ip = match[1]
          result.port = parseInt(match[2])
        }
      }
      
      // 解析 Steam ID
      if (trimmed.startsWith('steamid :')) {
        const match = trimmed.match(/steamid\s*:\s*(\S+)/)
        if (match) {
          result.steamId = match[1]
        }
      }
    }

    return result as ParsedStatus
  } catch (error) {
    console.error('Failed to parse status output:', error)
    return null
  }
}

/**
 * 解析玩家列表
 */
export function parsePlayerList(output: string): Array<{
  index: number
  name: string
  steamId: string
  time: string
  ping: number
  state: string
}> {
  const players: Array<{
    index: number
    name: string
    steamId: string
    time: string
    ping: number
    state: string
  }> = []

  try {
    const lines = output.split('\n')
    
    for (const line of lines) {
      // 匹配玩家行格式: # 1 "PlayerName" STEAM_1:0:123456 12:34 56 active
      const match = line.match(/^#\s*(\d+)\s+"([^"]+)"\s+(\S+)\s+(\d+:\d+)\s+(\d+)\s+(\w+)/)
      if (match) {
        players.push({
          index: parseInt(match[1]),
          name: match[2],
          steamId: match[3],
          time: match[4],
          ping: parseInt(match[5]),
          state: match[6]
        })
      }
    }
  } catch (error) {
    console.error('Failed to parse player list:', error)
  }

  return players
}

/**
 * 解析地图名称
 */
export function parseMapName(mapName: string): {
  campaign: string
  chapter: number
  displayName: string
} {
  // 地图名称格式: c1m1_hotel
  const match = mapName.match(/c(\d+)m(\d+)_(.+)/i)
  
  if (match) {
    const campaignNum = parseInt(match[1])
    const chapterNum = parseInt(match[2])

    // 根据战役编号获取战役名称
    const campaignNames: Record<number, string> = {
      1: '死亡中心',
      2: '黑色狂欢节',
      3: '沼泽激战',
      4: '暴风骤雨',
      5: '教区',
      6: '短暂时刻',
      7: '牺牲',
      8: '毫不留情',
      9: '坠机险途',
      10: '死亡丧钟',
      11: '寂静时分',
      12: '血腥收获',
      13: '冷溪'
    }
    
    return {
      campaign: campaignNames[campaignNum] || `战役 ${campaignNum}`,
      chapter: chapterNum,
      displayName: `${campaignNames[campaignNum] || `战役 ${campaignNum}`} - 第${chapterNum}章`
    }
  }
  
  return {
    campaign: '未知',
    chapter: 0,
    displayName: mapName
  }
}

/**
 * 解析服务器 FPS
 */
export function parseFps(output: string): number | null {
  const match = output.match(/fps\s*:\s*(\d+)/i)
  return match ? parseInt(match[1]) : null
}

/**
 * 解析运行时间
 */
export function parseUptime(output: string): number | null {
  // 格式: uptime: 1h 23m 45s
  const match = output.match(/uptime\s*:\s*(?:(\d+)h\s*)?(?:(\d+)m\s*)?(?:(\d+)s)?/i)
  
  if (match) {
    const hours = parseInt(match[1] || '0')
    const minutes = parseInt(match[2] || '0')
    const seconds = parseInt(match[3] || '0')
    
    return hours * 3600 + minutes * 60 + seconds
  }
  
  return null
}

/**
 * 格式化运行时间
 */
export function formatUptime(seconds: number): string {
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  const secs = seconds % 60
  
  if (hours > 0) {
    return `${hours}小时 ${minutes}分钟`
  } else if (minutes > 0) {
    return `${minutes}分钟 ${secs}秒`
  } else {
    return `${secs}秒`
  }
}

/**
 * 解析难度
 */
export function parseDifficulty(value: string): string {
  const difficultyMap: Record<string, string> = {
    '0': 'easy',
    '1': 'easy',
    'easy': 'easy',
    '1.0': 'normal',
    'normal': 'normal',
    '2.0': 'hard',
    'hard': 'hard',
    'impossible': 'impossible',
    '3.0': 'impossible'
  }
  
  return difficultyMap[value.toLowerCase()] || 'normal'
}

/**
 * 解析游戏模式
 */
export function parseGameMode(mode: string): string {
  const modeMap: Record<string, string> = {
    'coop': 'coop',
    'versus': 'versus',
    'survival': 'survival',
    'scavenge': 'scavenge',
    'realism': 'realism',
    'teamversus': 'versus',
    'teamscavenge': 'scavenge',
    'mutation1': 'coop',
    'mutation2': 'versus'
  }
  
  return modeMap[mode.toLowerCase()] || 'coop'
}
