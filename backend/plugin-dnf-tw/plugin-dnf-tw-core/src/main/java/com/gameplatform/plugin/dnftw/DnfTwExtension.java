package com.gameplatform.plugin.dnftw;

import com.gameplatform.plugin.extension.GameEnhancementExtension;
import com.gameplatform.plugin.extension.deploy.DeployVersionDeclaration;
import org.pf4j.Extension;

import java.util.List;

/**
 * dnf-tw 的部署扩展声明（design.md §3.3 组 J、§7.3 B-14/B-15；PRD FR-22 / FR-23）。
 *
 * <p><b>目录走 {@code getDeployVersions}，不走 {@code getDeployConfigs()}</b>：后者的读时合并
 * 只作用于 VO 读取路径，部署执行路径读的是 {@code game_metadata} 表快照（design.md §16.1 / F-19），
 * 挂进那里会得到一个「向导看得见、部署看不见」的目录。</p>
 *
 * <p><b>本期状态：占位模板，不可上线</b>（OP-02 裁决、PRD §8.6）。目录内容来自 JAR 内的
 * {@code version-catalog.yml}，其中每个待填字段都带未填充标记，读取时被过滤 ⇒
 * 对外声明的条目数恒为 0 ⇒ 主应用侧 {@code CatalogView.state == EMPTY}（不是 {@code INVALID}、
 * 不抛异常），dnf-tw 恒处 PRD §8.4.2 的 S1：向导不出现「目标版本」选择项、载荷不写
 * {@code deployVersion}、不进扩展阶段，部署与改造前逐项一致（AC-24）。</p>
 *
 * <p><b>「填充即生效」为什么成立</b>（FR-23 / BR-15 ④）：本类到 SDK 声明类型之间只有一条读取路径
 * ——{@link VersionCatalogTemplate} 解析模板 → 按未填充标记逐条过滤 → 逐字段搬运成
 * {@link DeployVersionDeclaration}。三个环节都不看「模板填了没填」、不按模板内容分支：
 * 没有「未填充就返回空列表」的特判分支，空列表是过滤的自然结果。真实资料到位后
 * <b>只替换 {@code version-catalog.yml} 的内容</b>（填值 + 删掉不需要的可选行）即让 S3 可达，
 * 不需要改本类、不需要改主应用、不需要动 SDK 声明接口。</p>
 *
 * <p>{@link #getDeployVersions(String)} 对<b>任意</b> deployType 返回同一份目录：
 * 「集合外 deployType 带步骤或 imageTag 即不合法」是主应用的校验规则 N5（design.md §16.3），
 * 插件侧替它过滤会把这条规则的判定对象抹掉，也会让本方法按入参分支。</p>
 *
 * <p>{@code getDeployExtensionSteps(ctx)}（§16.2 解析顺序 ① 支）保持 SDK 默认实现（空集）：
 * dnf-tw 属纯声明型插件，步骤集由目录条目自带的 {@code patches ++ scripts} 承载（② 支）。</p>
 */
@Extension
public class DnfTwExtension implements GameEnhancementExtension {

    /** 归属键，与 {@code core/src/main/resources/games/dnf_tw.yml} 的 {@code game.code} 一致。 */
    public static final String GAME_CODE = "dnf_tw";

    /** 与 {@code plugin.properties} / {@code plugin.version} 一致的插件版本。 */
    static final String PLUGIN_VERSION = "1.0.0";

    /** OP-02 裁决要求对外可见的状态标注（PRD §8.6、AC-24 ⑤），插件清单界面读到的就是这句。 */
    static final String PLACEHOLDER_NOTICE = "占位模板，不可上线";

    @Override
    public String getGameCode() {
        return GAME_CODE;
    }

    @Override
    public String getGameName() {
        return "DNF台服";
    }

    @Override
    public String getVersion() {
        return PLUGIN_VERSION;
    }

    @Override
    public String getDescription() {
        return "DNF台服 部署扩展声明插件（" + PLACEHOLDER_NOTICE + "）";
    }

    @Override
    public List<DeployVersionDeclaration> getDeployVersions(String deployType) {
        return VersionCatalogTemplate.read();
    }
}
