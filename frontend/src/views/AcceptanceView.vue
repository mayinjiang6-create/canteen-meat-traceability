<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { acceptanceApi, type AcceptanceQuery } from '@/api/acceptance'
import { purchaseApi } from '@/api/purchase'
import { errText } from '@/api/http'
import { fmtDateTime, nowText, toMoney } from '@/utils/format'
import { useAuthStore } from '@/stores/auth'
import PageHeader from '@/components/PageHeader.vue'
import type { AcceptanceVO, PurchaseVO } from '@/types/api'

const auth = useAuthStore()

const loading = ref(false)
const rows = ref<AcceptanceVO[]>([])
const total = ref(0)
const query = reactive<AcceptanceQuery>({ page: 1, size: 20, batchNo: '', result: '' })

async function load() {
  loading.value = true
  try {
    const data = await acceptanceApi.page({ ...query })
    rows.value = data.records
    total.value = data.total
  } catch {
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

/** 待验收批次：只有 PENDING_ACCEPT 的采购批次能被验收 */
const pendingBatches = ref<PurchaseVO[]>([])

async function loadPending() {
  try {
    const data = await purchaseApi.page({ page: 1, size: 100, status: 'PENDING_ACCEPT' })
    pendingBatches.value = data.records
  } catch {
    pendingBatches.value = []
  }
}

onMounted(() => {
  load()
  loadPending()
})

function search() {
  query.page = 1
  load()
}

function reset() {
  query.batchNo = ''
  query.result = ''
  query.page = 1
  load()
}

/* ------------------------------ 验收登记 ------------------------------ */

const dialogVisible = ref(false)
const submitting = ref(false)
const formRef = ref<FormInstance>()

const blank = () => ({
  batchId: undefined as number | undefined,
  acceptTime: nowText(),
  inspector: auth.displayName,
  temperature: '',
  appearanceOk: true,
  certComplete: true,
  sampleRetained: true,
  result: 'PASS' as 'PASS' | 'FAIL',
  rejectReason: '',
})

const form = reactive(blank())

const rules: FormRules = {
  batchId: [{ required: true, message: '请选择待验收的采购批次', trigger: 'change' }],
  inspector: [{ required: true, message: '请填写验收员', trigger: 'blur' }],
  result: [{ required: true, message: '请选择验收结论', trigger: 'change' }],
  rejectReason: [
    {
      validator: (_r, value, cb) => {
        if (form.result === 'FAIL' && !value) {
          return cb(new Error('拒收必须填写原因，这是后续追溯与追责的依据'))
        }
        cb()
      },
      trigger: 'blur',
    },
  ],
}

// 结论切到 PASS 时清掉拒收原因，避免残留脏数据提交上去
watch(
  () => form.result,
  (v) => {
    if (v === 'PASS') form.rejectReason = ''
  },
)

function openCreate(batch?: PurchaseVO) {
  Object.assign(form, blank())
  form.batchId = batch?.id
  if (batch?.coldChainTemp) form.temperature = batch.coldChainTemp
  if (pendingBatches.value.length === 0) loadPending()
  dialogVisible.value = true
}

const selectedBatch = ref<PurchaseVO | null>(null)
watch(
  () => form.batchId,
  (id) => {
    selectedBatch.value = pendingBatches.value.find((b) => b.id === id) ?? null
    if (selectedBatch.value?.coldChainTemp) {
      form.temperature = selectedBatch.value.coldChainTemp
    }
  },
)

async function submit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    const created = await acceptanceApi.create({
      batchId: form.batchId!,
      acceptTime: form.acceptTime || undefined,
      inspector: form.inspector,
      temperature: form.temperature ? toMoney(form.temperature) : undefined,
      appearanceOk: form.appearanceOk,
      certComplete: form.certComplete,
      sampleRetained: form.sampleRetained,
      result: form.result,
      rejectReason: form.result === 'FAIL' ? form.rejectReason : undefined,
    })
    ElMessage.success(
      form.result === 'PASS'
        ? `验收单 ${created.acceptNo} 已登记，批次状态更新为「验收通过」`
        : `验收单 ${created.acceptNo} 已登记，批次已限制流转`,
    )
    dialogVisible.value = false
    load()
    loadPending()
  } catch (err) {
    ElMessage.error(errText(err))
  } finally {
    submitting.value = false
  }
}

function checkIcon(ok: boolean) {
  return ok ? 'CircleCheck' : 'CircleClose'
}
</script>

<template>
  <div class="page">
    <PageHeader
      title="入库验收"
      desc="到货后逐批核对感官、票证与留样，登记中心温度。验收结论一经提交即生成链上存证事件，通过后方可投入加工。"
    >
      <template #actions>
        <el-button v-if="auth.isAdmin" type="primary" @click="openCreate()">
          <el-icon><Stamp /></el-icon>
          <span style="margin-left: 4px">登记验收</span>
        </el-button>
      </template>
    </PageHeader>

    <!-- 待验收提示条 -->
    <div v-if="pendingBatches.length > 0" class="pending-bar">
      <el-icon class="pb-icon"><WarningFilled /></el-icon>
      <span>当前有 <b>{{ pendingBatches.length }}</b> 个采购批次已到货但尚未登记验收：</span>
      <div class="pb-list">
        <el-tag
          v-for="b in pendingBatches.slice(0, 6)"
          :key="b.id"
          size="small"
          effect="plain"
          class="pb-tag"
          @click="openCreate(b)"
        >
          <span class="mono">{{ b.batchNo }}</span> · {{ b.meatType }}
        </el-tag>
        <span v-if="pendingBatches.length > 6" class="text-sub" style="font-size: 12px">
          等 {{ pendingBatches.length }} 个
        </span>
      </div>
    </div>

    <div class="panel">
      <div class="toolbar">
        <el-input
          v-model="query.batchNo"
          placeholder="按采购批次号查询"
          clearable
          style="width: 220px"
          @keyup.enter="search"
          @clear="search"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-select v-model="query.result" placeholder="全部结论" clearable style="width: 140px" @change="search">
          <el-option label="验收通过" value="PASS" />
          <el-option label="整批拒收" value="FAIL" />
        </el-select>
        <el-button type="primary" plain @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
        <div class="grow" />
        <span class="text-sub" style="font-size: 12.5px">共 {{ total }} 条</span>
      </div>

      <el-table v-loading="loading" :data="rows" border stripe empty-text="暂无验收记录">
        <el-table-column label="验收单号" width="142">
          <template #default="{ row }"><span class="mono">{{ row.acceptNo }}</span></template>
        </el-table-column>
        <el-table-column label="采购批次" width="142">
          <template #default="{ row }"><span class="mono">{{ row.batchNo }}</span></template>
        </el-table-column>
        <el-table-column prop="inspector" label="验收员" width="100" />
        <el-table-column label="验收时间" width="158">
          <template #default="{ row }"><span class="mono text-sub">{{ fmtDateTime(row.acceptTime) }}</span></template>
        </el-table-column>
        <el-table-column label="中心温度" width="94" align="right">
          <template #default="{ row }">
            <span class="mono">{{ row.temperature ? `${row.temperature}℃` : '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="感官" width="72" align="center">
          <template #default="{ row }">
            <el-icon :color="row.appearanceOk ? '#1a9c5b' : '#d92d20'" :size="16">
              <component :is="checkIcon(row.appearanceOk)" />
            </el-icon>
          </template>
        </el-table-column>
        <el-table-column label="票证" width="72" align="center">
          <template #default="{ row }">
            <el-icon :color="row.certComplete ? '#1a9c5b' : '#d92d20'" :size="16">
              <component :is="checkIcon(row.certComplete)" />
            </el-icon>
          </template>
        </el-table-column>
        <el-table-column label="留样" width="72" align="center">
          <template #default="{ row }">
            <el-icon :color="row.sampleRetained ? '#1a9c5b' : '#d92d20'" :size="16">
              <component :is="checkIcon(row.sampleRetained)" />
            </el-icon>
          </template>
        </el-table-column>
        <el-table-column label="结论" width="106" align="center">
          <template #default="{ row }">
            <el-tag :type="row.result === 'PASS' ? 'success' : 'danger'" size="small" disable-transitions>
              {{ row.result === 'PASS' ? '验收通过' : '整批拒收' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="rejectReason" label="拒收原因" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ row.rejectReason || '—' }}</template>
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

    <!-- 登记对话框 -->
    <el-dialog v-model="dialogVisible" title="登记验收结果" width="620px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="112px">
        <el-form-item label="采购批次" prop="batchId">
          <el-select v-model="form.batchId" placeholder="选择待验收的批次" filterable style="width: 100%">
            <el-option
              v-for="b in pendingBatches"
              :key="b.id"
              :label="`${b.batchNo} · ${b.meatType} · ${b.quantity}${b.unit}`"
              :value="b.id"
            />
          </el-select>
          <div v-if="pendingBatches.length === 0" class="hint warn">
            没有待验收的批次。所有批次都已完成验收，或尚未创建采购单。
          </div>
        </el-form-item>

        <el-form-item label="验收员" prop="inspector">
          <el-input v-model="form.inspector" maxlength="50" style="width: 220px" />
        </el-form-item>

        <el-form-item label="验收时间">
          <el-date-picker
            v-model="form.acceptTime"
            type="datetime"
            value-format="YYYY-MM-DD HH:mm:ss"
            style="width: 100%"
          />
        </el-form-item>

        <el-form-item label="中心温度">
          <el-input v-model="form.temperature" placeholder="如 3.50" style="width: 180px">
            <template #append>℃</template>
          </el-input>
          <div class="hint inline">默认带上采购单登记的到货温度，可复测后修改</div>
        </el-form-item>

        <el-form-item label="检查项">
          <div class="checks">
            <el-checkbox v-model="form.appearanceOk">感官正常（色泽、气味、组织状态）</el-checkbox>
            <el-checkbox v-model="form.certComplete">票证齐全（检疫合格证、动物检疫证明）</el-checkbox>
            <el-checkbox v-model="form.sampleRetained">已按规范留样</el-checkbox>
          </div>
        </el-form-item>

        <el-form-item label="验收结论" prop="result">
          <el-radio-group v-model="form.result">
            <el-radio-button value="PASS">验收通过</el-radio-button>
            <el-radio-button value="FAIL">整批拒收</el-radio-button>
          </el-radio-group>
        </el-form-item>

        <el-form-item v-if="form.result === 'FAIL'" label="拒收原因" prop="rejectReason">
          <el-input
            v-model="form.rejectReason"
            type="textarea"
            :rows="3"
            maxlength="200"
            show-word-limit
            placeholder="例如：到货温度 6.20℃ 超过冷鲜猪肉 4.00℃ 限值，感官有轻微异味"
          />
        </el-form-item>

        <el-alert
          v-if="selectedBatch"
          :type="form.result === 'PASS' ? 'success' : 'warning'"
          :closable="false"
          show-icon
          :title="
            form.result === 'PASS'
              ? `提交后批次 ${selectedBatch.batchNo} 将置为「验收通过」，可投入加工`
              : `提交后批次 ${selectedBatch.batchNo} 将置为「整批拒收」，不可投入加工`
          "
          description="本次验收会生成一条 ACCEPT_RESULT 存证事件，摘要上链后可在公开溯源页核验。"
        />
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">提交验收结果</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.pending-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding: 10px 14px;
  margin-bottom: 14px;
  font-size: 13px;
  color: #8a5a00;
  background: #fdf6e8;
  border: 1px solid #f3ddab;
  border-radius: 9px;
}

.pb-icon {
  color: #d99000;
}

.pb-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}

.pb-tag {
  cursor: pointer;
}

.pb-tag:hover {
  color: var(--el-color-primary);
  border-color: var(--el-color-primary);
}

.checks {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.hint {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--app-text-sub);
}

.hint.inline {
  margin: 0 0 0 12px;
  display: inline-block;
}

.hint.warn {
  color: #d97706;
}
</style>
