package com.gameplatform.plugin.l4d2.config;

import com.gameplatform.plugin.l4d2.vo.BuiltinPluginVO;
import lombok.Data;

import java.util.List;

/**
 * builtin-plugins.yaml 根配置类。
 *
 * <p>结构：
 * <pre>
 * plugins:
 *   - id: "..."
 *     name: "..."
 *     category: platform | required | optional | custom
 *     fileName: "....zip"
 *     size: 12345
 *     platform: linux | windows | all
 *     description: "..."
 * </pre>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class BuiltinPluginsConfig {

    /** 内置插件列表 */
    private List<BuiltinPluginVO> plugins;
}
