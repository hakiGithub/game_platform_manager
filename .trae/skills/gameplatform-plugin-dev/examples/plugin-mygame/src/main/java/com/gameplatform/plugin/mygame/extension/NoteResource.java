package com.gameplatform.plugin.mygame.extension;

import com.gameplatform.api.extension.AbstractExtension;
import com.gameplatform.plugin.extension.ExtensionModel;
import com.gameplatform.plugin.extension.Strategy;

/**
 * 笔记扩展资源（@ExtensionModel）。
 * <p>
 * 存储策略 {@link Strategy#MODEL_ISOLATED}：物理表 {@code ext_plugin_mygame_noteresource}，
 * 与其他模型物理隔离。框架自动填充 id（雪花ID）/ groupName（pluginId）/ kind / version（乐观锁）/ metadata。
 * <p>
 * name 为业务标识（NOT NULL UNIQUE），同类型内唯一；此处用 {@code {instanceId}-{uuid}} 保证唯一性。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtensionModel(strategy = Strategy.MODEL_ISOLATED)
public class NoteResource extends AbstractExtension<NoteSpec> {
    // 字段全部继承自 AbstractExtension：id / name / spec / version / status / metadata
    // 业务字段写在 NoteSpec 中
}
