<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { supplierApi } from '@/api/supplier'
import { purchaseApi } from '@/api/purchase'
import { processApi } from '@/api/process'
import { abnormalApi } from '@/api/abnormal'
import { saleApi } from '@/api/sale'
import { PURCHASE_STATUS } from '@/constants/dict'
import StatusTag from '@/components/StatusTag.vue'
import PageHeader from '@/components/PageHeader.vue'
import type { PurchaseVO } from '@/types/api'

const router = useRouter()
const loading = ref(true)

const stats = ref({
  suppliers: 0,
  purchases: 0,
  pendingAccept: 0,
  processes: 0,
  onSale: 0,
  sales: 0,
  openAbnormal: 0,
})

const recentPurchases = ref<PurchaseVO[]>([])

/**
 * 概览页不发新接口：全部用各模块的分页接口取 total（size=1）。
 *
 * 理由写在论文里也站得住：为一个演示用的数字面板新增统计接口，
 * 会让 SQL 里出现一堆 count(*) 且口径容易和列表页对不上；
 * 直接复用列表接口的 total，页面上看到的数字和点进去看到的条数永远一致。
 * 后端将来真要做统计（趋势图、同比），再单开接口。
 */
async function loadAll() {
  loading.value = true
  const [sup, pur, pend, proc, onSale, sale, abn, recent] = await Promise.allSettled([
    supplierApi.page({ page: 1, size: 1, status: 'ACTIVE' }),
    purchaseApi.page({ page: 1, size: 1 }),
    purchaseApi.page({ page: 1, size: 1, status: 'PENDING_ACCEPT' }),
    processApi.page({ page: 1, size: 1 }),
    processApi.page({ page: 1, size: 1, status: 'ON_SALE' }),
    saleApi.page({ page: 1, size: 1 }),
    abnormalApi.page({ page: 1, size: 1, status: 'OPEN' }),
    purchaseApi.page({ page: 1, size: 6, sort: 'createdAt,desc' }),
  ])

  const total = (r: PromiseSettledResult<{ total: number }>) =>
    r.status === 'fulfilled' ? r.value.total : 0

  stats.value = {
    suppliers: total(sup as never),
    purchases: total(pur as never),
    pendingAccept: total(pend as never),
    processes: total(proc as never),
    onSale: total(onSale as never),
    sales: total(sale as never),
    openAbnormal: total(abn as never),
  }

  if (recent.status === 'fulfilled') {
    recentPurchases.value = recent.value.records
  }
  loading.value = false
}

onMounted(loadAll)

const flowSteps = computed(() => [
  {
    title: '采购入库',
    desc: '登记供应商、检疫证号与到货温度',
    value: stats.value.purchases,
    unit: '个批次',
    path: '/purchases',
    icon: 'ShoppingCart',
    warn: stats.value.pendingAccept > 0 ? `${stats.value.pendingAccept} 个待验收` : '',
  },
  {
    title: '验收存证',
    desc: '感官、票证、留样三项检查并上链',
    value: stats.value.purchases - stats.value.pendingAccept,
    unit: '个已验收',
    path: '/acceptances',
    icon: 'Stamp',
    warn: '',
  },
  {
    title: '加工投料',
    desc: '多来源投料，投入量不超过可用余量',
    value: stats.value.processes,
    unit: '个批次',
    path: '/process',
    icon: 'Cpu',
    warn: '',
  },
  {
    title: '销售追溯',
    desc: '按窗口登记销售，追溯码贴到包装上',
    value: stats.value.onSale,
    unit: '个在售',
    path: '/sales',
    icon: 'Sell',
    warn: stats.value.openAbnormal > 0 ? `${stats.value.openAbnormal} 个异常待处置` : '',
  },
])

const todos = computed(() => {
  const list: { text: string; count: number; path: string; type: 'warning' | 'danger' | 'info' }[] = []
  if (stats.value.pendingAccept > 0) {
    list.push({
      text: '有采购批次到货但未登记验收结果',
      count: stats.value.pendingAccept,
      path: '/acceptances',
      type: 'warning',
    })
  }
  if (stats.value.openAbnormal > 0) {
    list.push({
      text: '有异常工单待给出处置结论',
      count: stats.value.openAbnormal,
      path: '/abnormal',
      type: 'danger',
    })
  }
  if (stats.value.processes === 0) {
    list.push({ text: '尚无加工批次，验收通过后可投料', count: 0, path: '/process', type: 'info' })
  }
  return list
})

function go(path: string) {
  router.push(path)
}

function fmtQty(v: string | null | undefined) {
  return v == null ? '—' : v
}
</script>

<template>
  <div class="page">
    <PageHeader
      title="运行概览"
      desc="食堂肉类从采购到销售的全流程在此汇总。左侧菜单按业务发生顺序排列，可直接进入任一环节。"
    >
      <template #actions>
        <el-button :loading="loading" @click="loadAll">
          <el-icon><Refresh /></el-icon>
          <span style="margin-left: 4px">刷新</span>
        </el-button>
      </template>
    </PageHeader>

    <!-- 指标卡 -->
    <div class="stat-row" v-loading="loading">
      <div class="stat-card" @click="go('/suppliers')">
        <div class="stat-icon"><el-icon><OfficeBuilding /></el-icon></div>
        <div class="stat-body">
          <div class="stat-num mono">{{ stats.suppliers }}</div>
          <div class="stat-label">正常备案供应商</div>
        </div>
      </div>
      <div class="stat-card" @click="go('/purchases')">
        <div class="stat-icon"><el-icon><ShoppingCart /></el-icon></div>
        <div class="stat-body">
          <div class="stat-num mono">{{ stats.purchases }}</div>
          <div class="stat-label">采购批次总数</div>
        </div>
      </div>
      <div class="stat-card" @click="go('/process')">
        <div class="stat-icon"><el-icon><Cpu /></el-icon></div>
        <div class="stat-body">
          <div class="stat-num mono">{{ stats.processes }}</div>
          <div class="stat-label">加工批次总数</div>
        </div>
      </div>
      <div class="stat-card" @click="go('/sales')">
        <div class="stat-icon"><el-icon><Sell /></el-icon></div>
        <div class="stat-body">
          <div class="stat-num mono">{{ stats.sales }}</div>
          <div class="stat-label">销售记录总数</div>
        </div>
      </div>
    </div>

    <!-- 业务链路 -->
    <div class="panel">
      <h3 class="panel-title">业务链路</h3>
      <div class="flow">
        <template v-for="(step, idx) in flowSteps" :key="step.title">
          <div class="flow-step" @click="go(step.path)">
            <div class="step-head">
              <el-icon class="step-icon"><component :is="step.icon" /></el-icon>
              <span class="step-index mono">0{{ idx + 1 }}</span>
            </div>
            <div class="step-title">{{ step.title }}</div>
            <div class="step-desc">{{ step.desc }}</div>
            <div class="step-value">
              <span class="mono">{{ step.value }}</span>
              <span class="unit">{{ step.unit }}</span>
            </div>
            <el-tag v-if="step.warn" type="warning" size="small" effect="light" class="step-warn">
              {{ step.warn }}
            </el-tag>
          </div>
          <div v-if="idx < flowSteps.length - 1" class="flow-arrow">
            <el-icon><Right /></el-icon>
          </div>
        </template>
      </div>
    </div>

    <div class="two-col">
      <!-- 待办 -->
      <div class="panel">
        <h3 class="panel-title">需要注意</h3>
        <div v-if="todos.length === 0" class="empty-tip">
          <el-icon :size="18"><CircleCheck /></el-icon>
          <span>当前没有待处理事项</span>
        </div>
        <ul v-else class="todo-list">
          <li v-for="(todo, i) in todos" :key="i" @click="go(todo.path)">
            <div class="todo-dot" :class="`dot-${todo.type}`" />
            <span class="todo-text">{{ todo.text }}</span>
            <span v-if="todo.count > 0" class="todo-count mono">{{ todo.count }}</span>
            <el-icon class="todo-arrow"><ArrowRight /></el-icon>
          </li>
        </ul>
      </div>

      <!-- 最近采购 -->
      <div class="panel">
        <h3 class="panel-title">最近采购批次</h3>
        <el-table :data="recentPurchases" size="small" :show-header="true" style="width: 100%">
          <el-table-column prop="batchNo" label="批次号" min-width="132">
            <template #default="{ row }">
              <span class="mono">{{ row.batchNo }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="meatType" label="品类" min-width="88" />
          <el-table-column label="状态" width="102">
            <template #default="{ row }">
              <StatusTag :value="row.status" :dict="PURCHASE_STATUS" />
            </template>
          </el-table-column>
          <el-table-column label="可用量" width="92" align="right">
            <template #default="{ row }">
              <span class="mono">{{ fmtQty(row.availableQty) }}</span>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="recentPurchases.length === 0 && !loading" class="empty-tip small">
          <span>还没有采购批次</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.stat-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 14px;
}

.stat-card {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 16px 18px;
  background: #fff;
  border: 1px solid var(--app-border);
  border-radius: 10px;
  cursor: pointer;
  transition: all 0.16s ease;
}

.stat-card:hover {
  border-color: var(--el-color-primary-light-5);
  box-shadow: 0 4px 14px rgba(19, 122, 95, 0.08);
}

.stat-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  flex-shrink: 0;
  border-radius: 10px;
  font-size: 19px;
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.stat-num {
  font-size: 24px;
  font-weight: 600;
  line-height: 1.15;
  color: var(--app-text-main);
}

.stat-label {
  margin-top: 3px;
  font-size: 12.5px;
  color: var(--app-text-sub);
}

/* 链路 */
.flow {
  display: flex;
  align-items: stretch;
  gap: 4px;
}

.flow-step {
  flex: 1;
  min-width: 0;
  padding: 14px 16px;
  border: 1px solid var(--app-border);
  border-radius: 9px;
  background: #fcfdfe;
  cursor: pointer;
  transition: all 0.16s ease;
}

.flow-step:hover {
  background: var(--el-color-primary-light-9);
  border-color: var(--el-color-primary-light-5);
  transform: translateY(-1px);
}

.step-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.step-icon {
  font-size: 17px;
  color: var(--el-color-primary);
}

.step-index {
  font-size: 11px;
  color: #c3cbd6;
  letter-spacing: 1px;
}

.step-title {
  font-size: 14px;
  font-weight: 600;
  margin-bottom: 4px;
}

.step-desc {
  font-size: 12px;
  line-height: 1.6;
  color: var(--app-text-sub);
  min-height: 38px;
}

.step-value {
  margin-top: 6px;
  display: flex;
  align-items: baseline;
  gap: 5px;
}

.step-value .mono {
  font-size: 19px;
  font-weight: 600;
  color: var(--el-color-primary);
}

.step-value .unit {
  font-size: 11.5px;
  color: var(--app-text-sub);
}

.step-warn {
  margin-top: 8px;
}

.flow-arrow {
  display: flex;
  align-items: center;
  color: #cdd5df;
  font-size: 15px;
}

/* 两列 */
.two-col {
  display: grid;
  grid-template-columns: 1fr 1.25fr;
  gap: 14px;
  margin-top: 14px;
}

.todo-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.todo-list li {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 4px;
  border-bottom: 1px dashed #eef1f5;
  cursor: pointer;
  font-size: 13px;
}

.todo-list li:last-child {
  border-bottom: none;
}

.todo-list li:hover {
  color: var(--el-color-primary);
}

.todo-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex-shrink: 0;
}

.dot-warning {
  background: #e6a23c;
}

.dot-danger {
  background: #d92d20;
}

.dot-info {
  background: #8a94a6;
}

.todo-text {
  flex: 1;
}

.todo-count {
  font-weight: 600;
  color: var(--app-text-main);
}

.todo-arrow {
  color: #c3cbd6;
  font-size: 13px;
}

.empty-tip {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 18px 4px;
  font-size: 13px;
  color: var(--app-text-sub);
}

.empty-tip.small {
  padding: 12px 4px;
  font-size: 12.5px;
}
</style>
