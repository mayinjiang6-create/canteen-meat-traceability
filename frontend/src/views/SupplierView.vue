<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { supplierApi, type SupplierQuery } from '@/api/supplier'
import { errText } from '@/api/http'
import { SUPPLIER_STATUS } from '@/constants/dict'
import { useAuthStore } from '@/stores/auth'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import type { SupplierCreateRequest, SupplierVO } from '@/types/api'

const auth = useAuthStore()

const loading = ref(false)
const rows = ref<SupplierVO[]>([])
const total = ref(0)

const query = reactive<SupplierQuery>({ page: 1, size: 20, keyword: '', status: '' })

async function load() {
  loading.value = true
  try {
    const data = await supplierApi.page({ ...query })
    rows.value = data.records
    total.value = data.total
  } catch {
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

onMounted(load)

function search() {
  query.page = 1
  load()
}

function reset() {
  query.keyword = ''
  query.status = ''
  query.page = 1
  load()
}

/* ------------------------------ 新增 / 编辑 ------------------------------ */

const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const submitting = ref(false)
const formRef = ref<FormInstance>()

const blankForm = (): SupplierCreateRequest => ({
  name: '',
  creditCode: '',
  licenseNo: '',
  contactName: '',
  contactPhone: '',
  address: '',
})

const form = reactive<SupplierCreateRequest>(blankForm())

const rules: FormRules = {
  name: [{ required: true, message: '请输入供应商名称', trigger: 'blur' }],
  creditCode: [{ pattern: /^[0-9A-Z]{18}$/, message: '统一社会信用代码为 18 位大写字母或数字', trigger: 'blur' }],
  contactPhone: [{ pattern: /^1[3-9]\d{9}$/, message: '请填写 11 位手机号', trigger: 'blur' }],
}

function openCreate() {
  editingId.value = null
  Object.assign(form, blankForm())
  dialogVisible.value = true
}

function openEdit(row: SupplierVO) {
  editingId.value = row.id
  Object.assign(form, {
    name: row.name,
    creditCode: row.creditCode || '',
    licenseNo: row.licenseNo || '',
    contactName: row.contactName || '',
    contactPhone: row.contactPhone || '',
    address: row.address || '',
  })
  dialogVisible.value = true
}

async function submit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    if (editingId.value == null) {
      const created = await supplierApi.create({ ...form })
      ElMessage.success(`备案成功，供应商编码 ${created.supplierCode}`)
    } else {
      await supplierApi.update(editingId.value, { ...form })
      ElMessage.success('已保存修改')
    }
    dialogVisible.value = false
    load()
  } catch (err) {
    // 后端已给出人话提示（如"统一社会信用代码已存在"），这里不再叠一层
    ElMessage.error(errText(err))
  } finally {
    submitting.value = false
  }
}

function fmtDate(v?: string | null) {
  return v ? v.replace('T', ' ').slice(0, 19) : '—'
}
</script>

<template>
  <div class="page">
    <PageHeader
      title="供应商备案"
      desc="食堂所有肉类来源必须先在册。备案信息中的许可证号会出现在公开溯源页上，供消费者核对。"
    >
      <template #actions>
        <el-button v-if="auth.isAdmin" type="primary" @click="openCreate">
          <el-icon><Plus /></el-icon>
          <span style="margin-left: 4px">新增备案</span>
        </el-button>
      </template>
    </PageHeader>

    <div class="panel">
      <div class="toolbar">
        <el-input
          v-model="query.keyword"
          placeholder="搜索名称 / 编码 / 许可证号"
          clearable
          style="width: 260px"
          @keyup.enter="search"
          @clear="search"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>

        <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 150px" @change="search">
          <el-option label="正常备案" value="ACTIVE" />
          <el-option label="已暂停" value="SUSPENDED" />
        </el-select>

        <el-button type="primary" plain @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
        <div class="grow" />
        <span class="text-sub" style="font-size: 12.5px">共 {{ total }} 家</span>
      </div>

      <el-table v-loading="loading" :data="rows" border stripe size="default" empty-text="暂无供应商">
        <el-table-column prop="supplierCode" label="编码" width="104">
          <template #default="{ row }"><span class="mono">{{ row.supplierCode }}</span></template>
        </el-table-column>
        <el-table-column prop="name" label="供应商名称" min-width="200" show-overflow-tooltip />
        <el-table-column prop="licenseNo" label="许可证号" width="130">
          <template #default="{ row }"><span class="mono">{{ row.licenseNo || '—' }}</span></template>
        </el-table-column>
        <el-table-column prop="creditCode" label="统一社会信用代码" width="190" show-overflow-tooltip>
          <template #default="{ row }"><span class="mono">{{ row.creditCode || '—' }}</span></template>
        </el-table-column>
        <el-table-column prop="contactName" label="联系人" width="90">
          <template #default="{ row }">{{ row.contactName || '—' }}</template>
        </el-table-column>
        <el-table-column prop="contactPhone" label="联系电话" width="128">
          <template #default="{ row }"><span class="mono">{{ row.contactPhone || '—' }}</span></template>
        </el-table-column>
        <el-table-column prop="address" label="地址" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.address || '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="106" align="center">
          <template #default="{ row }">
            <StatusTag :value="row.status" :dict="SUPPLIER_STATUS" />
          </template>
        </el-table-column>
        <el-table-column label="备案时间" width="158">
          <template #default="{ row }"><span class="mono text-sub">{{ fmtDate(row.createdAt) }}</span></template>
        </el-table-column>
        <el-table-column v-if="auth.isAdmin" label="操作" width="80" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
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

    <!-- 新增 / 编辑 -->
    <el-dialog
      v-model="dialogVisible"
      :title="editingId == null ? '新增供应商备案' : '编辑供应商备案'"
      width="560px"
      destroy-on-close
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="126px">
        <el-form-item label="供应商名称" prop="name">
          <el-input v-model="form.name" maxlength="100" placeholder="如：江西绿源肉业有限公司" />
        </el-form-item>
        <el-form-item label="统一社会信用代码" prop="creditCode">
          <el-input v-model="form.creditCode" maxlength="18" placeholder="18 位，可留空" />
        </el-form-item>
        <el-form-item label="食品经营许可证号" prop="licenseNo">
          <el-input v-model="form.licenseNo" maxlength="50" placeholder="如：JX20240001" />
        </el-form-item>
        <el-form-item label="联系人" prop="contactName">
          <el-input v-model="form.contactName" maxlength="50" />
        </el-form-item>
        <el-form-item label="联系电话" prop="contactPhone">
          <el-input v-model="form.contactPhone" maxlength="20" />
        </el-form-item>
        <el-form-item label="地址" prop="address">
          <el-input v-model="form.address" maxlength="200" />
        </el-form-item>
        <el-alert
          v-if="editingId == null"
          type="info"
          :closable="false"
          show-icon
          title="供应商编码由系统发号器生成（SUP-0001 递增），不接受手工填写，避免并发下撞唯一键。"
        />
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">
          {{ editingId == null ? '提交备案' : '保存' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>
