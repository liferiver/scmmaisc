<script setup lang="ts">
// 应用外壳：统一页头 + 路由出口（宪法 III 一致性）
</script>

<template>
  <el-container class="app-shell">
    <el-header class="app-header">
      <div class="app-header-inner">
        <router-link to="/" class="brand">电商物流与供应链参数化模拟平台</router-link>
        <nav class="app-nav">
          <router-link to="/">场景目录</router-link>
          <router-link to="/compare">方案对比</router-link>
        </nav>
      </div>
    </el-header>
    <el-main class="app-main">
      <router-view />
    </el-main>
  </el-container>
</template>

<style scoped>
.app-shell {
  min-height: 100vh;
}
.app-header {
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  padding: 0;
}
.app-header-inner {
  max-width: 1200px;
  margin: 0 auto;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
}
.brand {
  font-size: 18px;
  font-weight: 600;
  color: #303133;
  text-decoration: none;
}
.app-nav {
  display: flex;
  gap: 20px;
}
.app-nav a {
  color: #606266;
  text-decoration: none;
}
.app-nav a.router-link-active {
  color: #409eff;
  font-weight: 600;
}
.app-main {
  max-width: 1200px;
  width: 100%;
  margin: 0 auto;
  padding: 24px 16px;
}
</style>
