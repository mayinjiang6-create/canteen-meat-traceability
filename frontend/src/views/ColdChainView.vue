<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { coldChainApi, type ColdChainQuery } from '@/api/coldchain'
import { purchaseApi } from '@/api/purchase'
import { errText } from '@/api/http'
import { COLD_SEGMENT } from '@/constants/dict'
import { fmtDateTime, nowText, toMoney } from '@/utils/format'
import { useAuthStore } from '@/stores/auth'
import PageHeader from '@/components/PageHeader.vue'
import type { ColdChainVO, PurchaseVO } from '@/types/api'

const auth = useAuthStore()

const loading = ref(false)
const rows = ref<ColdChainVO[]>([])
const total = ref(0)
const query = reactive<ColdChainQuery>({ page: 1, size: 20, batchNo: '', exceeded: undefined })

async function load() {
  loading.value = true
  try {
    const data = await coldChainApi.page({ ...query })
    rows.value = data.records
    total.value = data.total
  } catch {
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

const batches = ref<PurchaseVO[]>([])

async function loadBatches() {
  try {
    const data = await purchaseApi.page({ page: 1, size: 100 })
    batches.value = data.records
  } catch {
    batches.value = []
  }
}

onMounted(() => {
  load()
  loadBatches()
})

function search() {
  query.page = 1
  load()
}

function reset() {
  query.batchNo = ''
  query.exceeded = undefined
  query.page = 1
  load()
}

/* ------------------------------ 登记 ------------------------------ */

const dialogVisible = ref(false)
const submitting = ref(false)
const formRef = ref<FormInstance>()

const form = reactive({
  batchNo: '',
  segment: 'WAREHOUSE_TO_CANTEEN',
  transportNo: '',
  temperature: '',
  recordedAt: nowText(),
  recorder: '',
  remark: '',
})

const rules: FormRules = {
  batchNo: [{ required: true, message: '请选择或输入采购批次号', trigger: 'change' }],
  segment: [{ required: true, message: '请选择运输段', trigger: 'change' }],
  temperature: [
    { required: true, message: '请填写实测温度', trigger: 'blur' },
    {
      validator: (_r, value, cb) => {
        if (!value) return cb()
        if (!/^-?\d{1,3}(\.\d{1,2})?$/.test(String(value))) {
          return cb(new Error('温度格式：最多 3 位整数 + 2 位小数，可带负号'))
        }
        cb()
      },
      trigger: 'blur',
    },
  ],
}

function openCreate() {
  form.batchNo = ''
  form.segment = 'WAREHOUSE_TO_CANTEEN'
  form.transportNo = ''
  form.temperature = ''
  form.recordedAt = nowText()
  form.recorder = auth.displayName
  form.remark = ''
  if (batches.value.length === 0) loadBatches()
  dialogVisible.value = true
}

/** 当前批次的品类，用来提示阈值口径 */
const currentBatch = computed(() => batches.value.find((b) => b.batchNo === form.batchNo))

/**
 * 阈值提示：真正判定在后端（trace.cold-chain.*），这里只是提前告诉录入人
 * "这批货的合规线是多少"，免得录完才发现超限。
 * 口径必须与后端 ColdChainRules 一致：含「冻」−15℃，其余 4℃。
 */
const thresholdHint = computed(() => {
  const meat = currentBatch.value?.meatType
  if (!meat) return null
  return meat.includes('冻') ? -15 : 4
})

const willExceed = computed(() => {
  if (thresholdHint.value == null || !form.temperature) return false
  const t = Number(form.temperature)
  if (Number.isNaN(t)) return false
  return t > thresholdHint.value
})

async function submit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    const created = await coldChainApi.create({
      batchNo: form.batchNo,
      segment: form.segment,
      transportNo: form.transportNo || undefined,
      temperature: toMoney(form.temperature),
      recordedAt: form.recordedAt || undefined,
      recorder: form.recorder || undefined,
      remark: form.remark || undefined,
    })
    if (created.exceeded) {
      ElMessage.warning(
        `温度 ${created.temperature}℃ 超过阈值 ${created.threshold}℃，已自动开出异常工单 ${created.abnormalNo ?? ''}`,
      )
    } else {
      ElMessage.success(`冷链记录 ${created.logNo} 已登记，温度合规`)
    }
    dialogVisible.value = false
    load()
  } catch (err) {
    ElMessage.error(errText(err))
  } finally {
    submitting.value = false
  }
}

function segmentText(v: string) {
  return COLD_SEGMENT[v]?.label ?? v
}
</script>

<template>
  <div class="page">
    <PageHeader
      title="冷链温控"
      desc="运输与仓储环节逐段登记实测温度。阈值按品类自动判定（含「冻」−15℃，牛猪 4℃），超限时系统自动开异常工单——但不会直接改批次状态，处置是验收员的判断。"
    >
      <template #actions>
        <el-button type="primary" @click="openCreate">
          <el-icon><Sunny /></el-icon>
          <span style="margin-left: 4px">登记温控</span>
        </el-button>
      </template>
    </PageHeader>

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
        <el-select v-model="query.exceeded" placeholder="全部记录" clearable style="width: 148px" @change="search">
          <el-option label="仅看超限" :value="true" />
          <el-option label="仅看合规" :value="false" />
        </el-select>
        <el-button type="primary" plain @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
        <div class="grow" />
        <span class="text-sub" style="font-size: 12.5px">共 {{ total }} 条</span>
      </div>

      <el-table v-loading="loading" :data="rows" border stripe empty-text="暂无温控记录">
        <el-table-column label="记录单号" width="142">
          <template #default="{ row }"><span class="mono">{{ row.logNo }}</span></template>
        </el-table-column>
        <el-table-column label="采购批次" width="142">
          <template #default="{ row }"><span class="mono">{{ row.batchNo }}</span></template>
        </el-table-column>
        <el-table-column prop="meatType" label="品类" min-width="130" show-overflow-tooltip />
        <el-table-column label="运输段" width="136">
          <template #default="{ row }">{{ segmentText(row.segment) }}</template>
        </el-table-column>
        <el-table-column prop="transportNo" label="运输单号" width="142">
          <template #default="{ row }"><span class="mono">{{ row.transportNo || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="实测温度" width="102" align="right">
          <template #default="{ row }">
            <span class="mono temp" :class="{ over: row.exceeded }">{{ row.temperature }}℃</span>
          </template>
        </el-table-column>
        <el-table-column label="阈值" width="86" align="right">
          <template #default="{ row }"><span class="mono text-sub">{{ row.threshold ?? '—' }}℃</span></template>
        </el-table-column>
        <el-table-column label="判定" width="98" align="center">
          <template #default="{ row }">
            <el-tag :type="row.exceeded ? 'danger' : 'success'" size="small" effect="light" disable-transitions>
              {{ row.exceeded ? '超限' : '合规' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="关联异常单" width="132">
          <template #default="{ row }">
            <span v-if="row.abnormalNo" class="mono abnormal-no">{{ row.abnormalNo }}</span>
            <span v-else class="text-sub">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="recorder" label="记录人" width="96" />
        <el-table-column label="记录时间" width="158">
          <template #default="{ row }"><span class="mono text-sub">{{ fmtDateTime(row.recordedAt) }}</span></template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.remark || '—' }}</template>
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

    <!-- 登记 -->
    <el-dialog v-model="dialogVisible" title="冷链温控登记" width="600px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="112px">
        <el-form-item label="采购批次" prop="batchNo">
          <el-select v-model="form.batchNo" placeholder="选择批次" filterable allow-create style="width: 100%">
            <el-option
              v-for="b in batches"
              :key="b.id"
              :label="`${b.batchNo} · ${b.meatType}`"
              :value="b.batchNo"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="运输段" prop="segment">
          <el-radio-group v-model="form.segment">
            <el-radio-button value="ORIGIN_TO_WAREHOUSE">产地 → 仓库</el-radio-button>
            <el-radio-button value="WAREHOUSE_TO_CANTEEN">仓库 → 食堂</el-radio-button>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="运输单号">
          <el-input v-model="form.transportNo" maxlength="40" placeholder="如 TR20261118001" />
        </el-form-item>

        <el-form-item label="实测温度" prop="temperature">
          <el-input v-model="form.temperature" placeholder="如 3.50 或 -16.20" style="width: 180px">
            <template #append>℃</template>
          </el-input>
          <div v-if="thresholdHint != null" class="hint inline">
            该品类阈值 <b class="mono">{{ thresholdHint }}℃</b>
            <span v-if="willExceed" class="over-tip">· 本次填写将判定为超限并自动开异常单</span>
          </div>
        </el-form-item>

        <el-form-item label="记录人">
          <el-input v-model="form.recorder" maxlength="50" style="width: 220px" />
        </el-form-item>

        <el-form-item label="记录时间">
          <el-date-picker
            v-model="form.recordedAt"
            type="datetime"
            value-format="YYYY-MM-DD HH:mm:ss"
            style="width: 100%"
          />
        </el-form-item>

        <el-form-item label="备注">
          <el-input
            v-model="form.remark"
            type="textarea"
            :rows="2"
            maxlength="200"
            show-word-limit
            placeholder="如：车厢制冷故障，途中停机约 40 分钟"
          />
        </el-form-item>

        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="阈值不接受人工填写"
          description="阈值由系统按品类从配置读取（trace.cold-chain.*）。能改阈值就不叫监管——这是本模块的设计前提。"
        />
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.temp.over {
  color: #d92d20;
  font-weight: 600;
}

.abnormal-no {
  color: #d92d20;
  font-size: 12px;
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

.over-tip {
  color: #d92d20;
}
</style>
