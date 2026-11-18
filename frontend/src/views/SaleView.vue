<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { saleApi, type SaleQuery } from '@/api/sale'
import { processApi } from '@/api/process'
import { errText } from '@/api/http'
import { SALE_CHANNELS, SALE_STATUS } from '@/constants/dict'
import { fmtDateTime, nowText, toMoney, toQty } from '@/utils/format'
import { useAuthStore } from '@/stores/auth'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import type { ProcessVO, SaleVO } from '@/types/api'

const auth = useAuthStore()

const loading = ref(false)
const rows = ref<SaleVO[]>([])
const total = ref(0)
const query = reactive<SaleQuery>({ page: 1, size: 20, processBatchNo: '', channel: '', status: '' })

async function load() {
  loading.value = true
  try {
    const data = await saleApi.page({ ...query })
    rows.value = data.records
    total.value = data.total
  } catch {
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

/** 只能对「在售」批次登记销售 */
const onSaleBatches = ref<ProcessVO[]>([])

async function loadOnSale() {
  try {
    const data = await processApi.page({ page: 1, size: 100, status: 'ON_SALE' })
    onSaleBatches.value = data.records
  } catch {
    onSaleBatches.value = []
  }
}

onMounted(() => {
  load()
  loadOnSale()
})

function search() {
  query.page = 1
  load()
}

function reset() {
  query.processBatchNo = ''
  query.channel = ''
  query.status = ''
  query.page = 1
  load()
}

/* ------------------------------ 销售登记 ------------------------------ */

const dialogVisible = ref(false)
const submitting = ref(false)
const formRef = ref<FormInstance>()

const form = reactive({
  processBatchNo: '',
  channel: '一食堂 3 号窗口',
  quantity: '',
  unitPrice: '',
  saleTime: nowText(),
  operator: auth.displayName,
})

const rules: FormRules = {
  processBatchNo: [{ required: true, message: '请选择在售批次（或直接扫追溯码）', trigger: 'change' }],
  quantity: [
    { required: true, message: '请填写销售数量', trigger: 'blur' },
    {
      validator: (_r, value, cb) => {
        if (!value) return cb()
        if (!/^\d{1,9}(\.\d{1,3})?$/.test(String(value))) {
          return cb(new Error('数量最多 9 位整数 + 3 位小数'))
        }
        if (Number(value) <= 0) return cb(new Error('销售数量必须大于 0'))
        cb()
      },
      trigger: 'blur',
    },
  ],
}

function openCreate() {
  form.processBatchNo = ''
  form.channel = '一食堂 3 号窗口'
  form.quantity = ''
  form.unitPrice = ''
  form.saleTime = nowText()
  form.operator = auth.displayName
  if (onSaleBatches.value.length === 0) loadOnSale()
  dialogVisible.value = true
}

const selectedBatch = ref<ProcessVO | null>(null)
watch(
  () => form.processBatchNo,
  (no) => {
    selectedBatch.value = onSaleBatches.value.find((b) => b.processNo === no) ?? null
  },
)

/** 实时算金额：只用于展示，真正入库的 amount 由后端算，避免两处口径不一致 */
const previewAmount = computed(() => {
  const q = toQty(form.quantity, 3)
  const p = toMoney(form.unitPrice)
  if (!q || !p) return null
  return (Number(q) * Number(p)).toFixed(2)
})

async function submit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    const created = await saleApi.create({
      processBatchNo: form.processBatchNo,
      channel: form.channel || undefined,
      quantity: toQty(form.quantity, 3),
      unitPrice: form.unitPrice ? toMoney(form.unitPrice) : undefined,
      saleTime: form.saleTime || undefined,
      operator: form.operator || undefined,
    })
    const soldOut = created.processStatus === 'SOLD_OUT'
    ElMessage.success(
      soldOut
        ? `销售单 ${created.saleNo} 已登记，该批次已售罄并自动流转`
        : `销售单 ${created.saleNo} 已登记，剩余可售 ${created.availableQty} kg`,
    )
    dialogVisible.value = false
    load()
    loadOnSale()
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
      title="销售登记"
      desc="窗口按追溯码登记售出数量。售出即从可售余量扣减，扣到 0 自动售罄。每条销售都会上链，出问题时能查到这批肉卖到了哪个窗口。"
    >
      <template #actions>
        <el-button v-if="auth.isAdmin" type="primary" @click="openCreate">
          <el-icon><Sell /></el-icon>
          <span style="margin-left: 4px">登记销售</span>
        </el-button>
      </template>
    </PageHeader>

    <div class="panel">
      <div class="toolbar">
        <el-input
          v-model="query.processBatchNo"
          placeholder="追溯码，如 PR20261118001"
          clearable
          style="width: 220px"
          @keyup.enter="search"
          @clear="search"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-select v-model="query.channel" placeholder="全部窗口" clearable style="width: 168px" @change="search">
          <el-option v-for="c in SALE_CHANNELS" :key="c" :label="c" :value="c" />
        </el-select>
        <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 132px" @change="search">
          <el-option label="正常" value="NORMAL" />
          <el-option label="已召回" value="RECALLED" />
        </el-select>
        <el-button type="primary" plain @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
        <div class="grow" />
        <span class="text-sub" style="font-size: 12.5px">共 {{ total }} 条</span>
      </div>

      <el-table v-loading="loading" :data="rows" border stripe empty-text="暂无销售记录">
        <el-table-column label="销售单号" width="142">
          <template #default="{ row }"><span class="mono">{{ row.saleNo }}</span></template>
        </el-table-column>
        <el-table-column label="追溯码" width="148">
          <template #default="{ row }"><span class="mono">{{ row.processNo }}</span></template>
        </el-table-column>
        <el-table-column prop="meatType" label="品类" min-width="140" show-overflow-tooltip />
        <el-table-column prop="channel" label="销售窗口" width="150" />
        <el-table-column label="数量" width="92" align="right">
          <template #default="{ row }"><span class="mono">{{ row.quantity }}</span></template>
        </el-table-column>
        <el-table-column label="单价" width="86" align="right">
          <template #default="{ row }"><span class="mono">{{ row.unitPrice || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="金额" width="102" align="right">
          <template #default="{ row }">
            <span class="mono amount">{{ row.amount ? `¥${row.amount}` : '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="operator" label="操作人" width="96" />
        <el-table-column label="销售时间" width="158">
          <template #default="{ row }"><span class="mono text-sub">{{ fmtDateTime(row.saleTime) }}</span></template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }"><StatusTag :value="row.status" :dict="SALE_STATUS" /></template>
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
    <el-dialog v-model="dialogVisible" title="销售登记" width="600px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="104px">
        <el-form-item label="在售批次" prop="processBatchNo">
          <el-select v-model="form.processBatchNo" placeholder="选择在售批次 / 扫追溯码" filterable style="width: 100%">
            <el-option
              v-for="b in onSaleBatches"
              :key="b.id"
              :label="`${b.processNo} · ${b.meatType} · 可售 ${b.availableQty}${b.unit}`"
              :value="b.processNo"
            />
          </el-select>
          <div v-if="onSaleBatches.length === 0" class="hint warn">
            没有在售批次。加工批次需先「上架」才能登记销售。
          </div>
        </el-form-item>

        <el-form-item label="销售窗口">
          <el-select v-model="form.channel" filterable allow-create style="width: 100%">
            <el-option v-for="c in SALE_CHANNELS" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>

        <el-form-item label="销售数量" prop="quantity">
          <el-input v-model="form.quantity" placeholder="如 25" style="width: 180px">
            <template #append>kg</template>
          </el-input>
          <div v-if="selectedBatch" class="hint inline">
            当前可售 <b class="mono">{{ selectedBatch.availableQty }}</b> kg
          </div>
        </el-form-item>

        <el-form-item label="单价（元）">
          <el-input v-model="form.unitPrice" placeholder="如 38.00" style="width: 180px">
            <template #append>/kg</template>
          </el-input>
          <span v-if="previewAmount" class="hint inline">
            预计金额 <b class="mono">¥{{ previewAmount }}</b>
          </span>
        </el-form-item>

        <el-form-item label="操作人">
          <el-input v-model="form.operator" maxlength="50" style="width: 220px" />
        </el-form-item>

        <el-form-item label="销售时间">
          <el-date-picker
            v-model="form.saleTime"
            type="datetime"
            value-format="YYYY-MM-DD HH:mm:ss"
            style="width: 100%"
          />
        </el-form-item>

        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="金额由服务端计算"
          description="quantity × unitPrice 保留 2 位小数。前端算的金额只用于预览，避免两个页面显示两个数。"
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
.amount {
  color: #1f2d3d;
  font-weight: 600;
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
