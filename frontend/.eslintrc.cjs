/* eslint-env node */
/**
 * ESLint 配置
 * 支持 Vue 3 + Vite + ES Module 项目
 */
module.exports = {
  root: true,
  env: {
    browser: true,
    es2022: true,
    node: true,
  },
  extends: [
    "eslint:recommended",
    "plugin:vue/vue3-recommended",
    "@vue/eslint-config-prettier",
  ],
  parser: "vue-eslint-parser",
  parserOptions: {
    parser: "@typescript-eslint/parser",
    ecmaVersion: "latest",
    sourceType: "module",
  },
  plugins: ["vue"],
  rules: {
    // 关闭或调整部分规则，避免与既有代码风格冲突
    "vue/multi-word-component-names": "off",
    "vue/require-default-prop": "off",
    "vue/no-v-html": "off",
    // 既有代码中存在大量未使用变量、冗余 try/catch 等风格问题，先降级为警告或关闭
    "no-unused-vars": "off",
    "no-useless-catch": "off",
    "no-case-declarations": "off",
  },
  overrides: [
    {
      files: ["src/tests/**/*.js", "src/tests/**/*.ts"],
      env: {
        // 测试文件使用 Vitest 全局变量
        browser: true,
        node: true,
        es2022: true,
      },
      globals: {
        describe: "readonly",
        it: "readonly",
        expect: "readonly",
        vi: "readonly",
        beforeEach: "readonly",
        afterEach: "readonly",
        beforeAll: "readonly",
        afterAll: "readonly",
      },
    },
  ],
  ignorePatterns: [
    "dist",
    "coverage",
    "node_modules",
    "*.config.js",
    "*.config.cjs",
    "vite.config.*",
    "vitest.config.*",
  ],
};
