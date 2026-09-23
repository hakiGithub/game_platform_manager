package com.gameplatform.deploy;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.http.HttpUtil;
import com.gameplatform.plugin.extension.deploy.ScriptStepDeclaration;
import com.gameplatform.plugin.service.FileAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 部署扩展阶段 SCRIPT 步骤的执行面（design.md §7.2 B-13、§8.3、§8.4、§15.2）。
 *
 * <p>形状固定为「平台侧物化脚本 → {@code sha256} 校验（声明了才校验）→ SFTP 上传到
 * {@code <workDir>/.platform-extension/E-<n>.sh} → {@code bash <file>} 执行 → {@code finally} 删除」。
 * 脚本正文<b>永不</b>拼进命令行（RISK-08）：命令行里只有脚本文件名，审计可读且无引号转义面。
 *
 * <p><b>本类只提供能力，不产部署日志行</b>：步骤行的归组与词面属 §14.6 的呈现契约（B-09 / B-11），
 * 因此结果对象只携带机械字段（{@code exitCode} / {@code timedOut} / 截断后的输出与字节数），
 * 不携带任何用户可见文案。OP-05 未关闭，本类因此不自造词面。（下面的 slf4j 输出是平台运维日志，
 * 不是给用户读的部署日志流。）
 *
 * <p><b>诚实限制（§15.3）</b>：{@code timeoutMs} 到点后平台只是<b>不再等待</b>，
 * 不保证宿主机上的脚本进程已被终止——远端 {@code timeout} 发的是 SIGTERM，
 * 脚本可自行 trap，其子进程也可能存活。不得对运维宣称「已终止」。
 *
 * <p><b>与 §14.13.3 字面的一处偏离（已在回传里登记）</b>：发出的命令是
 * {@code timeout <秒> bash <file>} 而不是裸 {@code bash <file>}。原因是代码事实
 * §15.2「实现落点」那一行不成立——{@code SshUtil.executeCommand} 的 {@code timeoutMs}
 * 只作用于建连与认证（{@code SshUtil:356} → {@code getOrCreateSession}），
 * 命令执行走 {@code executeRemoteCommand} 无超时（{@code SshUtil:368}）。裸 {@code bash <file>}
 * 会让 §15.2 说的「步骤级 timeoutMs 是唯一的上限护栏」形同虚设，挂死的脚本能把部署永久停在
 * {@code INSTALLING(5)}。仓内已有同形先例：{@code DockerComposeAdapter:260} 与
 * {@code LinuxGsmDockerAdapter:945} 都用 GNU {@code timeout} 兜这同一段。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExtensionScriptRunner {

    /** §15.2 拍板的缺省超时（10 分钟）；合法区间 {@code [1000, 1800000]} 的判定属声明期 N4，不在此处。 */
    public static final long DEFAULT_TIMEOUT_MS = 600_000L;

    /** §8.3 的硬约束：单步 stdout / stderr 各截断至 4000 字符 = 头 2000 + 尾 2000。 */
    public static final int OUTPUT_HEAD_CHARS = 2_000;
    public static final int OUTPUT_TAIL_CHARS = 2_000;

    /** 临时脚本目录名（§8.4：{@code workDir} 下只放临时脚本）。 */
    public static final String SCRIPT_DIR_NAME = ".platform-extension";

    /** GNU {@code timeout} 判定为超时的退出码。 */
    private static final int SHELL_TIMEOUT_EXIT_CODE = 124;

    /** 平台侧下载预算，与补丁链路同量级（{@code PatchInstallExecutor#platformDownload}）。 */
    private static final int DOWNLOAD_TIMEOUT_MS = 600_000;

    private final FileAccessService fileAccessService;

    /**
     * 执行一次 {@code position = host} 的脚本步骤。
     *
     * @param step      脚本步骤声明（{@code content} / {@code url} 恰有一个，由声明期 N3 保证）
     * @param stepIndex 步骤序号，决定临时文件名 {@code E-<n>.sh} 与 §14.6 的 {@code stepId}
     * @param hostId    目标宿主机
     * @param workDir   宿主机上的实例工作目录（两类适配器在 {@code DEPLOY} 末已回写进 {@code runtimeMetadata.workDir}）
     * @return 执行结果，字段可直接交给他处的 {@code exitCode} / 耗时 / 输出承载位
     * @throws ScriptPreconditionException 脚本没能开始执行（URL 不合法 / 下载失败 / 摘要不符）——
     *                                     此时宿主机上不会留下任何文件
     */
    public ScriptRunResult run(ScriptStepDeclaration step, int stepIndex, Long hostId, String workDir) {
        long timeoutMs = step.timeoutMs() == null ? DEFAULT_TIMEOUT_MS : step.timeoutMs();
        long shellSeconds = shellTimeoutSeconds(timeoutMs);
        String scriptDir = workDir + "/" + SCRIPT_DIR_NAME;
        String scriptFile = scriptDir + "/E-" + stepIndex + ".sh";
        Path localScript = null;
        // 「可能已在宿主机落文件」的边界要划在上传**之前**：传输中断同样会留下半个 E-<n>.sh，
        // 若在之后才置真，这半个文件就没人删了（V-13 的 finally 删除）。
        boolean mayExistOnHost = false;
        try {
            localScript = materializeLocalScript(step);
            fileAccessService.executeCommand(hostId, "mkdir -p " + shellQuote(scriptDir));
            mayExistOnHost = true;
            fileAccessService.uploadLocalFile(hostId, scriptFile, localScript.toString());

            // 命令标识 + 脚本文件名，不含正文（§8.3「命令文本」行）
            log.info("扩展脚本步骤 {} 开始: hostId={}, scriptFile={}, timeoutMs={}", stepIndex, hostId, scriptFile, timeoutMs);
            String command = "timeout " + shellSeconds + " bash " + shellQuote(scriptFile);
            long startedAt = System.currentTimeMillis();
            FileAccessService.CommandResult result = fileAccessService.executeCommand(hostId, command, timeoutMs);
            long elapsedMs = System.currentTimeMillis() - startedAt;

            // 判超时必须对齐「远端实际被杀的时刻」而不是声明的毫秒数：shell timeout 只有秒的粒度，
            // 用 timeoutMs 本身比会把一次真超时误判成普通的 exitCode == 124（违反 §14.6 规则 4）
            boolean timedOut = result.getExitCode() == SHELL_TIMEOUT_EXIT_CODE && elapsedMs >= shellSeconds * 1000L;
            Integer exitCode = timedOut ? null : result.getExitCode();
            log.info("扩展脚本步骤 {} 结束: scriptFile={}, exitCode={}, timedOut={}, elapsedMs={}",
                    stepIndex, scriptFile, exitCode, timedOut, elapsedMs);
            return new ScriptRunResult(exitCode, timedOut, timeoutMs, elapsedMs,
                    truncate(result.getOutput()), truncate(result.getError()));
        } finally {
            if (mayExistOnHost) {
                deleteQuietly(hostId, scriptFile, scriptDir);
            }
            deleteLocalQuietly(localScript);
        }
    }

    // ==================== 脚本物化 ====================

    /** 正文脚本落临时文件；URL 脚本先平台侧下载并校验——校验不过就不会走到上传，宿主机上不留文件。 */
    private Path materializeLocalScript(ScriptStepDeclaration step) {
        try {
            Path local = Files.createTempFile("platform-extension-", ".sh");
            if (step.content() != null && !step.content().isEmpty()) {
                Files.writeString(local, step.content(), StandardCharsets.UTF_8);
                return local;
            }
            String url = step.url();
            if (url == null || url.isBlank()) {
                throw new ScriptPreconditionException(ScriptPrecondition.SOURCE_ABSENT, null, null, null);
            }
            requireHttpScheme(url);
            long size;
            try {
                size = HttpUtil.downloadFile(url, local.toFile(), DOWNLOAD_TIMEOUT_MS);
            } catch (RuntimeException e) {
                throw new ScriptPreconditionException(ScriptPrecondition.DOWNLOAD_FAILED, null, null, e.getMessage());
            }
            if (size <= 0) {
                throw new ScriptPreconditionException(ScriptPrecondition.DOWNLOAD_FAILED, null, null, "empty response");
            }
            if (step.sha256() != null && !step.sha256().isBlank()) {
                String actual = DigestUtil.sha256Hex(local.toFile());
                if (!step.sha256().equalsIgnoreCase(actual)) {
                    throw new ScriptPreconditionException(ScriptPrecondition.CHECKSUM_MISMATCH,
                            step.sha256(), actual, null);
                }
            }
            return local;
        } catch (ScriptPreconditionException e) {
            throw e;
        } catch (IOException e) {
            throw new ScriptPreconditionException(ScriptPrecondition.DOWNLOAD_FAILED, null, null, e.getMessage());
        }
    }

    /** §8.4：脚本源只接受 http(s)。其余协议（file / ftp / 裸路径）一律拒。 */
    private static void requireHttpScheme(String url) {
        String scheme;
        try {
            scheme = URI.create(url).getScheme();
        } catch (IllegalArgumentException e) {
            throw new ScriptPreconditionException(ScriptPrecondition.URL_SCHEME_UNSUPPORTED, null, null, e.getMessage());
        }
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new ScriptPreconditionException(ScriptPrecondition.URL_SCHEME_UNSUPPORTED, null, null, scheme);
        }
    }

    /**
     * 远端 {@code timeout} 只有秒的粒度，这里<b>向上</b>取整（下界 1 s，防「timeout 0 = 不限时」）：
     * 向下取整会在声明预算还没用满前就把脚本杀掉（1400 ms → 1 s），那是「主应用覆盖声明」的一种，
     * BR-04 禁止。代价是预算最多被放宽不到 1 s，而 {@code timeoutMs} 的下界本就是 1 s（§15.2）。
     */
    private static long shellTimeoutSeconds(long timeoutMs) {
        return Math.max(1L, (timeoutMs + 999L) / 1000L);
    }

    // ==================== 输出截断（§8.3） ====================

    private static TruncatedOutput truncate(String output) {
        String text = output == null ? "" : output;
        long originalBytes = text.getBytes(StandardCharsets.UTF_8).length;
        int keep = OUTPUT_HEAD_CHARS + OUTPUT_TAIL_CHARS;
        if (text.length() <= keep) {
            return new TruncatedOutput(text, originalBytes, false);
        }
        return new TruncatedOutput(text.substring(0, OUTPUT_HEAD_CHARS)
                + text.substring(text.length() - OUTPUT_TAIL_CHARS), originalBytes, true);
    }

    // ==================== 清理 ====================

    /**
     * 删临时脚本，并把只放临时脚本的 {@code .platform-extension/} 目录一起收掉（§8.4）。
     * 用 {@code rmdir} 而非 {@code rm -rf}：脚本自己往这里写过东西就不该被平台顺手清空，
     * 目录非空时 rmdir 自然失败，忽略即可。
     */
    private void deleteQuietly(Long hostId, String remotePath, String remoteDir) {
        try {
            fileAccessService.deleteFile(hostId, remotePath);
        } catch (Exception e) {
            log.warn("扩展脚本临时文件删除失败（不影响步骤判定）: {}, {}", remotePath, e.getMessage());
        }
        try {
            fileAccessService.executeCommand(hostId, "rmdir " + shellQuote(remoteDir));
        } catch (Exception e) {
            log.debug("扩展脚本临时目录未清空（非空或已不存在）: {}", remoteDir);
        }
    }

    private void deleteLocalQuietly(Path local) {
        if (local == null) {
            return;
        }
        try {
            Files.deleteIfExists(local);
        } catch (IOException e) {
            log.warn("本地脚本临时文件删除失败: {}, {}", local, e.getMessage());
        }
    }

    private static String shellQuote(String value) {
        if (value == null || value.isEmpty()) {
            return "''";
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }

    // ==================== 出参 ====================

    /**
     * 一次脚本执行的结果。
     *
     * @param exitCode  退出码；{@code null} 表示超时（§14.6 规则 4 靠这一点把「超时」与「非零退出」区分开）
     * @param timedOut  是否按超时处置
     * @param timeoutMs 本步实际生效的超时（缺省已解析），供原因段填 {@code 〈timeoutMs〉} 槽位
     * @param elapsedMs 从发出命令到不再等待的实际耗时
     * @param stdout    截断后的标准输出
     * @param stderr    截断后的标准错误
     */
    public record ScriptRunResult(Integer exitCode, boolean timedOut, long timeoutMs, long elapsedMs,
                                  TruncatedOutput stdout, TruncatedOutput stderr) {

        /** §14.6 规则 4：{@code exitCode == 0} 即成功；超时与非零退出都判失败，但原因不同。 */
        public boolean succeeded() {
            return exitCode != null && exitCode == 0;
        }
    }

    /**
     * 截断后的一段输出。
     *
     * @param text          头 {@value ExtensionScriptRunner#OUTPUT_HEAD_CHARS} + 尾 {@value ExtensionScriptRunner#OUTPUT_TAIL_CHARS} 拼接后的文本
     * @param originalBytes 截断前的完整字节数，供 NOTE 行的 {@code N} 槽位
     * @param truncated     是否发生了截断
     */
    public record TruncatedOutput(String text, long originalBytes, boolean truncated) {

        /** §8.3 登记的截断说明词面；未截断时返回 {@code null}，由调用方决定是否产 {@code NOTE} 行。 */
        public String truncationNote() {
            return truncated ? "输出已截断，共 " + originalBytes + " 字节" : null;
        }
    }

    /** 脚本没能开始执行的原因分类（不携带文案：词面归 §14.6 的呈现契约）。 */
    public enum ScriptPrecondition {
        /** {@code content} 与 {@code url} 都为空。 */
        SOURCE_ABSENT,
        /** 非 http(s) 源（§8.4）。 */
        URL_SCHEME_UNSUPPORTED,
        /** 平台侧下载失败或响应为空。 */
        DOWNLOAD_FAILED,
        /** 声明了 {@code sha256} 且与实际不符。 */
        CHECKSUM_MISMATCH
    }

    /**
     * 前置条件不满足：脚本未上传、未执行。携带结构化字段而非文案，
     * 因为 OP-05 未关闭且 ui-spec §6.3 只登记了「补丁包」摘要不符的词面，脚本侧没有对应行。
     */
    @lombok.Getter
    public static class ScriptPreconditionException extends RuntimeException {

        private final ScriptPrecondition precondition;
        private final String expectedSha256;
        private final String actualSha256;

        public ScriptPreconditionException(ScriptPrecondition precondition, String expectedSha256,
                                           String actualSha256, String technicalDetail) {
            super(precondition + (technicalDetail == null ? "" : ": " + technicalDetail));
            this.precondition = precondition;
            this.expectedSha256 = expectedSha256;
            this.actualSha256 = actualSha256;
        }
    }
}
