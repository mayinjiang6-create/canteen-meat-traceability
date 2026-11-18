<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { recallApi, type RecallQuery } from '@/api/recall'
import { processApi } from '@/api/process'
import { errText } from '@/api/http'
import { RECALL_STATUS } from '@/constants/dict'
import { fmtDateTime, nowText } from '@/utils/format'
import { useAuthStore } from '@/stores/auth'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import type { ProcessVO, RecallVO } from '@/types/api'

const auth = useAuthStore()

const loading = ref(false)
const rows = ref<RecallVO[]>([])
const total = ref(0)
const query = reactive<RecallQuery>({ page: 1, size: 20, processBatchNo: '' })

async function load() {
  loading.value = true
  try {
    const data = await recallApi.page({ ...query })
    rows.value = data.records
    total.value = data.total
  } catch {
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

/** 可召回的批次：除已召回外的所有加工批次 */
const candidates = ref<ProcessVO[]>([])

async function loadCandidates() {
  try {
    const data = await processApi.page({ page: 1, size: 100 })
    candidates.value = data.records.filter((p) => p.status !== 'RECALLED')
  } catch {
    candidates.value = []
  }
}

onMounted(() => {
  load()
  loadCandidates()
})

function search() {
  query.page = 1
  load()
}

function reset() {
  query.processBatchNo = ''
  query.page = 1
  load()
}

/* ------------------------------ 发起召回 ------------------------------ */

const dialogVisible = ref(false)
const submitting = ref(false)
const formRef = ref<FormInstance>()

const form = reactive({
  processBatchNo: '',
  reason: '',
  scope: '',
  operator: auth.displayName,
  recallTime: nowText(),
})

const rules: FormRules = {
  processBatchNo: [{ required: true, message: '请选择需要召回的批次', trigger: 'change' }],
  reason: [{ required: true, message: '召回原因必填——它会被写进链上事件，半年后仍可查', trigger: 'blur' }],
}

function openCreate() {
  form.processBatchNo = ''
  form.reason = ''
  form.scope = ''
  form.operator = auth.displayName
  form.recallTime = nowText()
  if (candidates.value.length === 0) loadCandidates()
  dialogVisible.value = true
}

async function submit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  try {
    await ElMessageBox.confirm(
      '召回会立即把该批次置为「已召回」，并把其全部销售记录标记为「已召回」。此操作不可撤销，确认继续？',
      '召回确认',
      { confirmButtonText: '确认召回', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }

  submitting.value = true
  try {
    const created = await recallApi.create({
      processBatchNo: form.processBatchNo,
      reason: form.reason,
      scope: form.scope || undefined,
      operator: form.operator || undefined,
      recallTime: form.recallTime || undefined,
    })
    ElMessage.success(
      created.replayed
        ? `该批次已召回（召回单 ${created.recallNo}），本次为重复提交`
        : `召回单 ${created.recallNo} 已生效：影响 ${created.affectedSales} 笔销售、共 ${created.recalledQty} kg`,
    )
    dialogVisible.value = false
    load()
    loadCandidates()
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
      title="批次召回"
      desc="同批次发现安全问题时的最后一道闸门。召回会联动该批次的全部销售记录，让「这批肉卖到了哪几个窗口」变成可查的事实，而不是回忆。"
    >
      <template #actions>
        <el-button v-if="auth.isAdmin" type="danger" @click="openCreate">
          <el-icon><RefreshLeft /></el-icon>
          <span style="margin-left: 4px">发起召回</span>
        </el-button>
      </template>
    </PageHeader>

    <div class="panel">
      <div class="toolbar">
        <el-input
          v-model="query.processBatchNo"
          placeholder="按追溯码查询"
          clearable
          style="width: 220px"
          @keyup.enter="search"
          @clear="search"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button type="primary" plain @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
        <div class="grow" />
        <span class="text-sub" style="font-size: 12.5px">共 {{ total }} 条</span>
      </div>

      <el-table v-loading="loading" :data="rows" border stripe empty-text="暂无召回记录">
        <el-table-column label="召回单号" width="142">
          <template #default="{ row }"><span class="mono">{{ row.recallNo }}</span></template>
        </el-table-column>
        <el-table-column label="追溯码" width="148">
          <template #default="{ row }"><span class="mono">{{ row.processNo }}</span></template>
        </el-table-column>
        <el-table-column prop="meatType" label="品类" min-width="140" show-overflow-tooltip />
        <el-table-column prop="reason" label="召回原因" min-width="220" show-overflow-tooltip />
        <el-table-column prop="scope" label="召回范围" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.scope || '—' }}</template>
        </el-table-column>
        <el-table-column label="影响销售" width="104" align="center">
          <template #default="{ row }">
            <span class="mono affected">{{ row.affectedSales ?? '—' }} 笔</span>
          </template>
        </el-table-column>
        <el-table-column label="召回数量" width="108" align="right">
          <template #default="{ row }">
            <span class="mono">{{ row.recalledQty ? `${row.recalledQty} kg` : '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="operator" label="发起人" width="104" />
        <el-table-column label="召回时间" width="158">
          <template #default="{ row }"><span class="mono text-sub">{{ fmtDateTime(row.recallTime) }}</span></template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }"><StatusTag :value="row.status" :dict="RECALL_STATUS" /></template>
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

    <!-- 发起召回 -->
    <el-dialog v-model="dialogVisible" title="发起批次召回" width="620px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="104px">
        <el-form-item label="召回批次" prop="processBatchNo">
          <el-select v-model="form.processBatchNo" placeholder="选择需要召回的加工批次" filterable style="width: 100%">
            <el-option
              v-for="p in candidates"
              :key="p.id"
              :label="`${p.processNo} · ${p.meatType} · 当前 ${p.status}`"
              :value="p.processNo"
            />
          </el-select>
          <div v-if="candidates.length === 0" class="hint warn">没有可召回的批次（可能都已召回）。</div>
        </el-form-item>

        <el-form-item label="召回原因" prop="reason">
          <el-input
            v-model="form.reason"
            type="textarea"
            :rows="3"
            maxlength="300"
            show-word-limit
            placeholder="如：同批次抽检发现兽药残留超标"
          />
          <div class="hint">原因会写入链上事件的 payload，是可核验内容的一部分。</div>
        </el-form-item>

        <el-form-item label="召回范围">
          <el-input v-model="form.scope" maxlength="200" placeholder="如：2026-11-18 全天销售记录" />
        </el-form-item>

        <el-form-item label="发起人">
          <el-input v-model="form.operator" maxlength="50" style="width: 220px" />
        </el-form-item>

        <el-form-item label="召回时间">
          <el-date-picker
            v-model="form.recallTime"
            type="datetime"
            value-format="YYYY-MM-DD HH:mm:ss"
            style="width: 100%"
          />
        </el-form-item>

        <el-alert
          type="error"
          :closable="false"
          show-icon
          title="召回的连带效果（同一事务内完成）"
        >
          <div style="line-height: 1.9; font-size: 12.5px">
            ① 加工批次置为「已召回」，销售入口立即关闭；<br />
            ② 该批次<b>全部</b>销售记录的 status 置为「已召回」，一笔不落；<br />
            ③ 生成召回单与 RECALL 链上事件，payload 含原因、范围、影响笔数与数量。
          </div>
        </el-alert>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="danger" :loading="submitting" @click="submit">确认召回</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.affected {
  color: #d92d20;
  font-weight: 600;
}

.hint {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--app-text-sub);
}

.hint.warn {
  color: #d97706;
}
</style>
