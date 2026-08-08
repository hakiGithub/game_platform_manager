import { createPinia } from "pinia";

const pinia = createPinia();

export default pinia;

// 导出所有 store
export * from "./user";
export * from "./app";
export * from "./host";
export * from "./instance";
