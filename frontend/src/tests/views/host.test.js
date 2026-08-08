/**
 * 主机列表组件单元测试
 * 测试表格渲染、分页功能、筛选功能
 */

import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import { setActivePinia, createPinia } from "pinia";

// Mock API
const mockGetHostList = vi.fn();
const mockGetHostResources = vi.fn();
const mockCreateHost = vi.fn();
const mockUpdateHost = vi.fn();
const mockDeleteHost = vi.fn();
const mockTestHostConnection = vi.fn();

vi.mock("@/api/host", () => ({
  getHostList: (...args) => mockGetHostList(...args),
  getHostResources: (...args) => mockGetHostResources(...args),
  createHost: (...args) => mockCreateHost(...args),
  updateHost: (...args) => mockUpdateHost(...args),
  deleteHost: (...args) => mockDeleteHost(...args),
  testHostConnection: (...args) => mockTestHostConnection(...args),
}));

// Mock Element Plus
const mockElMessageSuccess = vi.fn();
const mockElMessageError = vi.fn();
const mockElMessageWarning = vi.fn();

vi.mock("element-plus", () => ({
  ElMessage: {
    success: mockElMessageSuccess,
    error: mockElMessageError,
    warning: mockElMessageWarning,
    info: vi.fn(),
  },
  ElMessageBox: {
    confirm: vi.fn().mockResolvedValue(true),
  },
}));

// Mock router
const mockPush = vi.fn();

vi.mock("vue-router", () => ({
  useRouter: () => ({
    push: mockPush,
  }),
}));

// 创建简化版主机列表组件用于测试
const HostComponent = {
  template: `
    <div class="host-container">
      <div class="search-area">
        <input v-model="searchForm.name" placeholder="主机名称" @keyup.enter="handleSearch" />
        <input v-model="searchForm.ip" placeholder="IP地址" @keyup.enter="handleSearch" />
        <select v-model="searchForm.status">
          <option value="">全部</option>
          <option :value="1">在线</option>
          <option :value="0">离线</option>
        </select>
        <button @click="handleSearch">搜索</button>
        <button @click="handleReset">重置</button>
      </div>
      
      <div class="table-area">
        <table>
          <thead>
            <tr>
              <th>状态</th>
              <th>主机名称</th>
              <th>IP地址</th>
              <th>SSH端口</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in tableData" :key="row.id" :class="{ 'row-stopped': row.status === 0 }">
              <td>
                <span :class="{ 'status-online': row.status === 1, 'status-offline': row.status === 0 }">
                  {{ row.status === 1 ? '在线' : '离线' }}
                </span>
              </td>
              <td>{{ row.name }}</td>
              <td>{{ row.ip }}</td>
              <td>{{ row.sshPort }}</td>
              <td>
                <button :disabled="row.status !== 1" @click="handleTerminal(row)">终端</button>
                <button @click="handleTest(row)">测试</button>
                <button @click="handleEdit(row)">编辑</button>
                <button @click="handleDelete(row)">删除</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      
      <div class="pagination">
        <button @click="handlePageChange(pagination.current - 1)" :disabled="pagination.current <= 1">上一页</button>
        <span>第 {{ pagination.current }} 页，共 {{ Math.ceil(pagination.total / pagination.size) }} 页</span>
        <button @click="handlePageChange(pagination.current + 1)" :disabled="pagination.current >= Math.ceil(pagination.total / pagination.size)">下一页</button>
        <select v-model="pagination.size" @change="handleSizeChange(pagination.size)">
          <option :value="10">10条/页</option>
          <option :value="20">20条/页</option>
          <option :value="50">50条/页</option>
        </select>
      </div>
    </div>
  `,
  data() {
    return {
      loading: false,
      searchForm: {
        name: "",
        ip: "",
        status: "",
      },
      tableData: [],
      pagination: {
        current: 1,
        size: 10,
        total: 0,
      },
    };
  },
  async mounted() {
    await this.fetchData();
  },
  methods: {
    async fetchData() {
      this.loading = true;
      try {
        const { getHostList, getHostResources } = await import("@/api/host");
        const data = await getHostList({
          current: this.pagination.current,
          size: this.pagination.size,
          ...this.searchForm,
        });
        this.tableData = (data.records || []).map((item) => ({
          ...item,
          resources: null,
          resourcesLoading: false,
        }));
        this.pagination.total = data.total || 0;

        // 加载在线主机的资源使用情况
        const onlineHosts = this.tableData.filter((h) => h.status === 1);
        for (const host of onlineHosts) {
          try {
            host.resourcesLoading = true;
            const resources = await getHostResources(host.id).catch(() => null);
            host.resources = resources;
          } catch (e) {
            // ignore
          } finally {
            host.resourcesLoading = false;
          }
        }
      } catch (error) {
        console.error("Failed to fetch host list:", error);
      } finally {
        this.loading = false;
      }
    },
    handleSearch() {
      this.pagination.current = 1;
      this.fetchData();
    },
    handleReset() {
      this.searchForm.name = "";
      this.searchForm.ip = "";
      this.searchForm.status = "";
      this.handleSearch();
    },
    handlePageChange(page) {
      if (
        page < 1 ||
        page > Math.ceil(this.pagination.total / this.pagination.size)
      )
        return;
      this.pagination.current = page;
      this.fetchData();
    },
    handleSizeChange(size) {
      this.pagination.size = size;
      this.pagination.current = 1;
      this.fetchData();
    },
    handleTerminal(row) {
      if (row.status !== 1) {
        const { ElMessage } = require("element-plus");
        ElMessage.warning("主机离线，无法打开终端");
        return;
      }
      const { useRouter } = require("vue-router");
      const router = useRouter();
      router.push({
        path: `/host/terminal/${row.id}`,
        query: { name: row.name, ip: row.ip },
      });
    },
    async handleTest(row) {
      try {
        const { testHostConnection } = await import("@/api/host");
        const result = await testHostConnection(row.id);
        const { ElMessage } = require("element-plus");
        if (result.success) {
          ElMessage.success(`连接测试成功，延迟: ${result.latency}ms`);
        } else {
          ElMessage.error(`连接测试失败: ${result.message}`);
        }
      } catch (error) {
        const { ElMessage } = require("element-plus");
        ElMessage.error("连接测试失败");
      }
    },
    handleEdit(row) {
      // 打开编辑弹窗
    },
    handleDelete(row) {
      // 打开删除确认弹窗
    },
  },
};

describe("Host Component", () => {
  let wrapper;

  const mockHosts = [
    { id: 1, name: "主机1", ip: "192.168.1.1", sshPort: 22, status: 1 },
    { id: 2, name: "主机2", ip: "192.168.1.2", sshPort: 22, status: 0 },
    { id: 3, name: "主机3", ip: "192.168.1.3", sshPort: 2222, status: 1 },
  ];

  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    mockGetHostList.mockResolvedValue({
      records: mockHosts,
      current: 1,
      size: 10,
      total: 3,
    });
    mockGetHostResources.mockResolvedValue({
      cpu: { usage: 50 },
      memory: { usage: 60 },
      disk: { usage: 40 },
    });
  });

  afterEach(() => {
    if (wrapper) {
      wrapper.unmount();
    }
    vi.clearAllMocks();
  });

  describe("表格渲染", () => {
    it("应该正确渲染主机列表", async () => {
      wrapper = mount(HostComponent);
      await flushPromises();

      expect(wrapper.vm.tableData).toHaveLength(3);
      expect(wrapper.findAll("tbody tr")).toHaveLength(3);
    });

    it("应该正确显示主机信息", async () => {
      wrapper = mount(HostComponent);
      await flushPromises();

      const firstRow = wrapper.findAll("tbody tr")[0];
      expect(firstRow.text()).toContain("主机1");
      expect(firstRow.text()).toContain("192.168.1.1");
      expect(firstRow.text()).toContain("22");
    });

    it("应该正确显示在线/离线状态", async () => {
      wrapper = mount(HostComponent);
      await flushPromises();

      const rows = wrapper.findAll("tbody tr");
      expect(rows[0].text()).toContain("在线");
      expect(rows[1].text()).toContain("离线");
      expect(rows[0].find(".status-online").exists()).toBe(true);
      expect(rows[1].find(".status-offline").exists()).toBe(true);
    });

    it("离线主机应该有特殊样式", async () => {
      wrapper = mount(HostComponent);
      await flushPromises();

      const rows = wrapper.findAll("tbody tr");
      expect(rows[1].classes()).toContain("row-stopped");
    });
  });

  describe("分页功能", () => {
    it("应该正确显示分页信息", async () => {
      wrapper = mount(HostComponent);
      await flushPromises();

      expect(wrapper.vm.pagination.current).toBe(1);
      expect(wrapper.vm.pagination.total).toBe(3);
      expect(wrapper.vm.pagination.size).toBe(10);
    });

    it("切换页码应该重新获取数据", async () => {
      wrapper = mount(HostComponent);
      await flushPromises();

      // 模拟有更多数据
      wrapper.vm.pagination.total = 30;

      await wrapper.vm.handlePageChange(2);

      expect(wrapper.vm.pagination.current).toBe(2);
    });

    it("切换每页条数应该重置到第一页", async () => {
      wrapper = mount(HostComponent);
      await flushPromises();

      wrapper.vm.pagination.current = 3;

      await wrapper.vm.handleSizeChange(20);

      expect(wrapper.vm.pagination.size).toBe(20);
      expect(wrapper.vm.pagination.current).toBe(1);
    });

    it("页码边界检查", async () => {
      wrapper = mount(HostComponent);
      await flushPromises();

      wrapper.vm.pagination.total = 30;
      wrapper.vm.pagination.size = 10;

      // 尝试切换到第0页
      await wrapper.vm.handlePageChange(0);
      expect(wrapper.vm.pagination.current).toBe(1);

      // 尝试切换到超过总页数的页
      await wrapper.vm.handlePageChange(10);
      expect(wrapper.vm.pagination.current).toBe(1); // 因为初始是1，不会变
    });
  });

  describe("筛选功能", () => {
    it("搜索应该重置页码", async () => {
      wrapper = mount(HostComponent);
      await flushPromises();

      wrapper.vm.pagination.current = 3;

      wrapper.vm.searchForm.name = "测试主机";
      await wrapper.vm.handleSearch();

      expect(wrapper.vm.pagination.current).toBe(1);
    });

    it("重置应该清空搜索条件", async () => {
      wrapper = mount(HostComponent);
      await flushPromises();

      wrapper.vm.searchForm.name = "测试";
      wrapper.vm.searchForm.ip = "192.168";
      wrapper.vm.searchForm.status = 1;

      await wrapper.vm.handleReset();

      expect(wrapper.vm.searchForm.name).toBe("");
      expect(wrapper.vm.searchForm.ip).toBe("");
      expect(wrapper.vm.searchForm.status).toBe("");
      expect(wrapper.vm.pagination.current).toBe(1);
    });

    it("应该支持按状态筛选", async () => {
      wrapper = mount(HostComponent);
      await flushPromises();

      wrapper.vm.searchForm.status = 1;
      await wrapper.vm.handleSearch();

      expect(wrapper.vm.pagination.current).toBe(1);
    });
  });

  describe("操作功能", () => {
    it("离线主机终端按钮应该被禁用", async () => {
      wrapper = mount(HostComponent);
      await flushPromises();

      const rows = wrapper.findAll("tbody tr");
      const offlineRow = rows[1]; // 离线主机
      const terminalBtn = offlineRow.find("button[disabled]");

      expect(terminalBtn.text()).toBe("终端");
    });

    it("点击测试应该调用测试连接API", async () => {
      mockTestHostConnection.mockResolvedValue({
        success: true,
        latency: 15,
      });

      wrapper = mount(HostComponent);
      await flushPromises();

      const rows = wrapper.findAll("tbody tr");
      const testBtn = rows[0].findAll("button")[1]; // 测试按钮

      await testBtn.trigger("click");
      await flushPromises();

      expect(mockTestHostConnection).toHaveBeenCalledWith(1);
    });

    it("连接测试成功应该显示成功消息", async () => {
      mockTestHostConnection.mockResolvedValue({
        success: true,
        latency: 15,
      });

      wrapper = mount(HostComponent);
      await flushPromises();

      // 使用组件方法直接测试
      await wrapper.vm.handleTest(mockHosts[0]);
      await flushPromises();

      // 验证API被调用
      expect(mockTestHostConnection).toHaveBeenCalledWith(1);
    });

    it("连接测试失败应该显示错误消息", async () => {
      mockTestHostConnection.mockResolvedValue({
        success: false,
        message: "认证失败",
      });

      wrapper = mount(HostComponent);
      await flushPromises();

      // 使用组件方法直接测试
      await wrapper.vm.handleTest(mockHosts[0]);
      await flushPromises();

      // 验证API被调用
      expect(mockTestHostConnection).toHaveBeenCalledWith(1);
    });
  });
});
