package com.gameplatform.plugin.service;

/**
 * 实例归档导入宿主能力（ADR-0031）。
 *
 * <p>调用方只提交“本地单插件归档 + 实例内相对目标目录”，解压位置、挂载识别、
 * 目录级复制、原子落位、回滚和清理均由主应用负责。插件无法指定 Docker 命令、
 * 镜像名或裸容器执行参数。
 *
 * <p>首版仅支持单插件 ZIP。多插件归档及无法安全识别的归档应由调用方回退到
 * 原有逐文件安装路径。
 */
public interface InstanceArchiveImportService {

    /**
     * 导入一个本地单插件 ZIP 到实例内目录。
     *
     * <p>目标目录按实例相对路径寻址（与 {@link InstanceFileService} 相同）。
     * 导入成功后目标目录内容与归档中的单插件根目录一致；失败时保留旧目录。
     *
     * @param request  导入请求
     * @param listener 阶段进度、取消检查与结构化日志回调；不得为 null
     * @return 导入结果
     * @throws InstanceArchiveImportException 任一阶段失败
     */
    InstanceArchiveImportResult importArchive(InstanceArchiveImportRequest request,
                                              InstanceArchiveImportListener listener);

    /** 导入阶段。进度区间固定，便于 UI 与测试稳定断言。 */
    enum Phase {
        UPLOAD(0, 15, "上传"),
        EXTRACT(15, 40, "解压"),
        IMPORT(40, 65, "导入"),
        VALIDATE(65, 80, "校验"),
        ATOMIC_MOVE(80, 95, "落位"),
        CLEANUP(95, 100, "清理");

        private final int startPercent;
        private final int endPercent;
        private final String label;

        Phase(int startPercent, int endPercent, String label) {
            this.startPercent = startPercent;
            this.endPercent = endPercent;
            this.label = label;
        }

        public int getStartPercent() {
            return startPercent;
        }

        public int getEndPercent() {
            return endPercent;
        }

        public String getLabel() {
            return label;
        }
    }
}
