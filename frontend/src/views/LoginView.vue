<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { errText } from '@/api/http'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const formRef = ref<FormInstance>()
const loading = ref(false)

const form = reactive({ username: '', password: '' })

const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入口令', trigger: 'blur' }],
}

/** 演示账号：答辩现场一个一个字母敲太浪费时间，点一下直接填 */
const demoAccounts = [
  { username: 'admin', password: 'Admin@123', role: '管理员', note: '全部写权限' },
  { username: 'inspector01', password: 'Inspector@123', role: '验收员', note: '同为 ADMIN 角色' },
  { username: 'supplier01', password: 'Supplier@123', role: '供应商', note: '只读 + 冷链登记' },
  { username: 'viewer01', password: 'Viewer@123', role: '只读', note: '看板与查询' },
]

function fill(account: (typeof demoAccounts)[number]) {
  form.username = account.username
  form.password = account.password
}

async function submit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    await auth.login(form.username, form.password)
    ElMessage.success(`欢迎回来，${auth.displayName}`)
    const redirect = (route.query.redirect as string) || '/dashboard'
    router.replace(redirect)
  } catch (err) {
    ElMessage.error(errText(err, '登录失败'))
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <!-- 左：品牌区 -->
    <section class="brand-side">
      <div class="grid-deco" />
      <div class="brand-inner">
        <div class="brand-row">
          <div class="brand-logo">
            <el-icon :size="22"><Connection /></el-icon>
          </div>
          <span class="brand-name">食堂肉类供应链溯源</span>
        </div>

        <h1>
          每一批肉，<br />
          从屠宰场到餐盘都可核验
        </h1>
        <p class="lead">
          采购、验收、加工、销售四段业务全程上链存证。任何人拿到批次号，
          都能自行核对记录是否被改动过。
        </p>

        <ul class="feature-list">
          <li>
            <el-icon><Lock /></el-icon>
            <div>
              <strong>联盟链存证</strong>
              <span>FISCO BCOS 3.17 四节点，事件摘要上链，链上只存哈希不存业务数据</span>
            </div>
          </li>
          <li>
            <el-icon><CircleCheck /></el-icon>
            <div>
              <strong>四类核验状态</strong>
              <span>已核验 / 不一致 / 待确认 / 链不可达，绝不把「查不到」说成「造假」</span>
            </div>
          </li>
          <li>
            <el-icon><Share /></el-icon>
            <div>
              <strong>全链路可回溯</strong>
              <span>加工批次可反查全部上游来源，销售记录可定位到具体窗口</span>
            </div>
          </li>
        </ul>

        <div class="brand-foot">
          江西工程学院 · 智慧产业学院 · 区块链工程 · 2027 届毕业设计
        </div>
      </div>
    </section>

    <!-- 右：登录表单 -->
    <section class="form-side">
      <div class="form-box">
        <h2>登录管理后台</h2>
        <p class="form-sub">请使用食堂管理员或后勤处账号登录</p>

        <el-form ref="formRef" :model="form" :rules="rules" size="large" @submit.prevent="submit">
          <el-form-item prop="username">
            <el-input v-model="form.username" placeholder="用户名" clearable :prefix-icon="'User'" />
          </el-form-item>
          <el-form-item prop="password">
            <el-input
              v-model="form.password"
              type="password"
              placeholder="登录口令"
              show-password
              :prefix-icon="'Lock'"
              @keyup.enter="submit"
            />
          </el-form-item>
          <el-button type="primary" class="submit-btn" :loading="loading" @click="submit">
            登 录
          </el-button>
        </el-form>

        <el-divider class="divider">演示账号（点击填充）</el-divider>

        <div class="demo-grid">
          <button v-for="acc in demoAccounts" :key="acc.username" class="demo-card" @click="fill(acc)">
            <span class="acc-role">{{ acc.role }}</span>
            <span class="acc-name mono">{{ acc.username }}</span>
            <span class="acc-note">{{ acc.note }}</span>
          </button>
        </div>

        <div class="trace-entry">
          <el-icon><View /></el-icon>
          <span>不需要登录？</span>
          <router-link to="/trace">直接进入公开溯源查询 →</router-link>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.login-page {
  display: flex;
  height: 100vh;
  overflow: hidden;
}

/* ---------------- 左侧品牌区 ---------------- */
.brand-side {
  position: relative;
  flex: 1.15;
  display: flex;
  align-items: center;
  padding: 0 64px;
  overflow: hidden;
  color: #eaf6f1;
  background: linear-gradient(150deg, #0b241e 0%, #123c31 55%, #175243 100%);
}

/* 背景网格：暗示"链"/"结构"，比纯色有信息量 */
.grid-deco {
  position: absolute;
  inset: 0;
  background-image: linear-gradient(rgba(255, 255, 255, 0.045) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.045) 1px, transparent 1px);
  background-size: 42px 42px;
  mask-image: radial-gradient(ellipse at 30% 40%, #000 30%, transparent 78%);
  pointer-events: none;
}

.brand-inner {
  position: relative;
  z-index: 1;
  max-width: 520px;
}

.brand-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 34px;
}

.brand-logo {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.16);
}

.brand-name {
  font-size: 15px;
  letter-spacing: 1px;
  color: rgba(255, 255, 255, 0.82);
}

h1 {
  margin: 0 0 18px;
  font-size: 34px;
  line-height: 1.42;
  font-weight: 600;
  letter-spacing: 0.5px;
  color: #ffffff;
}

.lead {
  margin: 0 0 36px;
  font-size: 14px;
  line-height: 1.9;
  color: rgba(255, 255, 255, 0.62);
}

.feature-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.feature-list li {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}

.feature-list .el-icon {
  margin-top: 2px;
  color: #7fe0b8;
  flex-shrink: 0;
}

.feature-list strong {
  display: block;
  margin-bottom: 3px;
  font-size: 14px;
  font-weight: 600;
  color: rgba(255, 255, 255, 0.92);
}

.feature-list span {
  font-size: 12.5px;
  line-height: 1.75;
  color: rgba(255, 255, 255, 0.5);
}

.brand-foot {
  margin-top: 46px;
  font-size: 12px;
  letter-spacing: 0.5px;
  color: rgba(255, 255, 255, 0.32);
}

/* ---------------- 右侧表单 ---------------- */
.form-side {
  width: 480px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #fff;
}

.form-box {
  width: 340px;
}

.form-box h2 {
  margin: 0 0 8px;
  font-size: 22px;
  font-weight: 600;
}

.form-sub {
  margin: 0 0 26px;
  font-size: 13px;
  color: var(--app-text-sub);
}

.submit-btn {
  width: 100%;
  margin-top: 4px;
  letter-spacing: 4px;
}

.divider {
  margin: 26px 0 16px;
}

.divider :deep(.el-divider__text) {
  font-size: 12px;
  color: var(--app-text-sub);
  background: #fff;
}

.demo-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

.demo-card {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 10px;
  text-align: left;
  cursor: pointer;
  background: #f8fafb;
  border: 1px solid var(--app-border);
  border-radius: 8px;
  transition: all 0.15s ease;
}

.demo-card:hover {
  background: var(--el-color-primary-light-9);
  border-color: var(--el-color-primary-light-5);
}

.acc-role {
  font-size: 12px;
  font-weight: 600;
  color: var(--el-color-primary);
}

.acc-name {
  font-size: 11.5px;
  color: #48576a;
}

.acc-note {
  font-size: 11px;
  color: #97a1af;
}

.trace-entry {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 26px;
  font-size: 12.5px;
  color: var(--app-text-sub);
}

.trace-entry a {
  color: var(--el-color-primary);
  text-decoration: none;
}

.trace-entry a:hover {
  text-decoration: underline;
}

@media (max-width: 960px) {
  .brand-side {
    display: none;
  }
  .form-side {
    width: 100%;
  }
}
</style>
