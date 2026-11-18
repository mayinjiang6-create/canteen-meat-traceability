<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { processApi, type ProcessQuery } from '@/api/process'
import { purchaseApi } from '@/api/purchase'
import { errText } from '@/api/http'
import { MEAT_TYPES, PROCESS_STATUS, WORKSHOPS } from '@/constants/dict'
import { fmtDateTime, nowText, toQty } from '@/utils/format'
import { useAuthStore } from '@/stores/auth'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import type { ProcessCreateRequest, ProcessVO, PurchaseVO } from '@/types/api'

const auth = useAuthStore()
const router = useRouter()

const loading = ref(false)
const rows = ref<ProcessVO[]>([])
const total = ref(0)
const query = reactive<ProcessQuery>({ page: 1, size: 20, processNo: '', status: '' })

async function load() {
  loading.value = true
  try {
    const data = await processApi.page({ ...query })
    rows.value = data.records
    total.value = data.total
  } catch {
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

/** 可投料的采购批次：验收通过且还有余量。已用尽的（CLOSED）不可再投 */
const availableBatches = ref<PurchaseVO[]>([])

async function loadAvailable() {
  try {
    const data = await purchaseApi.page({ page: 1, size: 100, status: 'PASSED' })
    availableBatches.value = data.records.filter((b) => Number(b.availableQty) > 0)
  } catch {
    availableBatches.value = []
  }
}

onMounted(() => {
  load()
  loadAvailable()
})

function search() {
  query.page = 1
  load()
}

function reset() {
  query.processNo = ''
  query.status = ''
  query.page = 1
  load()
}

/* ------------------------------ 详情 ------------------------------ */

const detailVisible = ref(false)
const detail = ref<ProcessVO | null>(null)
const detailLoading = ref(false)

async function openDetail(row: ProcessVO) {
  detailVisible.value = true
  detailLoading.value = true
  detail.value = row
  try {
    detail.value = await processApi.detail(row.id)
  } catch {
    /* 失败时退回列表里已有的数据（无来源明细） */
  } finally {
    detailLoading.value = false
  }
}

function gotoTrace(processNo?: string | null) {
  if (!processNo) return
  const url = router.resolve({ name: 'trace', params: { batchNo: processNo } }).href
  window.open(url, '_blank', 'noopener')
}

/* ------------------------------ 上架 ------------------------------ */

async function onSale(row: ProcessVO) {
  try {
    await ElMessageBox.confirm(
      `确认将加工批次 ${row.processNo} 上架销售？上架后窗口即可登记销售，上架本身不产生链上事件。`,
      '上架确认',
      { confirmButtonText: '上架', cancelButtonText: '取消', type: 'info' },
    )
  } catch {
    return
  }
  try {
    await processApi.onSale(row.id)
    ElMessage.success('已上架，可在销售登记中选择该批次')
    load()
  } catch (err) {
    ElMessage.error(errText(err))
  }
}

/* ------------------------------ 投料 ------------------------------ */

interface SourceRow {
  purchaseBatchNo: string
  usedQty: string
}

const createVisible = ref(false)
const submitting = ref(false)
const formRef = ref<FormInstance>()

const form = reactive({
  meatType: '冷鲜猪肉',
  processTime: nowText(),
  workshop: '一食堂后厨',
  operator: '',
  retainSampleNo: '',
  outputQty: '',
  sources: [{ purchaseBatchNo: '', usedQty: '' }] as SourceRow[],
})

function openCreate() {
  form.meatType = '冷鲜猪肉'
  form.processTime = nowText()
  form.workshop = '一食堂后厨'
  form.operator = auth.displayName
  form.retainSampleNo = ''
  form.outputQty = ''
  form.sources = [{ purchaseBatchNo: '', usedQty: '' }]
  if (availableBatches.value.length === 0) loadAvailable()
  createVisible.value = true
}

function addSource() {
  if (form.sources.length >= 10) {
    ElMessage.warning('单次投料最多 10 个来源批次')
    return
  }
  form.sources.push({ purchaseBatchNo: '', usedQty: '' })
}

function removeSource(index: number) {
  if (form.sources.length === 1) {
    ElMessage.warning('至少保留一个来源批次')
    return
  }
  form.sources.splice(index, 1)
}

function batchOf(no: string): PurchaseVO | undefined {
  return availableBatches.value.find((b) => b.batchNo === no)
}

/** 已选过的批次（用于禁选，避免重复引用 → 40904） */
function isPicked(no: string, currentIndex: number): boolean {
  return form.sources.some((s, i) => i !== currentIndex && s.purchaseBatchNo === no)
}

/** Σ投入量：同样是字符串定点加法，不走浮点 */
const totalInput = computed(() => {
  let sumCents = 0
  for (const s of form.sources) {
    const normalized = toQty(s.usedQty, 3)
    if (!normalized) continue
    sumCents += Math.round(Number(normalized) * 1000)
  }
  const intPart = Math.floor(sumCents / 1000)
  const frac = String(sumCents % 1000).padStart(3, '0')
  return `${intPart}.${frac}`
})

const outputNum = computed(() => Number(toQty(form.outputQty, 3) || 0))
const inputNum = computed(() => Number(totalInput.value))

/** 产出 > 投入 是硬错误（BR-04）：肉不会凭空变多 */
const outputExceeds = computed(() => outputNum.value > 0 && outputNum.value > inputNum.value)

const yieldRate = computed(() => {
  if (inputNum.value <= 0 || outputNum.value <= 0) return null
  return ((outputNum.value / inputNum.value) * 100).toFixed(1)
})

const rules: FormRules = {
  meatType: [{ required: true, message: '请填写品类', trigger: 'change' }],
  outputQty: [
    { required: true, message: '请填写产出量', trigger: 'blur' },
    {
      validator: (_r, value, cb) => {
        if (!value) return cb()
        if (!/^\d{1,9}(\.\d{1,3})?$/.test(String(value))) {
          return cb(new Error('产出量最多 9 位整数 + 3 位小数'))
        }
        if (Number(value) <= 0) return cb(new Error('产出量必须大于 0'))
        cb()
      },
      trigger: 'blur',
    },
  ],
}

async function submit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  const sources = form.sources.filter((s) => s.purchaseBatchNo && s.usedQty)
  if (sources.length === 0) {
    ElMessage.warning('请至少填写一个来源批次的投入量')
    return
  }
  if (outputExceeds.value) {
    ElMessage.error('产出量不能大于投入总量——加工只会减少重量（损耗、去骨、缩水）')
    return
  }

  submitting.value = true
  try {
    const payload: ProcessCreateRequest = {
      meatType: form.meatType,
      processTime: form.processTime || undefined,
      workshop: form.workshop || undefined,
      operator: form.operator || undefined,
      retainSampleNo: form.retainSampleNo || undefined,
      outputQty: toQty(form.outputQty, 3),
      sources: sources.map((s) => ({
        purchaseBatchNo: s.purchaseBatchNo,
        usedQty: toQty(s.usedQty, 3),
      })),
    }
    const created = await processApi.create(payload)
    ElMessage.success(`加工批次 ${created.processNo} 已创建，投入 ${created.inputQty} kg`)
    createVisible.value = false
    load()
    loadAvailable()
  } catch (err) {
    ElMessage.error(errText(err))
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="page">
    <PageHeader
      title="加工投料"
      desc="一个加工批次可同时消耗多个采购批次（例如猪肉丸用两个来源的冷鲜肉）。投入量按来源逐条扣减可用余量，产出量不得超过投入总量。"
    >
      <template #actions>
        <el-button v-if="auth.isAdmin" type="primary" @click="openCreate">
          <el-icon><Plus /></el-icon>
          <span style="margin-left: 4px">新建加工批次</span>
        </el-button>
      </template>
    </PageHeader>

    <div class="panel">
      <div class="toolbar">
        <el-input
          v-model="query.processNo"
          placeholder="追溯码，如 PR20261118001"
          clearable
          style="width: 220px"
          @keyup.enter="search"
          @clear="search"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 140px" @change="search">
          <el-option v-for="(item, key) in PROCESS_STATUS" :key="key" :label="item.label" :value="key" />
        </el-select>
        <el-button type="primary" plain @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
        <div class="grow" />
        <span class="text-sub" style="font-size: 12.5px">共 {{ total }} 条</span>
      </div>

      <el-table v-loading="loading" :data="rows" border stripe empty-text="暂无加工批次">
        <el-table-column label="追溯码" width="148" fixed="left">
          <template #default="{ row }">
            <el-button link type="primary" class="mono" @click="openDetail(row)">{{ row.processNo }}</el-button>
          </template>
        </el-table-column>
        <el-table-column prop="meatType" label="品类 / 成品名" min-width="150" show-overflow-tooltip />
        <el-table-column label="投入量" width="96" align="right">
          <template #default="{ row }"><span class="mono">{{ row.inputQty || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="来源数" width="82" align="center">
          <template #default="{ row }">
            <el-tag v-if="(row.sourceCount ?? 0) > 1" type="warning" size="small" effect="plain">
              {{ row.sourceCount }} 来源
            </el-tag>
            <span v-else class="mono">{{ row.sourceCount ?? 1 }}</span>
          </template>
        </el-table-column>
        <el-table-column label="产出量" width="96" align="right">
          <template #default="{ row }"><span class="mono">{{ row.outputQty }}</span></template>
        </el-table-column>
        <el-table-column label="可售余量" width="100" align="right">
          <template #default="{ row }"><span class="mono">{{ row.availableQty }}</span></template>
        </el-table-column>
        <el-table-column prop="workshop" label="加工车间" width="118" />
        <el-table-column prop="operator" label="操作人" width="96" />
        <el-table-column prop="retainSampleNo" label="留样编号" width="130">
          <template #default="{ row }"><span class="mono">{{ row.retainSampleNo || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="加工时间" width="158">
          <template #default="{ row }"><span class="mono text-sub">{{ fmtDateTime(row.processTime) }}</span></template>
        </el-table-column>
        <el-table-column label="状态" width="106" align="center">
          <template #default="{ row }"><StatusTag :value="row.status" :dict="PROCESS_STATUS" /></template>
        </el-table-column>
        <el-table-column label="操作" width="164" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
            <el-button
              v-if="auth.isAdmin && row.status === 'CREATED'"
              link
              type="success"
              size="small"
              @click="onSale(row)"
            >
              上架
            </el-button>
            <el-button link type="primary" size="small" @click="gotoTrace(row.processNo)">溯源</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="load"
          @size-change="search"
        />
      </div>
    </div>

    <!-- 详情 -->
    <el-drawer v-model="detailVisible" title="加工批次详情" size="640px">
      <div v-loading="detailLoading">
        <template v-if="detail">
          <el-descriptions :column="2" border size="default">
            <el-descriptions-item label="追溯码" :span="2">
              <span class="mono">{{ detail.processNo }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="品类">{{ detail.meatType }}</el-descriptions-item>
            <el-descriptions-item label="状态">
              <StatusTag :value="detail.status" :dict="PROCESS_STATUS" />
            </el-descriptions-item>
            <el-descriptions-item label="投入总量">
              <span class="mono">{{ detail.inputQty }} {{ detail.unit }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="产出量">
              <span class="mono">{{ detail.outputQty }} {{ detail.unit }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="可售余量">
              <span class="mono">{{ detail.availableQty }} {{ detail.unit }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="来源批次数">{{ detail.sourceCount }}</el-descriptions-item>
            <el-descriptions-item label="加工车间">{{ detail.workshop || '—' }}</el-descriptions-item>
            <el-descriptions-item label="操作人">{{ detail.operator || '—' }}</el-descriptions-item>
            <el-descriptions-item label="留样编号">
              <span class="mono">{{ detail.retainSampleNo || '—' }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="加工时间">
              <span class="mono">{{ fmtDateTime(detail.processTime) }}</span>
            </el-descriptions-item>
          </el-descriptions>

          <h4 class="sub-title">来源明细</h4>
          <el-table :data="detail.sources ?? []" border size="small" empty-text="无来源记录">
            <el-table-column label="上游采购批次" width="148">
              <template #default="{ row }"><span class="mono">{{ row.purchaseBatchNo }}</span></template>
            </el-table-column>
            <el-table-column label="供应商" min-width="150" show-overflow-tooltip>
              <template #default="{ row }">{{ row.supplierName || '—' }}</template>
            </el-table-column>
            <el-table-column label="投入量" width="100" align="right">
              <template #default="{ row }"><span class="mono">{{ row.usedQty }}</span></template>
            </el-table-column>
            <el-table-column label="扣减后余量" width="112" align="right">
              <template #default="{ row }"><span class="mono">{{ row.remainQty }}</span></template>
            </el-table-column>
          </el-table>

          <el-button type="primary" plain style="margin-top: 18px; width: 100%" @click="gotoTrace(detail?.processNo)">
            <el-icon><View /></el-icon>
            <span style="margin-left: 5px">查看该批次公开溯源页</span>
          </el-button>
        </template>
      </div>
    </el-drawer>

    <!-- 新建投料 -->
    <el-dialog v-model="createVisible" title="新建加工批次（多来源投料）" width="760px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="104px">
        <div class="form-grid">
          <el-form-item label="成品品类" prop="meatType">
            <el-select v-model="form.meatType" filterable allow-create default-first-option style="width: 100%">
              <el-option v-for="t in MEAT_TYPES" :key="t" :label="t" :value="t" />
            </el-select>
          </el-form-item>
          <el-form-item label="加工车间">
            <el-select v-model="form.workshop" filterable allow-create style="width: 100%">
              <el-option v-for="w in WORKSHOPS" :key="w" :label="w" :value="w" />
            </el-select>
          </el-form-item>
          <el-form-item label="操作人">
            <el-input v-model="form.operator" maxlength="50" />
          </el-form-item>
          <el-form-item label="留样编号">
            <el-input v-model="form.retainSampleNo" maxlength="40" placeholder="如 LC20261118001" />
          </el-form-item>
          <el-form-item label="加工时间">
            <el-date-picker
              v-model="form.processTime"
              type="datetime"
              value-format="YYYY-MM-DD HH:mm:ss"
              style="width: 100%"
            />
          </el-form-item>
          <el-form-item label="产出量" prop="outputQty">
            <el-input v-model="form.outputQty" placeholder="如 100" style="width: 100%">
              <template #append>kg</template>
            </el-input>
          </el-form-item>
        </div>

        <div class="source-block">
          <div class="source-head">
            <span class="st-title">来源批次与投入量</span>
            <el-button size="small" type="primary" plain @click="addSource">
              <el-icon><Plus /></el-icon>
              <span style="margin-left: 3px">增加来源</span>
            </el-button>
          </div>

          <div v-if="availableBatches.length === 0" class="hint warn">
            没有可投料的采购批次（需要先「验收通过」且尚有余量）。
          </div>

          <div v-for="(src, idx) in form.sources" :key="idx" class="source-row">
            <span class="row-index mono">{{ idx + 1 }}</span>
            <el-select v-model="src.purchaseBatchNo" placeholder="选择上游采购批次" filterable class="row-batch">
              <el-option
                v-for="b in availableBatches"
                :key="b.id"
                :label="`${b.batchNo} · ${b.meatType} · 余 ${b.availableQty}${b.unit}`"
                :value="b.batchNo"
                :disabled="isPicked(b.batchNo, idx)"
              />
            </el-select>
            <el-input v-model="src.usedQty" placeholder="投入量" class="row-qty">
              <template #append>kg</template>
            </el-input>
            <span class="row-remain text-sub">
              <template v-if="batchOf(src.purchaseBatchNo)">
                可用 {{ batchOf(src.purchaseBatchNo)!.availableQty }}
              </template>
            </span>
            <el-button link type="danger" size="small" @click="removeSource(idx)">
              <el-icon><Delete /></el-icon>
            </el-button>
          </div>

          <!-- 实时汇总：让用户在提交前就看到数量是否自洽 -->
          <div class="calc-bar" :class="{ danger: outputExceeds }">
            <div class="calc-item">
              <span class="cl">Σ 投入量</span>
              <span class="cv mono">{{ totalInput }}</span>
              <span class="cu">kg</span>
            </div>
            <el-icon class="calc-arrow"><Right /></el-icon>
            <div class="calc-item">
              <span class="cl">产出量</span>
              <span class="cv mono">{{ toQty(form.outputQty, 3) || '0.000' }}</span>
              <span class="cu">kg</span>
            </div>
            <div class="calc-item" v-if="yieldRate">
              <span class="cl">出品率</span>
              <span class="cv mono">{{ yieldRate }}</span>
              <span class="cu">%</span>
            </div>
            <div class="calc-msg" v-if="outputExceeds">
              <el-icon><WarningFilled /></el-icon>
              产出量大于投入总量，请检查
            </div>
            <div class="calc-msg ok" v-else-if="inputNum > 0 && outputNum > 0">
              <el-icon><CircleCheck /></el-icon>
              数量关系合法
            </div>
          </div>
        </div>

        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="提交后按来源批次号升序扣减余量"
          description="扣减使用条件 UPDATE（WHERE available_qty >= ?），并发下不会卖超。余量扣到 0 时该采购批次自动结案为「已用尽」。"
        />
      </el-form>

      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">提交投料</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.sub-title {
  margin: 20px 0 10px;
  font-size: 14px;
  font-weight: 600;
  padding-left: 9px;
  border-left: 3px solid var(--el-color-primary);
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 18px;
}

.source-block {
  margin: 6px 0 16px;
  padding: 14px 16px;
  background: #f9fbfc;
  border: 1px solid var(--app-border);
  border-radius: 9px;
}

.source-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}

.st-title {
  font-size: 13.5px;
  font-weight: 600;
}

.source-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.row-index {
  width: 18px;
  flex-shrink: 0;
  font-size: 12px;
  color: #a8b1bd;
  text-align: center;
}

.row-batch {
  flex: 1;
  min-width: 0;
}

.row-qty {
  width: 160px;
  flex-shrink: 0;
}

.row-remain {
  width: 92px;
  flex-shrink: 0;
  font-size: 11.5px;
}

.calc-bar {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-top: 10px;
  padding: 9px 12px;
  border-radius: 8px;
  background: #eef6f3;
  border: 1px solid #d3e8e1;
}

.calc-bar.danger {
  background: #fdecea;
  border-color: #f6c9c4;
}

.calc-item {
  display: flex;
  align-items: baseline;
  gap: 4px;
}

.cl {
  font-size: 12px;
  color: var(--app-text-sub);
}

.cv {
  font-size: 15px;
  font-weight: 600;
  color: var(--app-text-main);
}

.cu {
  font-size: 11px;
  color: var(--app-text-sub);
}

.calc-arrow {
  color: #b5c1cc;
  font-size: 13px;
}

.calc-msg {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-left: auto;
  font-size: 12.5px;
  font-weight: 500;
  color: #d92d20;
}

.calc-msg.ok {
  color: #1a9c5b;
}

.hint {
  margin: 4px 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--app-text-sub);
}

.hint.warn {
  color: #d97706;
}
</style>
