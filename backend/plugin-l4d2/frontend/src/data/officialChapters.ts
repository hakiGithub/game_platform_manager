/**
 * L4D2 官方战役章节目录（官方章节目录，ADR 语境见 CONTEXT.md「地图领域」）
 *
 * 数据来源：l4d2-server-next 参考项目 frontend/src/data/officialMaps.ts（14 个战役全章节）。
 * 官方地图内置于游戏本体，无需服务器 addons 扫描即可作为换图目标；
 * 服务器 addons 扫描到的战役若章节码命中本目录则视为官方（去重跳过），否则归为三方地图。
 */
export interface OfficialChapter {
  code: string
  title: string
  modes: string[]
}

export interface OfficialCampaign {
  title: string
  chapters: OfficialChapter[]
}

export const officialChapters: OfficialCampaign[] = [
  {
    title: '死亡中心',
    chapters: [
      { code: 'c1m1_hotel', title: '旅馆', modes: ['coop', 'realism', 'versus'] },
      { code: 'c1m2_streets', title: '街道', modes: ['coop', 'realism', 'versus'] },
      { code: 'c1m3_mall', title: '购物中心', modes: ['coop', 'realism', 'versus'] },
      { code: 'c1m4_atrium', title: '中厅', modes: ['coop', 'realism', 'versus'] }
    ]
  },
  {
    title: '黑色狂欢节',
    chapters: [
      { code: 'c2m1_highway', title: '高速公路', modes: ['coop', 'realism', 'versus'] },
      { code: 'c2m2_fairgrounds', title: '游乐场', modes: ['coop', 'realism', 'versus'] },
      { code: 'c2m3_coaster', title: '过山车', modes: ['coop', 'realism', 'versus'] },
      { code: 'c2m4_barns', title: '谷仓', modes: ['coop', 'realism', 'versus'] },
      { code: 'c2m5_concert', title: '音乐会', modes: ['coop', 'realism', 'versus'] }
    ]
  },
  {
    title: '沼泽激战',
    chapters: [
      { code: 'c3m1_plankcountry', title: '乡村', modes: ['coop', 'realism', 'versus'] },
      { code: 'c3m2_swamp', title: '沼泽', modes: ['coop', 'realism', 'versus'] },
      { code: 'c3m3_shantytown', title: '贫民窟', modes: ['coop', 'realism', 'versus'] },
      { code: 'c3m4_plantation', title: '种植园', modes: ['coop', 'realism', 'versus'] }
    ]
  },
  {
    title: '暴风骤雨',
    chapters: [
      { code: 'c4m1_milltown_a', title: '小镇', modes: ['coop', 'realism', 'versus'] },
      { code: 'c4m2_sugarmill_a', title: '糖厂', modes: ['coop', 'realism', 'versus'] },
      { code: 'c4m3_sugarmill_b', title: '逃离糖厂', modes: ['coop', 'realism', 'versus'] },
      { code: 'c4m4_milltown_b', title: '重返小镇', modes: ['coop', 'realism', 'versus'] },
      { code: 'c4m5_milltown_escape', title: '逃离小镇', modes: ['coop', 'realism', 'versus'] }
    ]
  },
  {
    title: '教区',
    chapters: [
      { code: 'c5m1_waterfront', title: '码头', modes: ['coop', 'realism', 'versus'] },
      { code: 'c5m2_park', title: '公园', modes: ['coop', 'realism', 'versus'] },
      { code: 'c5m3_cemetery', title: '墓地', modes: ['coop', 'realism', 'versus'] },
      { code: 'c5m4_quarter', title: '特区', modes: ['coop', 'realism', 'versus'] },
      { code: 'c5m5_bridge', title: '大桥', modes: ['coop', 'realism', 'versus'] }
    ]
  },
  {
    title: '消逝',
    chapters: [
      { code: 'c6m1_riverbank', title: '河畔', modes: ['coop', 'realism', 'versus'] },
      { code: 'c6m2_bedlam', title: '地下通道', modes: ['coop', 'realism', 'versus'] },
      { code: 'c6m3_port', title: '港口', modes: ['coop', 'realism', 'versus'] }
    ]
  },
  {
    title: '牺牲',
    chapters: [
      { code: 'c7m1_docks', title: '码头', modes: ['coop', 'realism', 'versus'] },
      { code: 'c7m2_barge', title: '驳船', modes: ['coop', 'realism', 'versus'] },
      { code: 'c7m3_port', title: '港口', modes: ['coop', 'realism', 'versus'] }
    ]
  },
  {
    title: '毫不留情',
    chapters: [
      { code: 'c8m1_apartment', title: '公寓', modes: ['coop', 'realism', 'versus'] },
      { code: 'c8m2_subway', title: '地铁', modes: ['coop', 'realism', 'versus'] },
      { code: 'c8m3_sewers', title: '下水道', modes: ['coop', 'realism', 'versus'] },
      { code: 'c8m4_interior', title: '医院', modes: ['coop', 'realism', 'versus'] },
      { code: 'c8m5_rooftop', title: '屋顶', modes: ['coop', 'realism', 'versus'] }
    ]
  },
  {
    title: '坠机险途',
    chapters: [
      { code: 'c9m1_alleys', title: '小巷', modes: ['coop', 'realism', 'versus'] },
      { code: 'c9m2_lots', title: '卡车停车场', modes: ['coop', 'realism', 'versus'] }
    ]
  },
  {
    title: '死亡丧钟',
    chapters: [
      { code: 'c10m1_caves', title: '洞穴', modes: ['coop', 'realism', 'versus'] },
      { code: 'c10m2_drainage', title: '水沟', modes: ['coop', 'realism', 'versus'] },
      { code: 'c10m3_ranchhouse', title: '教堂', modes: ['coop', 'realism', 'versus'] },
      { code: 'c10m4_mainstreet', title: '小镇', modes: ['coop', 'realism', 'versus'] },
      { code: 'c10m5_houseboat', title: '船屋', modes: ['coop', 'realism', 'versus'] }
    ]
  },
  {
    title: '静寂时分',
    chapters: [
      { code: 'c11m1_greenhouse', title: '温室', modes: ['coop', 'realism', 'versus'] },
      { code: 'c11m2_offices', title: '起重机', modes: ['coop', 'realism', 'versus'] },
      { code: 'c11m3_garage', title: '建筑工地', modes: ['coop', 'realism', 'versus'] },
      { code: 'c11m4_terminal', title: '航站楼', modes: ['coop', 'realism', 'versus'] },
      { code: 'c11m5_runway', title: '飞机跑道', modes: ['coop', 'realism', 'versus'] }
    ]
  },
  {
    title: '血腥收获',
    chapters: [
      { code: 'c12m1_hilltop', title: '森林', modes: ['coop', 'realism', 'versus'] },
      { code: 'c12m2_traintunnel', title: '隧道', modes: ['coop', 'realism', 'versus'] },
      { code: 'c12m3_bridge', title: '大桥', modes: ['coop', 'realism', 'versus'] },
      { code: 'c12m4_barn', title: '火车站', modes: ['coop', 'realism', 'versus'] },
      { code: 'c12m5_cornfield', title: '农舍', modes: ['coop', 'realism', 'versus'] }
    ]
  },
  {
    title: '刺骨寒溪',
    chapters: [
      { code: 'c13m1_alpinecreek', title: '高山小溪', modes: ['coop', 'realism', 'versus'] },
      { code: 'c13m2_southpinestream', title: '南松溪', modes: ['coop', 'realism', 'versus'] },
      { code: 'c13m3_memorialbridge', title: '纪念大桥', modes: ['coop', 'realism', 'versus'] },
      { code: 'c13m4_cutthroatcreek', title: '割喉溪', modes: ['coop', 'realism', 'versus'] }
    ]
  },
  {
    title: '背水一战',
    chapters: [
      { code: 'c14m1_junkyard', title: '垃圾场', modes: ['coop', 'realism', 'survival'] },
      { code: 'c14m2_lighthouse', title: '灯塔', modes: ['coop', 'realism', 'survival'] }
    ]
  }
]

/** 全部官方章节码集合（用于三方/官方判定） */
export const officialChapterCodes = new Set(
  officialChapters.flatMap(c => c.chapters.map(ch => ch.code))
)
