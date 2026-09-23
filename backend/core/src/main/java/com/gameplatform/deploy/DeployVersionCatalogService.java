package com.gameplatform.deploy;

import com.gameplatform.entity.GameMetadata;
import com.gameplatform.mapper.GameMetadataMapper;
import com.gameplatform.plugin.extension.GameEnhancementExtension;
import com.gameplatform.plugin.extension.deploy.DeployExtensionStepDeclaration;
import com.gameplatform.plugin.extension.deploy.DeployVersionDeclaration;
import com.gameplatform.plugin.extension.deploy.PatchStepDeclaration;
import com.gameplatform.plugin.extension.deploy.ScriptPosition;
import com.gameplatform.plugin.extension.deploy.ScriptStepDeclaration;
import com.gameplatform.plugin.service.PluginFrameworkService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 版本目录的唯一读者（design.md §16.3、B-05）。
 *
 * <p>向导、部署提交、扩展阶段三处都读目录；各读各的会得到「向导以为可用 / 部署认为不合法」
 * 的分叉（RISK-13），因此收敛到本组件。
 *
 * <p><b>两条通道不对称</b>（§16.1）：插件经 {@code getDeployConfigs()} 整节替换出的
 * {@code variables} / {@code composeTemplate} 只作用于 VO 读取路径，部署执行路径读的是
 * {@code game_metadata} 表快照。N1/N2 的判定因此<b>只认表快照</b>，且取值路径与
 * {@code InstanceServiceImpl#buildDeployConfig} 一致（§14.4.1 R1）——否则「校验通过而部署
 * 无效」在本期可达。
 *
 * <p><b>任一不合规即整目录 {@code INVALID}</b>（PRD §8.1「禁止部分采纳」）：不跳过该条继续，
 * 也不静默改用默认条目交付。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeployVersionCatalogService {

    /** 版本选择的唯一载体键（PRD §8.4）。 */
    public static final String VERSION_KEY = "deployVersion";

    /** 声明期保留变量键：值一律由平台在部署配置组装期写入（design.md §14.4.2）。 */
    public static final String PLATFORM_IMAGE_TAG_KEY = "PLATFORM_IMAGE_TAG";

    /** 本期允许声明扩展步骤 / imageTag 的部署方式集合（design.md §14.13.1）。 */
    public static final Set<String> STEP_SUPPORTED_DEPLOY_TYPES = Set.of("docker-compose", "linuxgsm-docker");

    /** PRD §8.1：versionId 字符集。 */
    private static final Pattern VERSION_ID = Pattern.compile("[A-Za-z0-9._-]+");

    /** design.md §14.4.1 R4：compose tag 合法字符集，长度 ≤ 128。 */
    private static final Pattern IMAGE_TAG = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._@/-]{0,127}");

    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");

    /** design.md §15.2 拍板的脚本超时区间。 */
    private static final long TIMEOUT_MIN_MS = 1_000L;
    private static final long TIMEOUT_MAX_MS = 1_800_000L;

    private static final String TEMPLATE_TAG_PLACEHOLDER = "${" + PLATFORM_IMAGE_TAG_KEY;

    private final GameMetadataMapper gameMetadataMapper;
    private final PluginFrameworkService pluginFrameworkService;

    /**
     * 读取并校验某游戏某部署方式的版本目录。
     *
     * @param gameCode   归属键（ADR-0008 体系）
     * @param deployType 部署方式编码，N5 的判定对象
     * @return 四态之一的读取结果，恒不为 {@code null}
     */
    public CatalogView read(String gameCode, String deployType) {
        if (gameCode == null || gameCode.isBlank() || deployType == null || deployType.isBlank()) {
            return CatalogView.absent();
        }
        GameEnhancementExtension ext = pluginFrameworkService.getExtensionByGameCode(gameCode);
        if (ext == null) {
            return CatalogView.absent();
        }
        List<DeployVersionDeclaration> declared;
        try {
            declared = ext.getDeployVersions(deployType);
        } catch (Exception e) {
            // SPI 抛异常归 ABSENT：不外泄到向导（AC-20 的构造手段之一）
            log.warn("插件 [{}] 读取版本目录异常，按「无目录」处置: deployType={}, cause={}",
                    gameCode, deployType, e.getMessage());
            return CatalogView.absent();
        }
        if (declared == null || declared.isEmpty()) {
            return CatalogView.empty();
        }

        Map<String, Object> tableConfig = tableTypeConfig(gameCode, deployType);
        List<String> failures = new ArrayList<>();
        collectDeployTypeSetFailures(declared, deployType, failures);
        collectBaseFailures(declared, failures);
        collectReservedKeyFailures(declared, tableConfig, failures);
        if (!failures.isEmpty()) {
            String reason = String.join("；", failures);
            log.warn("游戏 [{}] deployType={} 的版本目录声明不合法，整目录判不可用: {}",
                    gameCode, deployType, reason);
            return CatalogView.invalid(reason);
        }
        return CatalogView.available(declared.stream().map(this::toEntry).toList());
    }

    /**
     * 表侧该 deployType 声明的 {@code variables[].name}，BR-07 撞键清单的数据源。
     *
     * <p>与 N1/N2 同一条取值通道（表快照），因此提交期判定的「会不会互相覆盖」与实际部署
     * 组装出的那份 config 一致。
     */
    public List<String> declaredVariableNames(String gameCode, String deployType) {
        return variableNames(tableTypeConfig(gameCode, deployType));
    }

    /**
     * {@code PLATFORM_IMAGE_TAG} 的表侧声明态 —— design.md §14.4 两级门控的数据源。
     *
     * <p>判定通道同样只认表快照：声明与否、以及缺省值，都必须和 {@code buildDeployConfig}
     * 实际拿去渲染 {@code .env} 的那份 {@code variables[]} 是同一份。
     */
    public PlatformImageTagDeclaration platformImageTag(String gameCode, String deployType) {
        Map<String, Object> variable = findVariable(tableTypeConfig(gameCode, deployType), PLATFORM_IMAGE_TAG_KEY);
        if (variable == null) {
            return PlatformImageTagDeclaration.notDeclared();
        }
        Object defaultValue = variable.get("defaultValue");
        return new PlatformImageTagDeclaration(true,
                defaultValue == null || String.valueOf(defaultValue).isBlank()
                        ? null : String.valueOf(defaultValue));
    }

    /**
     * @param declared     该 deployType 是否声明了保留变量；未声明 ⇒ 平台完全不写该键
     * @param defaultValue 声明了时平台为该键写的缺省值（命中条目不带 imageTag 即用此值）
     */
    public record PlatformImageTagDeclaration(boolean declared, String defaultValue) {

        private static final PlatformImageTagDeclaration NOT_DECLARED = new PlatformImageTagDeclaration(false, null);

        static PlatformImageTagDeclaration notDeclared() {
            return NOT_DECLARED;
        }
    }

    /**
     * 该 deployType 的 {@code game_metadata} 表快照（内置 {@code games/*.yml} + 外置
     * {@code ./games} 扫描落库的结果），读不到时返回空 map。
     */
    private Map<String, Object> tableTypeConfig(String gameCode, String deployType) {
        GameMetadata game = gameMetadataMapper.selectByGameCode(gameCode);
        if (game == null || game.getDeployConfig() == null) {
            return Map.of();
        }
        Object typeConfig = game.getDeployConfig().get(deployType);
        return typeConfig instanceof Map ? asMap(typeConfig) : Map.of();
    }

    /** N5（design.md §14.13.1）：集合外 deployType 带任何步骤或 imageTag 即整目录不合法。 */
    private void collectDeployTypeSetFailures(List<DeployVersionDeclaration> declared,
                                              String deployType, List<String> failures) {
        if (STEP_SUPPORTED_DEPLOY_TYPES.contains(deployType)) {
            return;
        }
        for (int i = 0; i < declared.size(); i++) {
            DeployVersionDeclaration d = declared.get(i);
            if (notEmpty(d.patches()) || notEmpty(d.scripts()) || notBlank(d.imageTag())) {
                failures.add("条目「" + describe(d) + "」（第 " + (i + 1) + " 条）在部署方式「" + deployType
                        + "」下不得声明 patches/scripts/imageTag");
            }
        }
    }

    /** PRD §8.1 基础项 + §8.2 / §8.3 的条目内必填与互斥。 */
    private void collectBaseFailures(List<DeployVersionDeclaration> declared, List<String> failures) {
        Set<String> seen = new LinkedHashSet<>();
        int defaultCount = 0;
        for (int i = 0; i < declared.size(); i++) {
            DeployVersionDeclaration d = declared.get(i);
            String at = "条目「" + describe(d) + "」（第 " + (i + 1) + " 条）";
            if (!notBlank(d.versionId())) {
                failures.add(at + " 的 versionId 不得为空");
            } else {
                if (!VERSION_ID.matcher(d.versionId()).matches()) {
                    failures.add(at + " 的 versionId 含 [A-Za-z0-9._-] 之外的字符");
                }
                if (!seen.add(d.versionId())) {
                    failures.add(at + " 的 versionId 与目录内其它条目重复");
                }
            }
            if (Boolean.TRUE.equals(d.defaultEntry())) {
                defaultCount++;
            }
            collectPatchFailures(d, at, failures);
            collectScriptFailures(d, at, failures);
        }
        if (defaultCount > 1) {
            failures.add("目录内标记为默认的条目有 " + defaultCount + " 条，至多一条");
        }
    }

    private void collectPatchFailures(DeployVersionDeclaration d, String at, List<String> failures) {
        if (d.patches() == null) {
            return;
        }
        for (PatchStepDeclaration p : d.patches()) {
            String step = at + " 的 PATCH 步骤「" + (notBlank(p.label()) ? p.label() : "(无标签)") + "」";
            requireHttpUrl(step + " 的 url", p.url(), failures);
            if (!notBlank(p.targetPath())) {
                failures.add(step + " 的 targetPath 不得为空");
            } else {
                requireInstanceRelativePath(step, p.targetPath(), failures);
            }
            requireOptionalSha256(step + " 的 sha256", p.sha256(), failures);
        }
    }

    private void collectScriptFailures(DeployVersionDeclaration d, String at, List<String> failures) {
        if (d.scripts() == null) {
            return;
        }
        for (ScriptStepDeclaration s : d.scripts()) {
            String step = at + " 的 SCRIPT 步骤「" + (notBlank(s.label()) ? s.label() : "(无标签)") + "」";
            if (!notBlank(s.label())) {
                failures.add(step + " 的 label 不得为空");
            }
            boolean hasContent = notBlank(s.content());
            boolean hasUrl = notBlank(s.url());
            if (hasContent == hasUrl) {
                failures.add(step + " 的 content 与 url 必须恰有一个");
            }
            if (hasUrl) {
                requireHttpUrl(step + " 的 url", s.url(), failures);
            }
            requireOptionalSha256(step + " 的 sha256", s.sha256(), failures);
            if (s.position() == ScriptPosition.CONTAINER) {
                failures.add(step + " 声明 position=CONTAINER，本期只支持 HOST（design.md §14.5）");
            }
            Long timeout = s.timeoutMs();
            if (timeout != null && (timeout < TIMEOUT_MIN_MS || timeout > TIMEOUT_MAX_MS)) {
                failures.add(step + " 的 timeoutMs=" + timeout + " 越出 [" + TIMEOUT_MIN_MS + ", "
                        + TIMEOUT_MAX_MS + "]（design.md §15.2）");
            }
        }
    }

    /**
     * design.md §16.3 的 N1…N3：判定通道 = 表快照（§14.4.1 R1）。
     *
     * <p>N1/N2 由「有条目声明 imageTag」触发；N3 的两个取值各自按存在性校验——
     * 保留变量一旦被声明，它的 defaultValue 就会进 {@code .env}，与条目是否带 imageTag 无关。
     */
    private void collectReservedKeyFailures(List<DeployVersionDeclaration> declared,
                                            Map<String, Object> tableConfig, List<String> failures) {
        List<String> tags = declared.stream()
                .filter(d -> notBlank(d.imageTag()))
                .map(DeployVersionDeclaration::imageTag)
                .toList();
        Map<String, Object> reservedVariable = findVariable(tableConfig, PLATFORM_IMAGE_TAG_KEY);

        if (!tags.isEmpty()) {
            if (reservedVariable == null) {
                failures.add("目录声明了 imageTag，但表侧该部署方式未声明保留变量 " + PLATFORM_IMAGE_TAG_KEY
                        + "，该 tag 必然静默无效（N1）");
            }
            Object template = tableConfig.get("composeTemplate");
            if (!(template instanceof String text) || !text.contains(TEMPLATE_TAG_PLACEHOLDER)) {
                failures.add("目录声明了 imageTag，但表侧 composeTemplate 不含 " + TEMPLATE_TAG_PLACEHOLDER
                        + " 占位符字面量，注入后无消费者（N2）");
            }
        }
        tags.forEach(tag -> requireImageTag("条目声明的 imageTag=" + tag, tag, failures));
        if (reservedVariable != null) {
            Object defaultValue = reservedVariable.get("defaultValue");
            requireImageTag("保留变量 " + PLATFORM_IMAGE_TAG_KEY + " 的 defaultValue",
                    defaultValue instanceof String text ? text : null, failures);
        }
    }

    private void requireImageTag(String what, String value, List<String> failures) {
        if (!notBlank(value)) {
            failures.add(what + " 不得为空（N3）");
        } else if (!IMAGE_TAG.matcher(value).matches()) {
            failures.add(what + "=" + value + " 不是合法的镜像 tag（N3）");
        }
    }

    private void requireHttpUrl(String what, String value, List<String> failures) {
        if (!notBlank(value)) {
            failures.add(what + " 不得为空");
            return;
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            failures.add(what + "=" + value + " 不是合法 URL");
            return;
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            failures.add(what + "=" + value + " 不是合法 http(s) URL");
        }
    }

    private void requireOptionalSha256(String what, String value, List<String> failures) {
        if (notBlank(value) && !SHA256.matcher(value).matches()) {
            failures.add(what + " 不是 64 位十六进制摘要");
        }
    }

    /** BR-05：实例相对路径，越界即声明不合法。 */
    private void requireInstanceRelativePath(String step, String path, List<String> failures) {
        String normalized = path.replace('\\', '/');
        boolean absolute = normalized.startsWith("/") || normalized.contains(":");
        boolean escapes = Stream.of(normalized.split("/")).anyMatch(".."::equals);
        if (absolute || escapes) {
            failures.add(step + " 的 targetPath=" + path + " 不是实例目录内的相对路径（BR-05）");
        }
    }

    private VersionEntry toEntry(DeployVersionDeclaration d) {
        List<VersionEntry.StepSummary> summary = new ArrayList<>();
        int index = 1;
        for (DeployExtensionStepDeclaration step : stepsOf(d)) {
            summary.add(new VersionEntry.StepSummary(index++, step.label(), step.kind(), step.fatal()));
        }
        return new VersionEntry(d, notBlank(d.displayName()) ? d.displayName() : d.versionId(),
                Boolean.TRUE.equals(d.defaultEntry()), summary);
    }

    /**
     * 条目自带的有序混合步骤集（§16.2 解析顺序 ② 支）。
     *
     * <p>拼接处的显式类型见证不可省：{@code concat} 的两个 {@code ? extends T} 独立推导后取交
     * 得到 {@code T = INT#1}，泛型不变 ⇒ 不带见证的写法在 javac 17 下编译不过（§16.2 推导规则）。
     */
    static List<DeployExtensionStepDeclaration> stepsOf(DeployVersionDeclaration d) {
        List<PatchStepDeclaration> patches = d.patches() == null ? List.of() : d.patches();
        List<ScriptStepDeclaration> scripts = d.scripts() == null ? List.of() : d.scripts();
        return Stream.<DeployExtensionStepDeclaration>concat(patches.stream(), scripts.stream()).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static List<String> variableNames(Map<String, Object> tableConfig) {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> variable : variablesOf(tableConfig)) {
            Object name = variable.get("name");
            if (name instanceof String text && notBlank(text)) {
                names.add(text);
            }
        }
        return names;
    }

    private static Map<String, Object> findVariable(Map<String, Object> tableConfig, String name) {
        return variablesOf(tableConfig).stream()
                .filter(v -> name.equals(v.get("name")))
                .findFirst()
                .orElse(null);
    }

    private static List<Map<String, Object>> variablesOf(Map<String, Object> tableConfig) {
        if (tableConfig.get("variables") instanceof List<?> list) {
            return list.stream().filter(Map.class::isInstance).map(DeployVersionCatalogService::asMap).toList();
        }
        return List.of();
    }

    private static boolean notEmpty(List<?> list) {
        return list != null && !list.isEmpty();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String describe(DeployVersionDeclaration d) {
        return notBlank(d.versionId()) ? d.versionId() : "(无 versionId)";
    }
}
