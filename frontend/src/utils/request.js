import axios from "axios";
import { ElMessage, ElMessageBox } from "element-plus";
import { useUserStore } from "@/stores/user";
import router from "@/router";

// 创建 axios 实例
const service = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || "/api",
  timeout: 30000,
  headers: {
    "Content-Type": "application/json;charset=UTF-8",
  },
});

// 请求拦截器
service.interceptors.request.use(
  (config) => {
    const userStore = useUserStore();

    // 添加 token
    if (userStore.token) {
      config.headers["Authorization"] = `Bearer ${userStore.token}`;
    }

    return config;
  },
  (error) => {
    console.error("Request error:", error);
    return Promise.reject(error);
  },
);

// 响应拦截器
service.interceptors.response.use(
  (response) => {
    const { data } = response;

    // 如果是文件下载，直接返回
    if (response.config.responseType === "blob") {
      return response;
    }

    // 业务状态码判断
    if (data.code === 0 || data.code === 200) {
      return data.data;
    }

    // 业务错误处理（silent 模式下不弹全局通知，交由调用方自行处理）
    const errorMessage = data.message || "请求失败";
    if (!response.config?.silent) {
      ElMessage.error(errorMessage);
    }
    return Promise.reject(new Error(errorMessage));
  },
  (error) => {
    const { response } = error;
    // silent 模式：跳过全局错误通知，由调用方自行处理（如插件未安装时的降级空态）
    const silent = error.config?.silent;

    if (response) {
      const { status, data } = response;

      switch (status) {
        case 401:
          // 未授权，清除用户信息并跳转登录页
          ElMessageBox.confirm("登录状态已过期，请重新登录", "提示", {
            confirmButtonText: "重新登录",
            cancelButtonText: "取消",
            type: "warning",
          }).then(() => {
            const userStore = useUserStore();
            userStore.logout();
            router.push("/login");
          });
          break;
        case 403:
          if (!silent) ElMessage.error("没有权限访问该资源");
          break;
        case 404:
          if (!silent) ElMessage.error("请求的资源不存在");
          break;
        case 500:
          if (!silent) ElMessage.error(data?.message || "服务器内部错误");
          break;
        default:
          if (!silent) ElMessage.error(data?.message || `请求错误: ${status}`);
      }
    } else if (error.message.includes("timeout")) {
      if (!silent) ElMessage.error("请求超时，请检查网络连接");
    } else if (error.message.includes("Network")) {
      if (!silent) ElMessage.error("网络错误，请检查网络连接");
    } else {
      if (!silent) ElMessage.error(error.message || "未知错误");
    }

    return Promise.reject(error);
  },
);

export default service;
