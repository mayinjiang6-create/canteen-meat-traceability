<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { traceApi } from '@/api/trace'
import { BizError } from '@/api/http'
import { VERIFY_OVERALL } from '@/constants/dict'
import { fmtDateTime, shortHash } from '@/utils/format'
import type { TimelineItem, TraceResponse, VerifyStatus } from '@/types/api'
import VerifyBadge from '@/components/VerifyBadge.vue'

const route = useRoute()
const router = useRouter()

const loading = ref(false)
const trace = ref<TraceResponse | null>(null)
const errorMsg = ref('')
const inputBatchNo = ref('')

const demoBatches = [
  { no: 'PR20261118003', label: '加工批次（多来源投料）' },
  { no: 'PB20261118001', label: '采购批次（链上有真实存证）' },
  { no: 'PR20261118001', label: '加工批次（单来源）' },
]

async function load(batchNo: string) {
  if (!batchNo) return
  loading.value = true
  errorMsg.value = ''
  try {
    trace.value = await traceApi.trace(batchNo)
    schedulePoll()
  } catch (err) {
    trace.value = null
    if (err instanceof BizError && err.code === 40401) {
      errorMsg.value = `没有找到批次「${batchNo}」。请核对标签上的批次号，采购批次以 PB 开头，加工批次以 PR 开头。`
    } else {
      errorMsg.value = err instanceof Error ? err.message : '查询失败'
    }
  } finally {
    loading.value = false
  }
}

function doSearch() {
  const no = inputBatchNo.value.trim()
  if (!no) return
  router.push({ name: 'trace', params: { batchNo: no } })
}

watch(
  () => route.params.batchNo,
  (no) => {
    const target = Array.isArray(no) ? no[0] : no
    inputBatchNo.value = target || ''
    if (target) load(target)
    else {
      trace.value = null
      errorMsg.value = ''
    }
  },
  { immediate: true },
)

/* ---------------------------------------------------------------------------
 * 核验轮询
 * 只有存在「待上链确认」的事件时才轮询——链上存证是异步的，用户刷新页面
 * 其实就是想看"刚刚那笔上去了没有"。全部确认后自动停，不空跑。
 * ------------------------------------------------------------------------- */
let pollTimer: number | null = null
let pollCount = 0

function stopPoll() {
  if (pollTimer != null) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
}

function schedulePoll() {
  stopPoll()
  pollCount = 0
  const unconfirmed = trace.value?.verifySummary?.unconfirmed ?? 0
  if (unconfirmed <= 0) return
  pollTimer = window.setInterval(async () => {
    pollCount += 1
    if (pollCount > 20) {
      stopPoll()
      return
    }
    try {
      const data = await traceApi.verify(trace.value!.batchNo)
      const map = new Map(data.events.map((e) => [e.eventId, e.verifyStatus]))
      if (trace.value) {
        trace.value.timeline.forEach((item) => {
          const s = map.get(item.eventId)
          if (s) item.verifyStatus = s as VerifyStatus
        })
        trace.value.verifySummary = {
          ...trace.value.verifySummary,
          matched: data.counts.matched,
          mismatched: data.counts.mismatched,
          unconfirmed: data.counts.unconfirmed,
          unreachable: data.counts.unreachable,
          overall: data.overall,
        }
      }
      if (data.counts.unconfirmed === 0) stopPoll()
    } catch {
      stopPoll()
    }
  }, 4000)
}

onBeforeUnmount(stopPoll)

/* ------------------------------ 派生数据 ------------------------------ */

const timeline = computed<TimelineItem[]>(() => {
  const list = trace.value?.timeline ? [...trace.value.timeline] : []
  return list.sort((a, b) => (a.bizTime || '').localeCompare(b.bizTime || ''))
})

const overall = computed(() => VERIFY_OVERALL[trace.value?.verifySummary.overall ?? ''] ?? VERIFY_OVERALL.PARTIAL)

const isProcess = computed(() => trace.value?.batchType === 'PROCESS')

/** 四类计数，顺序固定：已核验 / 不一致 / 待确认 / 不可达 */
const verifyCells = computed(() => {
  const s = trace.value?.verifySummary
  if (!s) return []
  return [
    { key: 'matched', value: s.matched, label: '已核验', cls: 'matched' },
    { key: 'mismatched', value: s.mismatched, label: '摘要不一致', cls: 'mismatched' },
    { key: 'unconfirmed', value: s.unconfirmed, label: '待上链确认', cls: 'unconfirmed' },
    { key: 'unreachable', value: s.unreachable, label: '链上不可达', cls: 'unreachable' },
  ]
})

const canCompare = computed(() => (trace.value?.summary.sourceCount ?? 0) > 1)

/** 详情字段的中文标签；后端 payload 的 key 是英文 */
const DETAIL_LABELS: Record<string, string> = {
  inspector: '验收员',
  result: '检验结论',
  temperature: '中心温度',
  acceptNo: '验收单号',
  appearanceOk: '感官检查',
  certComplete: '票证齐全',
  sampleRetained: '已留样',
  rejectReason: '拒收原因',
  processNo: '加工批次号',
  workshop: '加工车间',
  operator: '操作人',
  inputQty: '投入量',
  outputQty: '产出量',
  sourceCount: '来源批次数',
  retainSampleNo: '留样编号',
  saleNo: '销售单号',
  channel: '销售窗口',
  quantity: '数量',
  unitPrice: '单价',
  amount: '金额',
  segment: '运输段',
  transportNo: '运输单号',
  threshold: '温度阈值',
  exceeded: '是否超限',
  recorder: '记录人',
  reason: '召回原因',
  scope: '召回范围',
  affectedSales: '影响销售笔数',
  recalledQty: '召回数量',
  stage: '处置阶段',
  action: '处置措施',
  conclusion: '处置结论',
  level: '异常等级',
  abnormalNo: '异常单号',
}

function detailEntries(item: TimelineItem) {
  if (!item.details) return []
  return Object.entries(item.details)
    .filter(([, v]) => v !== null && v !== undefined && v !== '')
    .map(([k, v]) => ({
      label: DETAIL_LABELS[k] ?? k,
      value: typeof v === 'boolean' ? (v ? '是' : '否') : String(v),
    }))
}

function goUpstream(batchNo: string) {
  router.push({ name: 'trace', params: { batchNo } })
}

const expanded = ref<Set<string>>(new Set())
function toggleExpand(id: string) {
  const next = new Set(expanded.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  expanded.value = next
}

function backToAdmin() {
  router.push('/dashboard')
}
</script>

<template>
  <div class="trace-page">
    <!-- 顶部 -->
    <header class="trace-header">
      <div class="brand" @click="router.push('/trace')">
        <div class="logo"><el-icon :size="18"><Connection /></el-icon></div>
        <div class="brand-text">
          <div class="bn">食堂肉类供应链溯源</div>
          <div class="bs">联盟链存证 · 公开查询</div>
        </div>
      </div>

      <div class="search-box">
        <el-input
          v-model="inputBatchNo"
          placeholder="输入包装上的批次号 / 追溯码，如 PR20261118001"
          size="large"
          clearable
          @keyup.enter="doSearch"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
          <template #append>
            <el-button type="primary" @click="doSearch">查询</el-button>
          </template>
        </el-input>
      </div>

      <el-button text class="back-btn" @click="backToAdmin">
        <el-icon><Setting /></el-icon>
        <span>管理后台</span>
      </el-button>
    </header>

    <main class="trace-main" v-loading="loading">
      <!-- 空态 -->
      <div v-if="!trace && !loading" class="empty-state">
        <template v-if="errorMsg">
          <el-icon :size="42" color="#d99000"><WarningFilled /></el-icon>
          <p class="err">{{ errorMsg }}</p>
        </template>
        <template v-else>
          <el-icon :size="42" color="#b8d7cf"><Document /></el-icon>
          <p>输入批次号即可查看该批次从采购到销售的全链路记录与链上核验结果。</p>
        </template>

        <div class="demo-list">
          <span class="dl-title">演示批次：</span>
          <el-button
            v-for="d in demoBatches"
            :key="d.no"
            size="small"
            plain
            @click="router.push({ name: 'trace', params: { batchNo: d.no } })"
          >
            <span class="mono">{{ d.no }}</span>
            <span class="dl-label">{{ d.label }}</span>
          </el-button>
        </div>
      </div>

      <!-- 结果 -->
      <template v-if="trace">
        <!-- 批次抬头 -->
        <section class="batch-hero">
          <div class="hero-left">
            <div class="hero-tags">
              <el-tag size="small" :type="isProcess ? 'warning' : 'primary'" effect="light" disable-transitions>
                {{ isProcess ? '加工批次' : '采购批次' }}
              </el-tag>
              <el-tag v-if="canCompare" size="small" type="info" effect="plain" disable-transitions>
                {{ trace.summary.sourceCount }} 个上游来源
              </el-tag>
            </div>
            <h1 class="mono">{{ trace.batchNo }}</h1>
            <div class="hero-meta">
              <span class="hm-item">{{ trace.meatType }}</span>
              <span v-if="trace.supplier" class="hm-divider" />
              <span v-if="trace.supplier" class="hm-item">
                {{ trace.supplier.name }}
                <span v-if="trace.supplier.licenseNo" class="lic mono">许可 {{ trace.supplier.licenseNo }}</span>
              </span>
            </div>
          </div>

          <div class="hero-right">
            <div class="overall" :class="`ov-${trace.verifySummary.overall}`">
              <el-icon :size="17">
                <component
                  :is="
                    trace.verifySummary.overall === 'PASS'
                      ? 'CircleCheckFilled'
                      : trace.verifySummary.overall === 'FAIL'
                        ? 'CircleCloseFilled'
                        : 'WarningFilled'
                  "
                />
              </el-icon>
              <span>{{ overall.label }}</span>
            </div>
            <div class="checked-at">核验时间 {{ fmtDateTime(trace.verifySummary.checkedAt) }}</div>
          </div>
        </section>

        <!-- 概览 -->
        <section class="summary-row">
          <div class="sum-card">
            <div class="sc-label">{{ canCompare ? '投入总量' : '采购数量' }}</div>
            <div class="sc-value mono">{{ trace.summary.totalInputQty || '—' }}<span class="sc-unit">kg</span></div>
          </div>
          <div class="sum-card">
            <div class="sc-label">成品产出</div>
            <div class="sc-value mono">{{ trace.summary.outputQty || '—' }}<span class="sc-unit">kg</span></div>
          </div>
          <div class="sum-card">
            <div class="sc-label">已售出</div>
            <div class="sc-value mono">{{ trace.summary.soldQty || '—' }}<span class="sc-unit">kg</span></div>
          </div>
          <div class="sum-card wide">
            <div class="sc-label">检疫合格证号</div>
            <div class="quarantine-list">
              <span v-for="q in trace.summary.quarantineNos" :key="q" class="mono q-no">{{ q }}</span>
              <span v-if="trace.summary.quarantineNos.length === 0" class="text-sub">—</span>
            </div>
          </div>
        </section>

        <!-- ============================================================
             两块必须分开：检验结论 vs 记录核验（BR-15）
             ============================================================ -->
        <section class="split-row">
          <!-- 左：检验结论 -->
          <div class="split-card conclusion">
            <div class="split-head">
              <span class="split-title">检验结论</span>
              <span class="split-sub">这批肉合不合格</span>
            </div>

            <div class="conclusion-body">
              <el-tag
                :type="trace.conclusion.inspectionResult === 'PASS' ? 'success' : 'danger'"
                size="large"
                effect="dark"
                disable-transitions
                class="conclusion-tag"
              >
                {{ trace.conclusion.inspectionResult === 'PASS' ? '检验合格' : trace.conclusion.inspectionResult === 'FAIL' ? '检验不合格' : '暂无结论' }}
              </el-tag>
              <p class="conclusion-note">{{ trace.conclusion.inspectionNote || '该批次暂无检验结论记录' }}</p>
              <div class="conclusion-source">
                结论来源：{{ trace.conclusion.source || '—' }}
              </div>
            </div>

            <div class="split-foot warn">
              <el-icon><InfoFilled /></el-icon>
              <span>结论由验收员依据票证与感官检查给出，区块链不参与判定。</span>
            </div>
          </div>

          <!-- 右：记录核验 -->
          <div class="split-card verify">
            <div class="split-head">
              <span class="split-title">记录核验</span>
              <span class="split-sub">这些记录有没有被改过</span>
            </div>

            <div class="verify-grid">
              <div v-for="cell in verifyCells" :key="cell.key" class="vg-cell" :class="`vg-${cell.cls}`">
                <div class="vg-num mono">{{ cell.value }}</div>
                <div class="vg-label">{{ cell.label }}</div>
              </div>
            </div>

            <div class="verify-note">
              本次共 {{ trace.timeline.length }} 条业务事件，逐条比对链上摘要与本地重算的 SHA-256。
            </div>

            <div class="split-foot">
              <el-icon><Lock /></el-icon>
              <span>核验只回答「记录是否被改动」，不回答「肉是否合格」。</span>
            </div>
          </div>
        </section>

        <!-- 时间线 -->
        <section class="timeline-section">
          <div class="tl-head">
            <h3>全链路记录</h3>
            <span class="tl-sub">按业务发生时间排序，点击上游批次可继续向上追溯</span>
          </div>

          <div class="timeline">
            <div v-for="(item, idx) in timeline" :key="item.eventId" class="tl-node">
              <div class="tl-rail">
                <div class="tl-dot" :class="`dot-${item.verifyStatus}`" />
                <div v-if="idx < timeline.length - 1" class="tl-line" />
              </div>

              <div class="tl-card">
                <div class="tc-top">
                  <span class="tc-label">{{ item.eventLabel }}</span>
                  <VerifyBadge :status="item.verifyStatus" />
                  <span class="tc-time mono">{{ fmtDateTime(item.bizTime) }}</span>
                </div>

                <!-- 上游批次跳转：体现「链路」而不是「列表」 -->
                <div v-if="item.batchNo && item.batchNo !== trace.batchNo" class="tc-upstream">
                  <el-icon><Link /></el-icon>
                  <span>来自上游批次</span>
                  <el-button link type="primary" class="mono" @click="goUpstream(item.batchNo)">
                    {{ item.batchNo }}
                  </el-button>
                  <el-icon class="up-arrow"><ArrowRight /></el-icon>
                </div>

                <div v-if="detailEntries(item).length" class="tc-details">
                  <div v-for="d in detailEntries(item)" :key="d.label" class="tc-detail">
                    <span class="td-label">{{ d.label }}</span>
                    <span class="td-value">{{ d.value }}</span>
                  </div>
                </div>

                <div class="tc-chain">
                  <button class="chain-toggle" @click="toggleExpand(item.eventId)">
                    <el-icon :size="12"><component :is="expanded.has(item.eventId) ? 'ArrowUp' : 'ArrowDown'" /></el-icon>
                    <span>{{ expanded.has(item.eventId) ? '收起' : '链上信息' }}</span>
                  </button>

                  <template v-if="expanded.has(item.eventId)">
                    <div class="chain-detail">
                      <div class="cd-row">
                        <span class="cd-label">事件 ID</span>
                        <span class="cd-value mono">{{ item.eventId }}</span>
                      </div>
                      <div class="cd-row">
                        <span class="cd-label">数据摘要</span>
                        <span class="cd-value mono">{{ item.digest }}</span>
                      </div>
                      <div class="cd-row">
                        <span class="cd-label">交易哈希</span>
                        <span class="cd-value mono">{{ item.txHash || '（尚未上链）' }}</span>
                      </div>
                      <div class="cd-row">
                        <span class="cd-label">区块高度</span>
                        <span class="cd-value mono">{{ item.blockNumber ?? '—' }}</span>
                      </div>
                    </div>
                  </template>
                  <template v-else>
                    <span class="chain-peek mono">
                      {{ shortHash(item.txHash) }}
                      <span v-if="item.blockNumber != null" class="block-tag">#{{ item.blockNumber }}</span>
                    </span>
                  </template>
                </div>
              </div>
            </div>
          </div>

          <div v-if="timeline.length === 0" class="tl-empty">该批次暂无业务事件记录</div>
        </section>

        <!-- 技术说明：答辩时这一块很关键 -->
        <section class="tech-note">
          <h4>这些结论是怎么来的</h4>
          <div class="tn-grid">
            <div class="tn-item">
              <div class="tn-title">
                <el-icon><Files /></el-icon>
                链上存什么
              </div>
              <p>
                每条业务事件生成一条规范串（8 行，含事件 ID、批次号、类型、业务时间、数量、单位、payload），
                计算 SHA-256 后只把<b>摘要</b>写入联盟链。业务明细留在数据库，链上不存隐私数据。
              </p>
            </div>
            <div class="tn-item">
              <div class="tn-title">
                <el-icon><CircleCheck /></el-icon>
                核验怎么做
              </div>
              <p>
                打开本页时，服务端按事件 ID 从链上取回摘要，与本地记录重新计算的摘要逐字节比对。
                一致为「已核验」，不一致为「摘要不一致」，链上还没有为「待上链确认」，链连不上为「链上不可达」。
              </p>
            </div>
            <div class="tn-item">
              <div class="tn-title">
                <el-icon><Warning /></el-icon>
                为什么「链上不可达」单独一类
              </div>
              <p>
                链暂时连不上时，若显示「已核验」是掩盖问题，若显示「不一致」等于诬告数据被篡改。
                所以第四类必须独立存在——<b>查不到 ≠ 被改过</b>。
              </p>
            </div>
          </div>
        </section>
      </template>
    </main>

    <footer class="trace-footer">
      高校食堂肉类供应链溯源系统 · 江西工程学院智慧产业学院 · 基于 FISCO BCOS 联盟链
    </footer>
  </div>
</template>

<style scoped>
.trace-page {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  background: #f2f5f6;
}

/* ---------------- 顶部 ---------------- */
.trace-header {
  display: flex;
  align-items: center;
  gap: 24px;
  padding: 14px 32px;
  background: linear-gradient(100deg, #0e2a23 0%, #16453a 60%, #1a5346 100%);
  color: #fff;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
  flex-shrink: 0;
}

.logo {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 33px;
  height: 33px;
  border-radius: 9px;
  background: rgba(255, 255, 255, 0.12);
  border: 1px solid rgba(255, 255, 255, 0.18);
}

.bn {
  font-size: 14.5px;
  font-weight: 600;
  letter-spacing: 0.6px;
}

.bs {
  font-size: 11px;
  color: rgba(255, 255, 255, 0.5);
  margin-top: 1px;
}

.search-box {
  flex: 1;
  max-width: 620px;
}

.search-box :deep(.el-input__wrapper) {
  border-radius: 8px 0 0 8px;
}

.search-box :deep(.el-input-group__append) {
  border-radius: 0 8px 8px 0;
  overflow: hidden;
}

.back-btn {
  margin-left: auto;
  color: rgba(255, 255, 255, 0.68);
  flex-shrink: 0;
}

.back-btn:hover {
  color: #fff;
}

.back-btn span {
  margin-left: 4px;
  font-size: 12.5px;
}

/* ---------------- 主体 ---------------- */
.trace-main {
  flex: 1;
  width: 100%;
  max-width: 1120px;
  margin: 0 auto;
  padding: 26px 24px 40px;
}

/* 空态 */
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 78px 20px 40px;
  text-align: center;
}

.empty-state p {
  max-width: 520px;
  margin: 16px 0 0;
  font-size: 13.5px;
  line-height: 1.9;
  color: var(--app-text-sub);
}

.empty-state .err {
  color: #b45309;
}

.demo-list {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: 8px;
  margin-top: 30px;
}

.dl-title {
  font-size: 12.5px;
  color: var(--app-text-sub);
}

.dl-label {
  margin-left: 6px;
  font-size: 11.5px;
  color: #97a1af;
}

/* 抬头 */
.batch-hero {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  padding: 22px 26px;
  background: #fff;
  border: 1px solid var(--app-border);
  border-radius: 12px;
  margin-bottom: 14px;
}

.hero-tags {
  display: flex;
  gap: 7px;
  margin-bottom: 10px;
}

.batch-hero h1 {
  margin: 0 0 10px;
  font-size: 26px;
  font-weight: 600;
  letter-spacing: 0.8px;
  color: var(--app-text-main);
}

.hero-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  color: #48576a;
}

.hm-divider {
  width: 1px;
  height: 12px;
  background: #dde3ea;
}

.lic {
  margin-left: 6px;
  font-size: 11.5px;
  color: var(--app-text-sub);
}

.hero-right {
  text-align: right;
  flex-shrink: 0;
}

.overall {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 14px;
  border-radius: 20px;
  font-size: 14px;
  font-weight: 600;
}

.ov-PASS {
  color: #1a9c5b;
  background: #e8f6ee;
  border: 1px solid #bfe5cf;
}

.ov-FAIL {
  color: #d92d20;
  background: #fdecea;
  border: 1px solid #f6c9c4;
}

.ov-PARTIAL {
  color: #b7791f;
  background: #fdf6e8;
  border: 1px solid #f3ddab;
}

.checked-at {
  margin-top: 7px;
  font-size: 11.5px;
  color: #97a1af;
}

/* 概览 */
.summary-row {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr 1.5fr;
  gap: 12px;
  margin-bottom: 14px;
}

.sum-card {
  padding: 14px 16px;
  background: #fff;
  border: 1px solid var(--app-border);
  border-radius: 10px;
}

.sc-label {
  font-size: 12px;
  color: var(--app-text-sub);
  margin-bottom: 7px;
}

.sc-value {
  font-size: 20px;
  font-weight: 600;
  color: var(--app-text-main);
}

.sc-unit {
  margin-left: 3px;
  font-size: 11.5px;
  font-weight: 400;
  color: var(--app-text-sub);
}

.quarantine-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.q-no {
  font-size: 12px;
  color: #48576a;
}

/* 分栏 */
.split-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
  margin-bottom: 14px;
}

.split-card {
  display: flex;
  flex-direction: column;
  padding: 18px 20px 14px;
  background: #fff;
  border: 1px solid var(--app-border);
  border-radius: 12px;
}

/* 左侧用中性灰头，右侧用链的青绿头 —— 视觉上就不该被读成一块 */
.split-head {
  display: flex;
  align-items: baseline;
  gap: 9px;
  padding-bottom: 12px;
  margin-bottom: 14px;
  border-bottom: 1px dashed #e8edf2;
}

.split-title {
  font-size: 15px;
  font-weight: 600;
}

.split-sub {
  font-size: 11.5px;
  color: #97a1af;
}

.conclusion-body {
  flex: 1;
}

.conclusion-tag {
  font-size: 14px;
  padding: 0 18px;
  height: 34px;
  line-height: 34px;
}

.conclusion-note {
  margin: 12px 0 8px;
  font-size: 13px;
  line-height: 1.85;
  color: #48576a;
}

.conclusion-source {
  font-size: 12px;
  color: #97a1af;
}

.verify-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 8px;
}

.vg-cell {
  padding: 11px 8px;
  text-align: center;
  border-radius: 8px;
  border: 1px solid transparent;
}

.vg-num {
  font-size: 21px;
  font-weight: 600;
  line-height: 1.2;
}

.vg-label {
  margin-top: 4px;
  font-size: 11px;
}

.vg-matched {
  color: var(--vc-matched);
  background: #e8f6ee;
  border-color: #bfe5cf;
}

.vg-mismatched {
  color: var(--vc-mismatched);
  background: #fdecea;
  border-color: #f6c9c4;
}

.vg-unconfirmed {
  color: var(--vc-unconfirmed);
  background: #f1f3f6;
  border-color: #dfe3e9;
}

.vg-unreachable {
  color: var(--vc-unreachable);
  background: #fdf4e3;
  border-color: #f3ddab;
}

.verify-note {
  margin-top: 13px;
  font-size: 12px;
  line-height: 1.8;
  color: var(--app-text-sub);
}

.split-foot {
  display: flex;
  align-items: flex-start;
  gap: 5px;
  margin-top: 14px;
  padding-top: 11px;
  border-top: 1px dashed #e8edf2;
  font-size: 11.5px;
  line-height: 1.7;
  color: #8a94a6;
}

.split-foot :deep(.el-icon) {
  margin-top: 2px;
  flex-shrink: 0;
}

.split-foot.warn {
  color: #a08b5f;
}

/* 时间线 */
.timeline-section {
  padding: 20px 22px 8px;
  background: #fff;
  border: 1px solid var(--app-border);
  border-radius: 12px;
  margin-bottom: 14px;
}

.tl-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 18px;
}

.tl-head h3 {
  margin: 0;
  font-size: 15.5px;
  font-weight: 600;
}

.tl-sub {
  font-size: 11.5px;
  color: #97a1af;
}

.tl-node {
  display: flex;
  gap: 14px;
}

.tl-rail {
  position: relative;
  width: 12px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding-top: 5px;
}

.tl-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
  border: 2px solid #fff;
  box-shadow: 0 0 0 1.5px currentColor;
}

.dot-MATCHED {
  color: var(--vc-matched);
  background: var(--vc-matched);
}

.dot-MISMATCHED {
  color: var(--vc-mismatched);
  background: var(--vc-mismatched);
}

.dot-UNCONFIRMED {
  color: var(--vc-unconfirmed);
  background: #fff;
}

.dot-UNREACHABLE {
  color: var(--vc-unreachable);
  background: var(--vc-unreachable);
}

.tl-line {
  flex: 1;
  width: 2px;
  margin: 4px 0;
  min-height: 18px;
  background: linear-gradient(180deg, #dfe6ec 0%, #eef2f6 100%);
}

.tl-card {
  flex: 1;
  min-width: 0;
  padding: 12px 14px;
  margin-bottom: 12px;
  background: #fbfcfd;
  border: 1px solid #e9eef3;
  border-radius: 9px;
}

.tc-top {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.tc-label {
  font-size: 14px;
  font-weight: 600;
  color: var(--app-text-main);
}

.tc-time {
  margin-left: auto;
  font-size: 12px;
  color: #97a1af;
}

.tc-upstream {
  display: flex;
  align-items: center;
  gap: 5px;
  margin-top: 9px;
  padding: 5px 9px;
  border-radius: 6px;
  background: #eef6f3;
  font-size: 12px;
  color: #5b7a6e;
}

.tc-upstream .mono {
  font-size: 12.5px;
  padding: 0;
}

.up-arrow {
  font-size: 11px;
  color: #9db5aa;
}

.tc-details {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 20px;
  margin-top: 9px;
}

.tc-detail {
  display: flex;
  align-items: baseline;
  gap: 6px;
  font-size: 12.5px;
}

.td-label {
  color: #8a94a6;
}

.td-value {
  color: #37475a;
}

.tc-chain {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: 10px;
  padding-top: 9px;
  border-top: 1px dashed #e9eef3;
}

.chain-toggle {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 0;
  font-size: 11.5px;
  color: var(--el-color-primary);
  background: none;
  border: none;
  cursor: pointer;
}

.chain-toggle:hover {
  text-decoration: underline;
}

.chain-peek {
  font-size: 11.5px;
  color: #a8b1bd;
}

.block-tag {
  margin-left: 8px;
  padding: 0 5px;
  border-radius: 3px;
  background: #f0f3f6;
  color: #7c8798;
}

.chain-detail {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: 6px;
  padding: 9px 11px;
  border-radius: 7px;
  background: #f5f8fa;
}

.cd-row {
  display: flex;
  gap: 10px;
  font-size: 11.5px;
  line-height: 1.7;
}

.cd-label {
  width: 62px;
  flex-shrink: 0;
  color: #8a94a6;
}

.cd-value {
  color: #37475a;
  word-break: break-all;
}

.tl-empty {
  padding: 30px 0;
  text-align: center;
  font-size: 13px;
  color: #97a1af;
}

/* 技术说明 */
.tech-note {
  padding: 20px 22px 22px;
  background: linear-gradient(180deg, #ffffff 0%, #fafcfb 100%);
  border: 1px solid var(--app-border);
  border-radius: 12px;
}

.tech-note h4 {
  margin: 0 0 16px;
  font-size: 14.5px;
  font-weight: 600;
  padding-left: 9px;
  border-left: 3px solid var(--el-color-primary);
}

.tn-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 18px;
}

.tn-title {
  display: flex;
  align-items: center;
  gap: 5px;
  margin-bottom: 6px;
  font-size: 13px;
  font-weight: 600;
  color: #37475a;
}

.tn-title :deep(.el-icon) {
  color: var(--el-color-primary);
  font-size: 14px;
}

.tn-item p {
  margin: 0;
  font-size: 12px;
  line-height: 1.9;
  color: var(--app-text-sub);
}

.tn-item b {
  color: #37475a;
}

/* 页脚 */
.trace-footer {
  padding: 16px 24px 22px;
  text-align: center;
  font-size: 11.5px;
  color: #a8b1bd;
}

@media (max-width: 900px) {
  .split-row,
  .tn-grid,
  .summary-row {
    grid-template-columns: 1fr;
  }

  .trace-header {
    flex-wrap: wrap;
    gap: 12px;
    padding: 12px 16px;
  }

  .search-box {
    order: 3;
    max-width: none;
    flex-basis: 100%;
  }

  .batch-hero {
    flex-direction: column;
  }

  .hero-right {
    text-align: left;
  }
}
</style>
