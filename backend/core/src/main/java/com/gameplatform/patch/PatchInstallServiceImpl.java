package com.gameplatform.patch;

import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.plugin.patch.HostCapabilities;
import com.gameplatform.plugin.patch.PatchInstallProgressListener;
import com.gameplatform.plugin.patch.PatchInstallRequest;
import com.gameplatform.plugin.patch.PatchInstallService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.plugin.task.TaskService;
import com.gameplatform.plugin.task.TaskSubmitRequest;
import com.gameplatform.task.TaskMutexManager;
import com.gameplatform.vo.InstanceVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 补丁安装服务实现（core，ADR-0006 决策 1）
 *
 * <p>{@link #install} 提交任务中心任务（source=MAIN、taskType=PATCH_INSTALL），
 * scopeType=HOST + scopeKey=hostId 实现同主机互斥（ADR-0006 决策 8）；
 * {@link #installSync} 直调 {@link PatchInstallExecutor}，不经任务中心，
 * 因此自行承任务中心同一个内存互斥键 {@code PATCH_INSTALL:<hostId>}（design.md §14.14）；
 * {@link #probeHost} 同步执行宿主机能力探测供 UI 预检。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PatchInstallServiceImpl implements PatchInstallService {

    /** 与任务中心 {@code computeMutexKey} 默认规则（taskType + ":" + scopeKey）逐字同源，改动即互斥失效。 */
    private static final String MUTEX_KEY_PREFIX = "PATCH_INSTALL:";

    /** holder 前缀：非任务 ID，{@code removeByTaskId} 拿不到它，故不会被 PENDING 超时/崩溃恢复误释放。 */
    private static final String MUTEX_HOLDER_PREFIX = "EXT:";

    private static final PatchInstallProgressListener SILENT_LISTENER = new PatchInstallProgressListener() {
        @Override
        public void onProgress(int percent, String message) {
        }

        @Override
        public void onLog(String message) {
        }

        @Override
        public boolean isCancelled() {
            return false;
        }
    };

    private final TaskService taskService;
    private final InstanceQueryService instanceQueryService;
    private final HostCapabilityProber prober;
    private final PatchInstallExecutor executor;
    private final TaskMutexManager taskMutexManager;

    /** 等待预算与轮询间隔（design.md §8.1 / §14.14）。非 final 是测试接缝：单测要能把「等满预算」跑出来。 */
    long mutexPollIntervalMs = 2_000L;
    long mutexWaitBudgetMs = 600_000L;

    private final AtomicLong syncSequence = new AtomicLong();

    @Override
    public String install(PatchInstallRequest request) {
        if (request == null || request.getInstanceId() == null
                || request.getUrl() == null || request.getUrl().isBlank()
                || request.getTargetPath() == null) {
            throw new BusinessException("instanceId/url/targetPath 不能为空");
        }

        InstanceVO instance = instanceQueryService.getInstanceById(request.getInstanceId());
        if (instance == null) {
            throw new BusinessException("实例不存在: " + request.getInstanceId());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instanceId", request.getInstanceId());
        payload.put("url", request.getUrl());
        payload.put("targetPath", request.getTargetPath());
        if (request.getFormat() != null) {
            payload.put("format", request.getFormat());
        }
        if (request.getSha256() != null) {
            payload.put("sha256", request.getSha256());
        }

        TaskSubmitRequest submit = TaskSubmitRequest.builder()
                .taskType("PATCH_INSTALL")
                .source("MAIN")
                .scopeType("HOST")
                .scopeKey(String.valueOf(instance.getHostId()))
                .scopeName("补丁安装: " + instance.getInstanceName())
                .payload(payload)
                .build();

        String taskId = taskService.submit(submit);
        log.info("提交补丁安装任务: taskId={}, instanceId={}, url={}",
                taskId, request.getInstanceId(), request.getUrl());
        return taskId;
    }

    @Override
    public void installSync(PatchInstallRequest request, PatchInstallProgressListener listener) {
        if (request == null || request.getInstanceId() == null) {
            throw new BusinessException("instanceId 不能为空");
        }
        InstanceVO instance = instanceQueryService.getInstanceById(request.getInstanceId());
        if (instance == null) {
            throw new BusinessException("实例不存在: " + request.getInstanceId());
        }

        // 每主机互斥不在 PatchInstallExecutor 内（该类只承全局闸），直调 execute() 恰好绕开
        // 任务中心那条 submit 路径上的键占用，因此这里承同一个键补回来（design.md §14.14）。
        String mutexKey = MUTEX_KEY_PREFIX + instance.getHostId();
        // §14.14 写的是 EXT:<instanceId>:<stepIndex>，但 installSync 的 SDK 签名里没有 stepIndex
        // （序号属调用方的呈现层概念）。改用进程内自增后缀：前缀不变 ⇒ 仍不是任何 DB 任务 ID，
        // 且保证两个线程不会拿到同一个 holder（否则 putIfAbsent 会把「别人已持键」误判成「自己持着」）。
        String holder = MUTEX_HOLDER_PREFIX + request.getInstanceId() + ":" + syncSequence.incrementAndGet();
        acquireHostMutex(mutexKey, holder, instance.getHostId());
        try {
            // 请求对象引用透传：不经 payload 序列化，includePattern / headers 因此不被截断（§14.2）
            executor.execute(request, asExecutorListener(listener));
        } finally {
            taskMutexManager.remove(mutexKey, holder);
        }
    }

    /** 等满预算即判失败并指名原因，不静默跳过该步（§14.14「等待预算取 600 s」行）。 */
    private void acquireHostMutex(String mutexKey, String holder, Long hostId) {
        long deadline = System.currentTimeMillis() + mutexWaitBudgetMs;
        while (System.currentTimeMillis() < deadline) {
            if (taskMutexManager.putIfAbsent(mutexKey, holder)) {
                return;
            }
            try {
                Thread.sleep(mutexPollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // OP-05 缺口：ui-spec 只登记了「等满预算」那一支的词面，「等待期被中断」没有对应行。
                // 这里不新造产品文案，只给可归因的技术串（已在 MERC-20 回贴里登记为缺口）。
                throw new BusinessException("等待同主机补丁互斥被中断，实例 hostId=" + hostId);
            }
        }
        throw new BusinessException("等待同主机补丁互斥超时（" + mutexWaitBudgetMs / 1000 + "s），实例 hostId=" + hostId);
    }

    /**
     * SDK 回调 → 执行器回调的同形状桥接。{@code isCancelled} 原样透传而不固定为 {@code false}：
     * §14.1 的「扩展阶段传 () -> false」是**调用方**的义务，本服务不该替它写死——
     * 插件直接调 {@code installSync} 时那条取消通道仍然可用。
     */
    private static PatchInstallExecutor.ProgressListener asExecutorListener(PatchInstallProgressListener listener) {
        PatchInstallProgressListener source = listener == null ? SILENT_LISTENER : listener;
        return new PatchInstallExecutor.ProgressListener() {
            @Override
            public void onProgress(int percent, String message) {
                source.onProgress(percent, message);
            }

            @Override
            public void onLog(String message) {
                source.onLog(message);
            }

            @Override
            public boolean isCancelled() {
                return source.isCancelled();
            }
        };
    }

    @Override
    public HostCapabilities probeHost(Long hostId) {
        if (hostId == null) {
            throw new BusinessException("hostId 不能为空");
        }
        return prober.probe(hostId);
    }
}
