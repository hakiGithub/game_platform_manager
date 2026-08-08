package com.gameplatform.plugin.l4d2.config;

import com.gameplatform.plugin.l4d2.vo.PresetDetailVO;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * preset.yaml 根配置类。
 *
 * <p>结构：
 * <pre>
 * platform:
 *   linux: "..."
 *   windows: "..."
 * presets:
 *   - id: ...
 *     name: ...
 *     plugins:
 *       - name: ...
 *         configs:
 *           - name: xxx.cfg
 *             values:
 *               key: "value"
 * </pre>
 *
 * @author GamePlatform
 * @version 2.0.0
 */
@Data
public class PresetConfig {

    /** 平台插件名映射（linux/windows） */
    private Map<String, String> platform = new LinkedHashMap<>();

    /** 预设列表 */
    private List<PresetDetailVO> presets;
}
