package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.config.L4D2Config;
import com.gameplatform.plugin.l4d2.util.VpkParser;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * VPK 文件解析服务
 * 用于解析 L4D2 的 VPK 文件，提取地图章节信息
 * 
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
public class VpkParserService {

    private final L4D2Config config;
    private final VpkParser vpkParser;
    
    // 缓存：战役列表
    private final Map<String, List<VpkParser.Campaign>> campaignCache = new ConcurrentHashMap<>();
    
    // 缓存时间戳
    private final Map<String, Long> cacheTimestamp = new ConcurrentHashMap<>();

    public VpkParserService(L4D2Config config) {
        this.config = config;
        this.vpkParser = new VpkParser();
    }

    /**
     * 获取战役列表
     *
     * @param addonsPath addons 目录路径
     * @return 战役列表
     */
    public List<VpkParser.Campaign> getCampaignList(String addonsPath) {
        // 检查缓存
        if (config.isVpkCacheEnabled()) {
            List<VpkParser.Campaign> cached = campaignCache.get(addonsPath);
            Long timestamp = cacheTimestamp.get(addonsPath);
            
            if (cached != null && timestamp != null) {
                long elapsed = (System.currentTimeMillis() - timestamp) / 1000;
                if (elapsed < config.getVpkCacheExpire()) {
                    log.debug("使用缓存的战役列表: {}", addonsPath);
                    return cached;
                }
            }
        }

        // 扫描 VPK 文件
        List<VpkParser.Campaign> campaigns = scanVpkFiles(addonsPath);
        
        // 更新缓存
        if (config.isVpkCacheEnabled()) {
            campaignCache.put(addonsPath, campaigns);
            cacheTimestamp.put(addonsPath, System.currentTimeMillis());
        }

        return campaigns;
    }

    /**
     * 扫描 VPK 文件
     *
     * @param addonsPath addons 目录路径
     * @return 战役列表
     */
    private List<VpkParser.Campaign> scanVpkFiles(String addonsPath) {
        List<VpkParser.Campaign> campaigns = new ArrayList<>();
        Path addonsDir = Paths.get(addonsPath);

        if (!Files.exists(addonsDir) || !Files.isDirectory(addonsDir)) {
            log.warn("addons 目录不存在或不是目录: {}", addonsPath);
            return campaigns;
        }

        try {
            Files.list(addonsDir)
                    .filter(path -> path.toString().toLowerCase().endsWith(".vpk"))
                    .forEach(vpkPath -> {
                        try {
                            VpkParser.Campaign campaign = parseVpkFile(vpkPath);
                            if (campaign != null) {
                                // 检查是否已存在相同标题的战役
                                boolean exists = campaigns.stream()
                                        .anyMatch(c -> c.getTitle().equals(campaign.getTitle()));
                                if (!exists) {
                                    campaigns.add(campaign);
                                }
                            }
                        } catch (Exception e) {
                            log.error("解析 VPK 文件失败: {}", vpkPath, e);
                        }
                    });
        } catch (IOException e) {
            log.error("扫描 addons 目录失败: {}", addonsPath, e);
        }

        return campaigns;
    }

    /**
     * 解析单个 VPK 文件
     *
     * @param vpkPath VPK 文件路径
     * @return 战役信息
     */
    private VpkParser.Campaign parseVpkFile(Path vpkPath) {
        log.debug("解析 VPK 文件: {}", vpkPath);

        VpkParser.VpkArchive archive = vpkParser.parse(vpkPath.toFile());
        if (archive == null) {
            return null;
        }

        // 获取任务文件
        List<VpkParser.VpkFileEntry> missionFiles = archive.getMissionFiles();
        if (missionFiles.isEmpty()) {
            log.debug("VPK 文件中没有找到任务文件: {}", vpkPath);
            return null;
        }

        // 解析任务文件
        VpkParser.Campaign mergedCampaign = null;
        for (VpkParser.VpkFileEntry missionFile : missionFiles) {
            try {
                VpkParser.Campaign campaign = parseMissionFileFromVpk(vpkPath.toFile(), missionFile);
                if (campaign != null) {
                    if (mergedCampaign == null) {
                        mergedCampaign = campaign;
                    } else {
                        // 合并战役信息
                        mergedCampaign = mergeCampaigns(mergedCampaign, campaign);
                    }
                }
            } catch (Exception e) {
                log.error("解析任务文件失败: {}", missionFile.getFullPath(), e);
            }
        }

        if (mergedCampaign != null) {
            mergedCampaign.setVpkName(vpkPath.getFileName().toString());
        }

        return mergedCampaign;
    }

    /**
     * 从 VPK 文件中解析任务文件
     * 注意：这是一个简化实现，实际需要完整实现 VPK 文件读取
     *
     * @param vpkFile     VPK 文件
     * @param missionFile 任务文件条目
     * @return 战役信息
     */
    private VpkParser.Campaign parseMissionFileFromVpk(File vpkFile, VpkParser.VpkFileEntry missionFile) {
        // 简化实现：直接读取预加载数据
        // 实际实现需要完整读取 VPK 文件内容
        byte[] preloadData = missionFile.getPreloadData();
        if (preloadData != null && preloadData.length > 0) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new ByteArrayInputStream(preloadData)))) {
                return VpkParser.parseMissionFile(reader);
            } catch (IOException e) {
                log.error("解析任务文件内容失败", e);
            }
        }

        // 如果没有预加载数据，尝试从文件中读取
        // 这里需要完整实现 VPK 文件读取逻辑
        return null;
    }

    /**
     * 合并两个战役信息
     *
     * @param base      基础战役
     * @param additional 额外战役
     * @return 合并后的战役
     */
    private VpkParser.Campaign mergeCampaigns(VpkParser.Campaign base, VpkParser.Campaign additional) {
        if (base == null) {
            return additional;
        }
        if (additional == null) {
            return base;
        }

        // 如果基础战役没有标题，使用额外战役的标题
        if (base.getTitle() == null || base.getTitle().isEmpty()) {
            base.setTitle(additional.getTitle());
        }

        // 创建章节映射表，用于快速查找和去重
        Map<String, VpkParser.Chapter> chapterMap = new HashMap<>();
        for (VpkParser.Chapter chapter : base.getChapters()) {
            chapterMap.put(chapter.getCode(), chapter);
        }

        // 合并额外战役的章节
        for (VpkParser.Chapter addChapter : additional.getChapters()) {
            VpkParser.Chapter existingChapter = chapterMap.get(addChapter.getCode());
            if (existingChapter != null) {
                // 章节已存在，合并游戏模式
                for (String mode : addChapter.getModes()) {
                    if (!existingChapter.getModes().contains(mode)) {
                        existingChapter.getModes().add(mode);
                    }
                }
                // 如果现有章节没有标题，使用新的标题
                if (existingChapter.getTitle() == null || existingChapter.getTitle().isEmpty()) {
                    existingChapter.setTitle(addChapter.getTitle());
                }
            } else {
                // 新章节，直接添加
                base.getChapters().add(addChapter);
                chapterMap.put(addChapter.getCode(), addChapter);
            }
        }

        return base;
    }

    /**
     * 清除缓存
     */
    public void clearCache() {
        campaignCache.clear();
        cacheTimestamp.clear();
        log.info("VPK 缓存已清除");
    }

    /**
     * 清除指定路径的缓存
     *
     * @param addonsPath addons 目录路径
     */
    public void clearCache(String addonsPath) {
        campaignCache.remove(addonsPath);
        cacheTimestamp.remove(addonsPath);
        log.info("VPK 缓存已清除: {}", addonsPath);
    }

}
