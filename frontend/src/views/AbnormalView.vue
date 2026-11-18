<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { abnormalApi, type AbnormalQuery } from '@/api/abnormal'
import { processApi } from '@/api/process'
import { purchaseApi } from '@/api/purchase'
import { errText } from '@/api/http'
import { ABNORMAL_LEVEL, ABNORMAL_STATUS, ABNORMAL_TYPE } from '@/constants/dict'
import { fmtDateTime, nowText } from '@/utils/format'
import { useAuthStore } from '@/stores/auth'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import type { AbnormalVO, ProcessVO, PurchaseVO } from '@/types/api'

const auth = useAuthStore()

const loading = ref(false)
const rows = ref<AbnormalVO[]>([])
const total = ref(0)
const query = reactive<AbnormalQuery>({ page: 1, size: 20, processBatchNo: '', status: '', abnormalType: '' })

async function load() {
  loading.value = true
  try {
    const data = await abnormalApi.page({ ...query })
    rows.value = data.records
    total.value = data.total
  } catch {
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

const processes = ref<ProcessVO[]>([])
const purchases = ref<PurchaseVO[]>([])

async function loadRefs() {
  const [p1, p2] = await Promise.allSettled([
    processApi.page({ page: 1, size: 100 }),
    purchaseApi.page({ page: 1, size: 100 }),
  ])
  if (p1.status === 'fulfilled') processes.value = p1.value.records
  if (p2.status === 'fulfilled') purchases.value = p2.value.records
}

onMounted(() => {
  load()
  loadRefs()
})

function search() {
  query.page = 1
  load()
}

function reset() {
  query.processBatchNo = ''
  query.status = ''
  query.abnormalType = ''
  query.page = 1
  load()
}

/* ------------------------------ 登记 ------------------------------ */

const createVisible = ref(false)
const submitting = ref(false)
const formRef = ref<FormInstance>()

const form = reactive({
  scope: 'PROCESS' as 'PROCESS' | 'PURCHASE',
  processBatchNo: '',
  batchNo: '',
  abnormalType: 'SENSORY_ABNORMAL',
  level: 'MEDIUM',
  description: '',
  occurredAt: nowText(),
  handler: auth.displayName,
})

watch(
  () => form.scope,
  (v) => {
    if (v === 'PROCESS') form.batchNo = ''
    else form.processBatchNo = ''
  },
)

const rules: FormRules = {
  description: [{ required: true, message: '请描述发现的问题', trigger: 'blur' }],
  abnormalType: [{ required: true, message: '请选择异常类型', trigger: 'change' }],
}

function openCreate() {
  form.scope = 'PROCESS'
  form.processBatchNo = ''
  form.batchNo = ''
  form.abnormalType = 'SENSORY_ABNORMAL'
  form.level = 'MEDIUM'
  form.description = ''
  form.occurredAt = nowText()
  form.handler = auth.displayName
  createVisible.value = true
}

const targetMissing = computed(() =>
  form.scope === 'PROCESS' ? !form.processBatchNo : !form.batchNo,
)

async function submitCreate() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  if (targetMissing.value) {
    ElMessage.warning(form.scope === 'PROCESS' ? '请选择成品批次' : '请选择到货采购批次')
    return
  }

  submitting.value = true
  try {
    const created = await abnormalApi.create({
      processBatchNo: form.scope === 'PROCESS' ? form.processBatchNo : undefined,
      batchNo: form.scope === 'PURCHASE' ? form.batchNo : undefined,
      abnormalType: form.abnormalType,
      level: form.level,
      description: form.description,
      occurredAt: form.occurredAt || undefined,
      handler: form.handler || undefined,
    })
    ElMessage.success(
      `异常工单 ${created.abnormalNo} 已登记${form.scope === 'PROCESS' ? '，该批次已自动冻结销售' : ''}`,
    )
    createVisible.value = false
    load()
  } catch (err) {
    ElMessage.error(errText(err))
  } finally {
    submitting.value = false
  }
}

/* ------------------------------ 处置 ------------------------------ */

const disposeVisible = ref(false)
const disposing = ref(false)
const disposeRef = ref<FormInstance>()
const current = ref<AbnormalVO | null>(null)

const disposeForm = reactive({
  action: '',
  handler: auth.displayName,
  conclusion: '',
  resumeSale: false,
  disposeTime: nowText(),
})

const disposeRules: FormRules = {
  action: [{ required: true, message: '请填写处置措施', trigger: 'blur' }],
  conclusion: [{ required: true, message: '请填写处置结论', trigger: 'blur' }],
}

function openDispose(row: AbnormalVO) {
  current.value = row
  disposeForm.action = ''
  disposeForm.handler = auth.displayName
  disposeForm.conclusion = ''
  disposeForm.resumeSale = false
  disposeForm.disposeTime = nowText()
  disposeVisible.value = true
}

async function submitDispose() {
  if (!disposeRef.value || !current.value) return
  const valid = await disposeRef.value.validate().catch(() => false)
  if (!valid) return

  disposing.value = true
  try {
    await abnormalApi.dispose(current.value.id, {
      action: disposeForm.action,
      handler: disposeForm.handler || undefined,
      conclusion: disposeForm.conclusion,
      resumeSale: disposeForm.resumeSale,
      disposeTime: disposeForm.disposeTime || undefined,
    })
    ElMessage.success(disposeForm.resumeSale ? '已处置完毕，批次恢复在售' : '已处置完毕，批次未恢复销售')
    disposeVisible.value = false
    load()
  } catch (err) {
    ElMessage.error(errText(err))
  } finally {
    disposing.value = false
  }
}

const canDispose = (row: AbnormalVO) => row.status !== 'CLOSED'
</script>

<template>
  <div class="page">
    <PageHeader
      title="异常管理"
      desc="发现成品感官异常、票证不齐、温度超限时登记工单。若关联批次正在销售，系统会立即冻结其销售入口；处置时由人工决定是否恢复上架。"
    >
      <template #actions>
        <el-button v-if="auth.isAdmin" type="primary" @click="openCreate">
          <el-icon><WarningFilled /></el-icon>
          <span style="margin-left: 4px">登记异常</span>
        </el-button>
      </template>
    </PageHeader>

    <div class="panel">
      <div class="toolbar">
        <el-input
          v-model="query.processBatchNo"
          placeholder="按加工批次号查询"
          clearable
          style="width: 220px"
          @keyup.enter="search"
          @clear="search"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-select v-model="query.abnormalType" placeholder="全部类型" clearable style="width: 168px" @change="search">
          <el-option v-for="(item, key) in ABNORMAL_TYPE" :key="key" :label="item.label" :value="key" />
        </el-select>
        <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 136px" @change="search">
          <el-option v-for="(item, key) in ABNORMAL_STATUS" :key="key" :label="item.label" :value="key" />
        </el-select>
        <el-button type="primary" plain @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
        <div class="grow" />
        <span class="text-sub" style="font-size: 12.5px">共 {{ total }} 条</span>
      </div>

      <el-table v-loading="loading" :data="rows" border stripe empty-text="暂无异常工单">
        <el-table-column label="异常单号" width="142">
          <template #default="{ row }"><span class="mono">{{ row.abnormalNo }}</span></template>
        </el-table-column>
        <el-table-column label="关联批次" width="148">
          <template #default="{ row }">
            <span v-if="row.processNo" class="mono">{{ row.processNo }}</span>
            <span v-else-if="row.purchaseBatchNo" class="mono">{{ row.purchaseBatchNo }}</span>
            <span v-else class="text-sub">—</span>
          </template>
        </el-table-column>
        <el-table-column label="关联环节" width="94" align="center">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="row.processNo ? 'warning' : 'info'" disable-transitions>
              {{ row.processNo ? '成品' : '到货' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="132">
          <template #default="{ row }">
            <StatusTag :value="row.abnormalType" :dict="ABNORMAL_TYPE" />
          </template>
        </el-table-column>
        <el-table-column label="等级" width="76" align="center">
          <template #default="{ row }"><StatusTag :value="row.level" :dict="ABNORMAL_LEVEL" /></template>
        </el-table-column>
        <el-table-column prop="description" label="问题描述" min-width="240" show-overflow-tooltip />
        <el-table-column prop="conclusion" label="处置结论" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.conclusion || '—' }}</template>
        </el-table-column>
        <el-table-column prop="handler" label="处理人" width="96" />
        <el-table-column label="发生时间" width="158">
          <template #default="{ row }"><span class="mono text-sub">{{ fmtDateTime(row.occurredAt) }}</span></template>
        </el-table-column>
        <el-table-column label="状态" width="102" align="center">
          <template #default="{ row }"><StatusTag :value="row.status" :dict="ABNORMAL_STATUS" /></template>
        </el-table-column>
        <el-table-column label="操作" width="94" align="center" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="auth.isAdmin && canDispose(row)"
              link
              type="primary"
              size="small"
              @click="openDispose(row)"
            >
              处置
            </el-button>
            <span v-else class="text-sub" style="font-size: 12px">已闭环</span>
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

    <!-- 登记 -->
    <el-dialog v-model="createVisible" title="登记异常事件" width="620px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="112px">
        <el-form-item label="关联对象">
          <el-radio-group v-model="form.scope">
            <el-radio-button value="PROCESS">成品批次（加工阶段发现）</el-radio-button>
            <el-radio-button value="PURCHASE">到货批次（入库前发现）</el-radio-button>
          </el-radio-group>
        </el-form-item>

        <el-form-item v-if="form.scope === 'PROCESS'" label="成品批次">
          <el-select v-model="form.processBatchNo" placeholder="选择加工批次" filterable allow-create style="width: 100%">
            <el-option
              v-for="p in processes"
              :key="p.id"
              :label="`${p.processNo} · ${p.meatType} · ${p.status}`"
              :value="p.processNo"
            />
          </el-select>
          <div class="hint">该批次若正在销售，提交后立即冻结，销售登记将无法选择它。</div>
        </el-form-item>

        <el-form-item v-else label="到货批次">
          <el-select v-model="form.batchNo" placeholder="选择采购批次" filterable allow-create style="width: 100%">
            <el-option
              v-for="b in purchases"
              :key="b.id"
              :label="`${b.batchNo} · ${b.meatType} · ${b.status}`"
              :value="b.batchNo"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="异常类型" prop="abnormalType">
          <el-select v-model="form.abnormalType" style="width: 100%">
            <el-option v-for="(item, key) in ABNORMAL_TYPE" :key="key" :label="item.label" :value="key" />
          </el-select>
        </el-form-item>

        <el-form-item label="等级">
          <el-radio-group v-model="form.level">
            <el-radio-button value="LOW">低</el-radio-button>
            <el-radio-button value="MEDIUM">中</el-radio-button>
            <el-radio-button value="HIGH">高</el-radio-button>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="问题描述" prop="description">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="3"
            maxlength="300"
            show-word-limit
            placeholder="如：抽检发现色泽异常，暂停销售待复检"
          />
        </el-form-item>

        <el-form-item label="处理人">
          <el-input v-model="form.handler" maxlength="50" style="width: 220px" />
        </el-form-item>

        <el-form-item label="发生时间">
          <el-date-picker
            v-model="form.occurredAt"
            type="datetime"
            value-format="YYYY-MM-DD HH:mm:ss"
            style="width: 100%"
          />
        </el-form-item>

        <el-alert
          type="warning"
          :closable="false"
          show-icon
          title="登记会生成一条 DISPOSAL 事件（stage=REPORTED）"
          description="登记表示「发现了问题」，结案表示「问题处理完了」，两件事都要可核验，所以同一工单会留下两条链上事件。"
        />
      </el-form>

      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitCreate">提交登记</el-button>
      </template>
    </el-dialog>

    <!-- 处置 -->
    <el-dialog v-model="disposeVisible" title="异常处置" width="580px" destroy-on-close>
      <el-descriptions v-if="current" :column="1" border size="small" style="margin-bottom: 16px">
        <el-descriptions-item label="异常单号">
          <span class="mono">{{ current.abnormalNo }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="关联批次">
          <span class="mono">{{ current.processNo || current.purchaseBatchNo }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="问题描述">{{ current.description }}</el-descriptions-item>
      </el-descriptions>

      <el-form ref="disposeRef" :model="disposeForm" :rules="disposeRules" label-width="104px">
        <el-form-item label="处置措施" prop="action">
          <el-input
            v-model="disposeForm.action"
            type="textarea"
            :rows="2"
            maxlength="200"
            placeholder="如：暂停销售、封存留样并送第三方复检"
          />
        </el-form-item>

        <el-form-item label="处置结论" prop="conclusion">
          <el-input
            v-model="disposeForm.conclusion"
            type="textarea"
            :rows="2"
            maxlength="200"
            placeholder="如：复检合格，恢复销售"
          />
        </el-form-item>

        <el-form-item label="是否恢复销售">
          <el-switch v-model="disposeForm.resumeSale" active-text="恢复上架" inactive-text="保持冻结" />
          <div class="hint" style="margin-left: 12px">
            必须显式选择：结论是「整批销毁」时，按默认逻辑恢复上架会出大事。
          </div>
        </el-form-item>

        <el-form-item label="处理人">
          <el-input v-model="disposeForm.handler" maxlength="50" style="width: 200px" />
        </el-form-item>

        <el-form-item label="处置时间">
          <el-date-picker
            v-model="disposeForm.disposeTime"
            type="datetime"
            value-format="YYYY-MM-DD HH:mm:ss"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="disposeVisible = false">取消</el-button>
        <el-button type="primary" :loading="disposing" @click="submitDispose">提交处置</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.hint {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--app-text-sub);
}
</style>
