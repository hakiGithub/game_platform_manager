package com.gameplatform.clouddrive;

import com.gameplatform.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 插件工具脚本资源注册表。
 *
 * <p>宿主在插件加载阶段从插件 ClassLoader 读取脚本并缓存，运行时只接受
 * {@code pluginId:resource} 标识。脚本所有权在插件，而实例寻址、主机命令、
 * 脚本分发和临时文件清理仍由宿主控制。
 */
@Component
public class ToolScriptRegistry {

    private final Map<String, String> scripts = new ConcurrentHashMap<>();

    public void register(String scriptId, ClassLoader pluginClassLoader, String resourcePath) {
        try (var input = pluginClassLoader.getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new BusinessException("插件缺少工具脚本资源: " + resourcePath);
            }
            scripts.put(scriptId, new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("读取插件工具脚本失败: " + resourcePath
                    + " - " + e.getMessage(), e);
        }
    }

    public String read(String scriptId) {
        String script = scripts.get(scriptId);
        if (script == null) {
            throw new BusinessException("工具脚本未注册: " + scriptId);
        }
        return script;
    }

    public boolean contains(String scriptId) {
        return scripts.containsKey(scriptId);
    }

    public void unregisterPlugin(String pluginId) {
        String prefix = pluginId + ":";
        scripts.keySet().removeIf(key -> key.startsWith(prefix));
    }
}
