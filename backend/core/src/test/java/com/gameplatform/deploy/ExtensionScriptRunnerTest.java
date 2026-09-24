package com.gameplatform.deploy;

import cn.hutool.crypto.digest.DigestUtil;
import com.gameplatform.deploy.ExtensionScriptRunner.ScriptPrecondition;
import com.gameplatform.deploy.ExtensionScriptRunner.ScriptPreconditionException;
import com.gameplatform.deploy.ExtensionScriptRunner.ScriptRunResult;
import com.gameplatform.plugin.extension.deploy.ScriptStepDeclaration;
import com.gameplatform.plugin.service.FileAccessService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sun.net.httpserver.HttpServer;

/**
 * 脚本执行安全形状测试（design.md §7.2 B-13、§8.3、§8.4，V-13 / V-14 / FR-15 前提）。
 *
 * <p>核三件事：正文<b>永不进命令行</b>且校验不过<b>不在宿主机落地</b>（V-13）、
 * 输出按头 2000 + 尾 2000 截断并给出完整字节数（V-14）、
 * {@code exitCode} 从 {@code CommandResult} 取到并可交给他处（§14.6 规则 4 的前提）。
 */
@ExtendWith(MockitoExtension.class)
class ExtensionScriptRunnerTest {

    private static final Long HOST_ID = 3L;
    private static final String WORK_DIR = "/srv/app";
    private static final String SCRIPT_FILE = WORK_DIR + "/.platform-extension/E-2.sh";
    private static final String BODY_MARKER = "UNIQUE-SCRIPT-BODY-MARKER";

    private static HttpServer fixtureServer;
    private static String fixtureBaseUrl;

    @Mock
    private FileAccessService fileAccessService;

    private final List<String> commands = new ArrayList<>();
    private ExtensionScriptRunner runner;

    @AfterAll
    static void shutDownServer() {
        if (fixtureServer != null) {
            fixtureServer.stop(0);
        }
    }

    @BeforeEach
    void setUp() {
        runner = new ExtensionScriptRunner(fileAccessService);
        lenient().when(fileAccessService.executeCommand(anyLong(), anyString(), anyLong()))
                .thenAnswer(invocation -> {
                    commands.add(invocation.getArgument(1));
                    return commandResult(0, "", "");
                });
        lenient().when(fileAccessService.executeCommand(anyLong(), anyString()))
                .thenAnswer(invocation -> {
                    commands.add(invocation.getArgument(1));
                    return commandResult(0, "", "");
                });
    }

    // ==================== V-13：安全形状 ====================

    @Nested
    @DisplayName("V-13 安全形状")
    class SecurityShape {

        @Test
        @DisplayName("正文落临时文件执行，命令行里只出现脚本文件名")
        void scriptBodyNeverAppearsInCommand() {
            ScriptRunResult result = runner.run(hostScript("echo '" + BODY_MARKER + "'\nexit 0", null),
                    2, HOST_ID, WORK_DIR);

            String bash = executionCommand();
            assertTrue(bash.contains("bash '" + SCRIPT_FILE + "'"), "按文件执行：" + bash);
            assertFalse(bash.contains(BODY_MARKER), "命令文本不含脚本正文");
            assertTrue(commands.stream().noneMatch(c -> c.contains(BODY_MARKER)), "全程任何命令都不含正文");
            assertEquals(Integer.valueOf(0), result.exitCode());
        }

        @Test
        @DisplayName("上传到 workDir 下的 .platform-extension/E-<n>.sh")
        void uploadsUnderHiddenWorkDirSubtree() {
            runner.run(hostScript("true", null), 2, HOST_ID, WORK_DIR);

            ArgumentCaptor<String> remote = ArgumentCaptor.forClass(String.class);
            verify(fileAccessService).uploadLocalFile(eq(HOST_ID), remote.capture(), anyString());
            assertEquals(SCRIPT_FILE, remote.getValue());
            assertTrue(commands.stream().anyMatch(c -> c.equals("mkdir -p '"
                    + WORK_DIR + "/.platform-extension'")), "先建目录再上传：" + commands);
        }

        @Test
        @DisplayName("finally 删除临时脚本，并收掉只放临时脚本的空目录")
        void deletesRemoteScriptAfterSuccess() {
            runner.run(hostScript("true", null), 2, HOST_ID, WORK_DIR);
            verify(fileAccessService).deleteFile(HOST_ID, SCRIPT_FILE);
            assertTrue(commands.contains("rmdir '" + WORK_DIR + "/.platform-extension'"),
                    "§8.4：.platform-extension 只放临时脚本，收尾一并删除：" + commands);
        }

        @Test
        @DisplayName("上传中途断掉也要删掉那半个临时脚本")
        void deletesRemoteScriptWhenUploadBreaksMidway() {
            org.mockito.Mockito.doThrow(new IllegalStateException("sftp 通道断了"))
                    .when(fileAccessService).uploadLocalFile(anyLong(), anyString(), anyString());

            assertThrows(IllegalStateException.class,
                    () -> runner.run(hostScript("true", null), 2, HOST_ID, WORK_DIR));
            verify(fileAccessService).deleteFile(HOST_ID, SCRIPT_FILE);
        }

        @Test
        @DisplayName("执行抛异常也删除临时脚本")
        void deletesRemoteScriptAfterFailure() {
            lenient().when(fileAccessService.executeCommand(anyLong(), anyString(), anyLong()))
                    .thenThrow(new IllegalStateException("ssh channel down"));
            assertThrows(IllegalStateException.class,
                    () -> runner.run(hostScript("true", null), 2, HOST_ID, WORK_DIR));
            verify(fileAccessService).deleteFile(HOST_ID, SCRIPT_FILE);
        }

        @Test
        @DisplayName("摘要不符：不在宿主机落地，也不建目录")
        void checksumMismatchLeavesNoHostFootprint() throws Exception {
            String url = fixtureUrl("echo hi\n");
            ScriptStepDeclaration step = urlScript(url, "0".repeat(64));

            ScriptPreconditionException e = assertThrows(ScriptPreconditionException.class,
                    () -> runner.run(step, 2, HOST_ID, WORK_DIR));
            assertEquals(ScriptPrecondition.CHECKSUM_MISMATCH, e.getPrecondition());
            assertEquals(64, e.getActualSha256().length());
            verifyNoInteractions(fileAccessService);
        }

        @Test
        @DisplayName("非 http(s) 源一律拒绝")
        void rejectsNonHttpScheme() {
            ScriptStepDeclaration step = urlScript("file:///etc/passwd", null);
            ScriptPreconditionException e = assertThrows(ScriptPreconditionException.class,
                    () -> runner.run(step, 2, HOST_ID, WORK_DIR));
            assertEquals(ScriptPrecondition.URL_SCHEME_UNSUPPORTED, e.getPrecondition());
            verifyNoInteractions(fileAccessService);
        }

        @Test
        @DisplayName("源不可达：归入 DOWNLOAD_FAILED，不在宿主机落地")
        void unreachableSourceIsClassifiedAsDownloadFailure() {
            ScriptStepDeclaration step = urlScript("http://127.0.0.1:1/missing.sh", null);

            ScriptPreconditionException e = assertThrows(ScriptPreconditionException.class,
                    () -> runner.run(step, 2, HOST_ID, WORK_DIR));
            assertEquals(ScriptPrecondition.DOWNLOAD_FAILED, e.getPrecondition());
            verifyNoInteractions(fileAccessService);
        }

        @Test
        @DisplayName("声明了摘要且校验通过才上传执行")
        void verifiedDownloadIsUploadedAndRun() throws Exception {
            String body = "echo ok\n";
            String url = fixtureUrl(body);
            runner.run(urlScript(url, DigestUtil.sha256Hex(body.getBytes(StandardCharsets.UTF_8))),
                    2, HOST_ID, WORK_DIR);

            verify(fileAccessService).uploadLocalFile(eq(HOST_ID), eq(SCRIPT_FILE), anyString());
            assertTrue(executionCommand().contains("bash '" + SCRIPT_FILE + "'"));
        }

        @Test
        @DisplayName("未声明摘要则不校验，直接执行")
        void undeclaredChecksumSkipsVerification() throws Exception {
            runner.run(urlScript(fixtureUrl("echo ok\n"), null), 2, HOST_ID, WORK_DIR);
            verify(fileAccessService).uploadLocalFile(eq(HOST_ID), eq(SCRIPT_FILE), anyString());
        }
    }

    // ==================== V-14：输出截断 ====================

    @Nested
    @DisplayName("V-14 输出体量硬约束")
    class OutputTruncation {

        @Test
        @DisplayName("stdout 截为头 2000 + 尾 2000，NOTE 行给出完整字节数")
        void stdoutTruncatedToHeadAndTail() {
            String head = "h".repeat(2000);
            String middle = "m".repeat(1000);
            String tail = "t".repeat(2000);
            stubOutput(head + middle + tail, "");

            ScriptRunResult result = runner.run(hostScript("true", null), 2, HOST_ID, WORK_DIR);

            assertEquals(4000, result.stdout().text().length());
            assertEquals(head + tail, result.stdout().text());
            assertTrue(result.stdout().truncated());
            assertEquals(5000, result.stdout().originalBytes());
            assertEquals("输出已截断，共 5000 字节", result.stdout().truncationNote());
            assertFalse(result.stderr().truncated());
            assertNull(result.stderr().truncationNote());
        }

        @Test
        @DisplayName("字节数按 UTF-8 计，与「共 N 字节」的词面一致")
        void byteCountIsUtf8NotCharCount() {
            String chinese = "中".repeat(4001);
            stubOutput(chinese, "");

            ScriptRunResult result = runner.run(hostScript("true", null), 2, HOST_ID, WORK_DIR);

            assertEquals(4000, result.stdout().text().length(), "截断按字符：头 2000 + 尾 2000");
            assertEquals(chinese.getBytes(StandardCharsets.UTF_8).length, result.stdout().originalBytes());
        }

        @Test
        @DisplayName("未超阈值原样保留，不产 NOTE")
        void smallOutputPassesThrough() {
            stubOutput("short\n", "err\n");
            ScriptRunResult result = runner.run(hostScript("true", null), 2, HOST_ID, WORK_DIR);
            assertEquals("short\n", result.stdout().text());
            assertEquals("err\n", result.stderr().text());
            assertFalse(result.stdout().truncated());
        }
    }

    // ==================== FR-15 / §14.6 规则 4 ====================

    @Nested
    @DisplayName("退出码与超时")
    class ExitCodeAndTimeout {

        @Test
        @DisplayName("非零退出码从 CommandResult 取到并交给上层")
        void nonZeroExitCodeReachesCaller() {
            lenient().when(fileAccessService.executeCommand(anyLong(), anyString(), anyLong()))
                    .thenAnswer(invocation -> {
                        commands.add(invocation.getArgument(1));
                        return commandResult(3, "", "boom");
                    });

            ScriptRunResult result = runner.run(hostScript("exit 3", null), 2, HOST_ID, WORK_DIR);

            assertEquals(Integer.valueOf(3), result.exitCode());
            assertFalse(result.succeeded());
            assertEquals("boom", result.stderr().text());
            assertFalse(result.timedOut());
        }

        @Test
        @DisplayName("零退出码判成功")
        void zeroExitCodeSucceeds() {
            ScriptRunResult result = runner.run(hostScript("true", null), 2, HOST_ID, WORK_DIR);
            assertEquals(Integer.valueOf(0), result.exitCode());
            assertTrue(result.succeeded());
        }

        @Test
        @DisplayName("超时：exitCode 置 null 以与「非零退出」区分（§14.6 规则 4）")
        void timeoutYieldsNullExitCode() {
            long budget = 1_000L;
            lenient().when(fileAccessService.executeCommand(anyLong(), anyString(), anyLong()))
                    .thenAnswer(invocation -> {
                        commands.add(invocation.getArgument(1));
                        // 远端 shell timeout 在整秒处把进程杀掉，耗时必然 >= 那一秒
                        Thread.sleep(budget + 150);
                        return commandResult(124, "", "");
                    });

            ScriptRunResult result = runner.run(hostScript("sleep 999", budget), 2, HOST_ID, WORK_DIR);

            assertTrue(result.timedOut());
            assertNull(result.exitCode());
            assertFalse(result.succeeded());
            assertEquals(budget, result.timeoutMs());
        }

        @Test
        @DisplayName("秒的粒度向上取整：不提前杀掉还没用满预算的脚本")
        void subSecondBudgetRoundsUpNotDown() {
            runner.run(hostScript("true", 1_400L), 2, HOST_ID, WORK_DIR);
            assertTrue(executionCommand().startsWith("timeout 2 bash "),
                    "1400ms 若向下取整成 1 s，就是在声明预算内提前判死（BR-04 不得覆盖声明）："
                            + executionCommand());
        }

        @Test
        @DisplayName("脚本自身以 124 退出但没等满预算，不算超时")
        void selfExit124WithinBudgetIsNotTimeout() {
            lenient().when(fileAccessService.executeCommand(anyLong(), anyString(), anyLong()))
                    .thenAnswer(invocation -> {
                        commands.add(invocation.getArgument(1));
                        return commandResult(124, "", "");
                    });
            ScriptRunResult result = runner.run(hostScript("exit 124", 60_000L), 2, HOST_ID, WORK_DIR);
            assertFalse(result.timedOut());
            assertEquals(Integer.valueOf(124), result.exitCode());
        }

        @Test
        @DisplayName("缺省超时 600 s，并用 shell timeout 兜底")
        void defaultTimeoutIsSixHundredSeconds() {
            runner.run(hostScript("true", null), 2, HOST_ID, WORK_DIR);

            verify(fileAccessService).executeCommand(eq(HOST_ID), anyString(), eq(600_000L));
            String bash = executionCommand();
            assertTrue(bash.startsWith("timeout 600 bash "),
                    "SshUtil 的 timeoutMs 只作用于建连，命令本身须有 shell 超时兜底：" + bash);
        }

        @Test
        @DisplayName("声明的超时原样生效，不静默夹取")
        void declaredTimeoutIsPassedThrough() {
            runner.run(hostScript("true", 45_000L), 2, HOST_ID, WORK_DIR);
            verify(fileAccessService).executeCommand(eq(HOST_ID), contains("timeout 45 bash"), eq(45_000L));
        }
    }

    // ==================== 桩与工具 ====================

    private String executionCommand() {
        return commands.stream().filter(c -> c.contains("bash ")).findFirst()
                .orElseThrow(() -> new AssertionError("没有发出 bash 命令：" + commands));
    }

    private void stubOutput(String stdout, String stderr) {
        lenient().when(fileAccessService.executeCommand(anyLong(), anyString(), anyLong()))
                .thenAnswer(invocation -> {
                    commands.add(invocation.getArgument(1));
                    return commandResult(0, stdout, stderr);
                });
    }

    private static ScriptStepDeclaration hostScript(String content, Long timeoutMs) {
        return new ScriptStepDeclaration("脚本", content, null, null, null, true, timeoutMs);
    }

    private static ScriptStepDeclaration urlScript(String url, String sha256) {
        return new ScriptStepDeclaration("脚本", null, url, sha256, null, true, null);
    }

    private static FileAccessService.CommandResult commandResult(int exitCode, String output, String error) {
        FileAccessService.CommandResult result = new FileAccessService.CommandResult();
        result.setExitCode(exitCode);
        result.setSuccess(exitCode == 0);
        result.setOutput(output);
        result.setError(error);
        return result;
    }

    // ==================== 受控夹具 HTTP 源 ====================

    private static String fixtureUrl(String body) throws IOException {
        if (fixtureServer == null) {
            fixtureServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            fixtureServer.start();
            fixtureBaseUrl = "http://127.0.0.1:" + fixtureServer.getAddress().getPort();
        }
        String path = "/s-" + System.nanoTime() + ".sh";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        fixtureServer.createContext(path, exchange -> {
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        return fixtureBaseUrl + path;
    }
}
