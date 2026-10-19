package com.gameplatform.patch;

import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.HostMapper;
import com.gameplatform.plugin.patch.HostCapabilities;
import com.gameplatform.plugin.patch.PatchInstallRequest;
import com.gameplatform.plugin.service.AbstractInstanceFileService;
import com.gameplatform.service.FileService;
import com.gameplatform.util.SshUtil;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("PatchInstall headers 下载")
class PatchInstallHeaderDownloadTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("头文件以 0600 上传，HTTP 服务收到内容，下载结束清理两侧临时文件")
    void sendsHeadersAndCleansTemporaryFiles() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/patch.zip", exchange -> {
            String cookie = exchange.getRequestHeaders().getFirst("Cookie");
            byte[] body = (cookie == null ? "missing" : cookie).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            FileService fileService = mock(FileService.class);
            PatchInstallExecutor executor = newExecutor(fileService);
            Host host = host();
            Path destination = tempDir.resolve("patch.zip");
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/patch.zip";
            ArgumentCaptor<String> uploadedHeader = ArgumentCaptor.forClass(String.class);

            when(fileService.executeCommand(eq(1L), anyString(), anyLong())).thenAnswer(invocation ->
                    executeShell(invocation.getArgument(1), destination, url));
            doAnswer(invocation -> null)
                    .when(fileService).uploadLocalFile(eq(1L), anyString(), anyString());

            invokeRemoteDownload(executor, host, url,
                    Map.of("Cookie", "session=abc"), destination.toString());

            verify(fileService).uploadLocalFile(eq(1L), anyString(), uploadedHeader.capture());
            assertThat(Files.readString(destination, StandardCharsets.UTF_8)).isEqualTo("session=abc");
            assertThat(Path.of(uploadedHeader.getValue())).doesNotExist();
            ArgumentCaptor<String> commands = ArgumentCaptor.forClass(String.class);
            verify(fileService, atLeast(2)).executeCommand(eq(1L), commands.capture(), anyLong());
            assertThat(commands.getAllValues()).anySatisfy(command -> assertThat(command)
                    .contains("chmod 600")
                    .contains(".gp_headers_"));
            assertThat(commands.getAllValues()).anySatisfy(command -> assertThat(command)
                    .contains("curl -fsSL")
                    .contains("--header @")
                    .contains(".gp_headers_"));
            assertThat(commands.getAllValues()).anySatisfy(command -> assertThat(command)
                    .contains("rm -f")
                    .contains(".gp_headers_"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("主机 curl 版本低于 7.55 时以 UNSUPPORTED_HEADER_FILE 失败")
    void rejectsOldCurlForHeaderFile() {
        FileService fileService = mock(FileService.class);
        PatchInstallExecutor executor = newExecutor(fileService);
        Host host = host();
        when(fileService.executeCommand(eq(1L), anyString(), anyLong())).thenAnswer(invocation -> {
            String command = invocation.getArgument(1);
            return command.startsWith("curl --version")
                    ? result(true, "curl 7.29.0 (x86_64)")
                    : result(false, "unexpected command");
        });

        assertThatThrownBy(() -> invokeRemoteDownload(executor, host,
                "https://example.test/patch.zip", Map.of("Cookie", "session=abc"), "/tmp/patch.zip"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(PatchInstallExecutor.ERR_UNSUPPORTED_HEADER_FILE);
    }

    private static SshUtil.CommandResult executeShell(String command, Path destination, String url) {
        if (command.startsWith("curl --version")) {
            return result(true, "curl 8.5.0 (Windows)");
        }
        if (command.startsWith("curl ")) {
            try {
                java.net.http.HttpClient.newHttpClient().send(
                        java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                                .header("Cookie", "session=abc")
                                .GET()
                                .build(),
                        java.net.http.HttpResponse.BodyHandlers.ofFile(destination));
                return result(true, "");
            } catch (Exception e) {
                return result(false, e.getMessage());
            }
        }
        return result(true, "");
    }

    private static PatchInstallExecutor newExecutor(FileService fileService) {
        return new PatchInstallExecutor(
                mock(AbstractInstanceFileService.class),
                fileService,
                mock(HostMapper.class),
                mock(HostCapabilityProber.class),
                new PatchDecisionEngine(),
                mock(PatchArchiveExtractor.class));
    }

    private static Host host() {
        Host host = new Host();
        host.setId(1L);
        return host;
    }

    private static SshUtil.CommandResult result(boolean success, String output) {
        SshUtil.CommandResult result = new SshUtil.CommandResult();
        result.setSuccess(success);
        result.setExitCode(success ? 0 : 1);
        result.setOutput(output);
        result.setError(success ? "" : output);
        return result;
    }

    private static void invokeRemoteDownload(PatchInstallExecutor executor, Host host,
                                             String url, Map<String, String> headers, String destPath) {
        HostCapabilities capabilities = new HostCapabilities();
        capabilities.setTools(Map.of("curl", true));
        try {
            var method = PatchInstallExecutor.class.getDeclaredMethod(
                    "remoteDownload", Host.class, HostCapabilities.class, PatchInstallRequest.class, String.class);
            method.setAccessible(true);
            method.invoke(executor, host, capabilities, PatchInstallRequest.builder()
                    .instanceId(1L)
                    .url(url)
                    .targetPath("/tmp")
                    .headers(headers)
                    .build(), destPath);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new AssertionError(cause);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
