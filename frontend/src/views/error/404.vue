<script setup>
import { useRouter } from "vue-router";

const router = useRouter();

function goHome() {
  router.push("/");
}

function goBack() {
  router.go(-1);
}
</script>

<template>
  <div class="error-container">
    <div class="error-content">
      <div class="error-code">404</div>
      <div class="error-title">页面不存在</div>
      <div class="error-description">抱歉，您访问的页面不存在或已被移除</div>
      <div class="error-actions">
        <el-button type="primary" @click="goHome">
          <el-icon><HomeFilled /></el-icon>
          返回首页
        </el-button>
        <el-button @click="goBack">
          <el-icon><ArrowLeft /></el-icon>
          返回上页
        </el-button>
      </div>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.error-container {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100vh;
  min-height: 100vh;
  background:
    radial-gradient(circle at 50% 24%, rgba(39, 181, 243, 0.12), transparent 34%),
    var(--platform-bg);
}

.error-content {
  text-align: center;
}

.error-code {
  font-size: 120px;
  font-weight: 700;
  color: var(--platform-cyan);
  line-height: 1;
  margin-bottom: 24px;
  text-shadow: 2px 2px 4px rgba(64, 158, 255, 0.2);
}

.error-title {
  font-size: 32px;
  font-weight: 600;
  color: var(--platform-text-primary);
  margin-bottom: 16px;
}

.error-description {
  font-size: 16px;
  color: var(--platform-text-secondary);
  margin-bottom: 32px;
}

.error-actions {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
}
</style>
