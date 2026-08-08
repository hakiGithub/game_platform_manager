package com.gameplatform.plugin.l4d2.crawler;

import com.gameplatform.plugin.l4d2.crawler.dto.MapDetail;
import com.gameplatform.plugin.l4d2.crawler.dto.MapListItem;

import java.util.List;

/**
 * 地图爬虫接口。
 *
 * <p>抽象不同来源站点（如 orangetage、创意工坊）的列表抓取能力，
 * 详情页解析由 {@link #crawlDetail(String)} 单独提供。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public interface MapCrawler {

    /** 获取来源标识 */
    String getSource();

    /** 获取列表页所有地图摘要（全量） */
    List<MapListItem> crawlAllListItems();

    /** 获取指定页的地图摘要 */
    List<MapListItem> crawlListPage(int page);

    /** 获取总页数 */
    int getTotalPages();

    /**
     * 获取指定详情页的地图详细信息。
     *
     * @param detailUrl 详情页完整 URL
     * @return 解析后的详情，失败返回 null
     */
    MapDetail crawlDetail(String detailUrl);
}
