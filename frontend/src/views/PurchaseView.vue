<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { purchaseApi, type PurchaseQuery } from '@/api/purchase'
import { supplierApi } from '@/api/supplier'
import { errText } from '@/api/http'
import { MEAT_TYPES, PURCHASE_STATUS } from '@/constants/dict'
import { fmtDateTime, nowText, toMoney, toQty } from '@/utils/format'
import { useAuthStore } from '@/stores/auth'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import type { PurchaseCreateRequest, PurchaseVO, SupplierVO } from '@/types/api'

const auth = useAuthStore()
const router = useRouter()

const loading = ref(false)
const rows = ref<PurchaseVO[]>([])
const total = ref(0)
const query = reactive<PurchaseQuery>({ page: 1, size: 20, batchNo: '', status: '', meatType: '' })

const suppliers = ref<SupplierVO[]>([])

async function loadSuppliers() {
  try {
    const data = await supplierApi.page({ page: 1, size: 100, status: 'ACTIVE' })
    suppliers.value = data.records
  } catch {
    suppliers.value = []
  }
}

async function load() {
  loading.value = true
  try {
    const data = await purchaseApi.page({ ...query })
    rows.value = data.records
    total.value = data.total
  } catch {
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadSuppliers()
  load()
})

function search() {
  query.page = 1
  load()
}

function reset() {
  query.batchNo = ''
  query.status = ''
  query.meatType = ''
  query.page = 1
  load()
}

/* ------------------------------ 详情抽屉 ------------------------------ */

const detailVisible = ref(false)
const detail = ref<PurchaseVO | null>(null)

function openDetail(row: PurchaseVO) {
  detail.value = row
  detailVisible.value = true
}

function gotoTrace(batchNo?: string | null) {
  if (!batchNo) return
  const url = router.resolve({ name: 'trace', params: { batchNo } }).href
  window.open(url, '_blank', 'noopener')
}

/* ------------------------------ 新建采购 ------------------------------ */

const createVisible = ref(false)
const submitting = ref(false)
const formRef = ref<FormInstance>()

const blank = () => ({
  supplierId: undefined as number | undefined,
  meatType: '冷鲜猪肉',
  origin: '',
  slaughterNo: '',
  quarantineNo: '',
  quantity: '',
  unit: 'kg',
  unitPrice: '',
  arriveTime: nowText(),
  coldChainTemp: '',
})

const form = reactive(blank())

const rules: FormRules = {
  supplierId: [{ required: true, message: '请选择供应商', trigger: 'change' }],
  meatType: [{ required: true, message: '请填写品类', trigger: 'change' }],
  quarantineNo: [{ required: true, message: '检疫合格证号必须填写', trigger: 'blur' }],
  quantity: [
    { required: true, message: '请填写采购数量', trigger: 'blur' },
    {
      validator: (_r, value, cb) => {
        if (!value) return cb()
        if (!/^\d{1,9}(\.\d{1,3})?$/.test(String(value))) {
          return cb(new Error('数量最多 9 位整数 + 3 位小数'))
        }
        if (Number(value) <= 0) return cb(new Error('数量必须大于 0'))
        cb()
      },
      trigger: 'blur',
    },
  ],
}

function openCreate() {
  Object.assign(form, blank())
  if (suppliers.value.length === 0) loadSuppliers()
  createVisible.value = true
}

const selectedSupplier = computed(() =>
  suppliers.value.find((s) => s.id === form.supplierId),
)

async function submit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    const payload: PurchaseCreateRequest = {
      supplierId: form.supplierId!,
      meatType: form.meatType,
      origin: form.origin || undefined,
      slaughterNo: form.slaughterNo || undefined,
      quarantineNo: form.quarantineNo,
      // 后端按 BigDecimal 接收，必须传字符串且位数固定，否则摘要规范串会变
      quantity: toQty(form.quantity, 3),
      unit: form.unit || 'kg',
      unitPrice: form.unitPrice ? toMoney(form.unitPrice) : undefined,
      arriveTime: form.arriveTime || undefined,
      coldChainTemp: form.coldChainTemp ? toMoney(form.coldChainTemp) : undefined,
    }
    const created = await purchaseApi.create(payload)
    ElMessage.success(`采购批次 ${created.batchNo} 已创建，存证任务已入队`)
    createVisible.value = false
    load()
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
      title="采购批次"
      desc="每批到货登记一条采购记录：供应商、检疫合格证号、数量与到货温度。提交后业务数据立即入库，链上存证由后台任务异步完成。"
    >
      <template #actions>
        <el-button v-if="auth.isAdmin" type="primary" @click="openCreate">
          <el-icon><Plus /></el-icon>
          <span style="margin-left: 4px">新建采购批次</span>
        </el-button>
      </template>
    </PageHeader>

    <div class="panel">
      <div class="toolbar">
        <el-input
          v-model="query.batchNo"
          placeholder="批次号，如 PB20261118001"
          clearable
          style="width: 220px"
          @keyup.enter="search"
          @clear="search"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>

        <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 140px" @change="search">
          <el-option v-for="(item, key) in PURCHASE_STATUS" :key="key" :label="item.label" :value="key" />
        </el-select>

        <el-select v-model="query.meatType" placeholder="全部品类" clearable style="width: 160px" @change="search">
          <el-option v-for="t in MEAT_TYPES" :key="t" :label="t" :value="t" />
        </el-select>

        <el-button type="primary" plain @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
        <div class="grow" />
        <span class="text-sub" style="font-size: 12.5px">共 {{ total }} 条</span>
      </div>

      <el-table v-loading="loading" :data="rows" border stripe empty-text="暂无采购批次">
        <el-table-column label="批次号" width="142" fixed="left">
          <template #default="{ row }">
            <el-button link type="primary" class="mono" @click="openDetail(row)">{{ row.batchNo }}</el-button>
          </template>
        </el-table-column>
        <el-table-column prop="supplierName" label="供应商" min-width="168" show-overflow-tooltip>
          <template #default="{ row }">{{ row.supplierName || `#${row.supplierId}` }}</template>
        </el-table-column>
        <el-table-column prop="meatType" label="品类" width="112" />
        <el-table-column prop="origin" label="产地" width="128" show-overflow-tooltip />
        <el-table-column prop="quarantineNo" label="检疫合格证号" width="176" show-overflow-tooltip>
          <template #default="{ row }"><span class="mono">{{ row.quarantineNo }}</span></template>
        </el-table-column>
        <el-table-column label="采购量" width="96" align="right">
          <template #default="{ row }"><span class="mono">{{ row.quantity }}</span></template>
        </el-table-column>
        <el-table-column label="可用余量" width="100" align="right">
          <template #default="{ row }">
            <span class="mono" :class="{ zero: Number(row.availableQty) === 0 }">{{ row.availableQty }}</span>
          </template>
        </el-table-column>
        <el-table-column label="单价" width="88" align="right">
          <template #default="{ row }"><span class="mono">{{ row.unitPrice || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="到货温度" width="94" align="right">
          <template #default="{ row }">
            <span v-if="row.coldChainTemp" class="mono">{{ row.coldChainTemp }}℃</span>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column prop="arriveTime" label="到货时间" width="158">
          <template #default="{ row }"><span class="mono text-sub">{{ fmtDateTime(row.arriveTime) }}</span></template>
        </el-table-column>
        <el-table-column label="状态" width="112" align="center">
          <template #default="{ row }"><StatusTag :value="row.status" :dict="PURCHASE_STATUS" /></template>
        </el-table-column>
        <el-table-column label="操作" width="150" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
            <el-button link type="primary" size="small" @click="gotoTrace(row.batchNo)">溯源</el-button>
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
    <el-drawer v-model="detailVisible" title="采购批次详情" size="560px">
      <template v-if="detail">
        <el-descriptions :column="1" border size="default">
          <el-descriptions-item label="批次号">
            <span class="mono">{{ detail.batchNo }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <StatusTag :value="detail.status" :dict="PURCHASE_STATUS" />
          </el-descriptions-item>
          <el-descriptions-item label="供应商">{{ detail.supplierName || `#${detail.supplierId}` }}</el-descriptions-item>
          <el-descriptions-item label="品类">{{ detail.meatType }}</el-descriptions-item>
          <el-descriptions-item label="产地">{{ detail.origin || '—' }}</el-descriptions-item>
          <el-descriptions-item label="屠宰场编号">
            <span class="mono">{{ detail.slaughterNo || '—' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="检疫合格证号">
            <span class="mono">{{ detail.quarantineNo }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="采购数量">
            <span class="mono">{{ detail.quantity }} {{ detail.unit }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="可用余量">
            <span class="mono">{{ detail.availableQty }} {{ detail.unit }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="单价">
            <span class="mono">{{ detail.unitPrice || '—' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="到货时间">
            <span class="mono">{{ fmtDateTime(detail.arriveTime) }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="到货中心温度">
            <span class="mono">{{ detail.coldChainTemp ? `${detail.coldChainTemp}℃` : '—' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="创建时间">
            <span class="mono">{{ fmtDateTime(detail.createdAt) }}</span>
          </el-descriptions-item>
        </el-descriptions>

        <el-button type="primary" plain style="margin-top: 18px; width: 100%" @click="gotoTrace(detail?.batchNo)">
          <el-icon><View /></el-icon>
          <span style="margin-left: 5px">查看该批次公开溯源页</span>
        </el-button>
      </template>
    </el-drawer>

    <!-- 新建 -->
    <el-dialog v-model="createVisible" title="新建采购批次" width="620px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="126px">
        <el-form-item label="供应商" prop="supplierId">
          <el-select v-model="form.supplierId" placeholder="选择已备案的供应商" filterable style="width: 100%">
            <el-option v-for="s in suppliers" :key="s.id" :label="`${s.name}（${s.supplierCode}）`" :value="s.id" />
          </el-select>
          <div v-if="suppliers.length === 0" class="form-hint warn">
            还没有正常状态的供应商，请先到「供应商备案」新增。
          </div>
        </el-form-item>

        <el-form-item label="肉类品类" prop="meatType">
          <el-select v-model="form.meatType" filterable allow-create default-first-option style="width: 100%">
            <el-option v-for="t in MEAT_TYPES" :key="t" :label="t" :value="t" />
          </el-select>
          <div class="form-hint">
            品类决定冷链温度阈值：含「冻」→ −15℃，含「牛」「猪」→ 4℃。
          </div>
        </el-form-item>

        <el-form-item label="产地">
          <el-input v-model="form.origin" maxlength="100" placeholder="如：江西省南昌市" />
        </el-form-item>

        <el-form-item label="屠宰场编号">
          <el-input v-model="form.slaughterNo" maxlength="50" placeholder="如：SL20261117001" />
        </el-form-item>

        <el-form-item label="检疫合格证号" prop="quarantineNo">
          <el-input v-model="form.quarantineNo" maxlength="60" placeholder="如：JX-JY-2026-111701" />
        </el-form-item>

        <el-form-item label="采购数量" prop="quantity">
          <el-input v-model="form.quantity" placeholder="如 120 或 120.5" style="width: 180px">
            <template #append>{{ form.unit }}</template>
          </el-input>
          <span class="form-hint inline">提交时按 3 位小数规范化（120 → 120.000）</span>
        </el-form-item>

        <el-form-item label="单位">
          <el-radio-group v-model="form.unit">
            <el-radio-button value="kg">kg</el-radio-button>
            <el-radio-button value="件">件</el-radio-button>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="单价（元）">
          <el-input v-model="form.unitPrice" placeholder="如 24.50" style="width: 180px" />
        </el-form-item>

        <el-form-item label="到货时间">
          <el-date-picker
            v-model="form.arriveTime"
            type="datetime"
            value-format="YYYY-MM-DD HH:mm:ss"
            placeholder="选择到货时间"
            style="width: 100%"
          />
        </el-form-item>

        <el-form-item label="到货中心温度">
          <el-input v-model="form.coldChainTemp" placeholder="如 3.50" style="width: 180px">
            <template #append>℃</template>
          </el-input>
        </el-form-item>

        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="提交后返回「存证待上链」是正常的"
          description="业务数据先入库，链上存证由后台任务异步提交，通常数秒内完成。可在公开溯源页查看核验结果。"
        />
        <div v-if="selectedSupplier" class="form-hint" style="margin-top: 10px">
          当前供应商许可证号：<span class="mono">{{ selectedSupplier.licenseNo || '未登记' }}</span>
        </div>
      </el-form>

      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.zero {
  color: #b0b8c4;
}

.form-hint {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--app-text-sub);
}

.form-hint.inline {
  margin: 0 0 0 12px;
  display: inline-block;
}

.form-hint.warn {
  color: #d97706;
}
</style>
