/**
 * 后端出参类型定义。
 * 口径来源：backend/src/main/java/com/jxgc/meattrace/module/*／dto/*.java
 *
 * 铁律：数量 / 金额一律 string。后端刻意用字符串返回（见 TECH-03 §1.3），
 * 前端若转 number 再拼回去，120.001 会变成 120.00099999999999，摘要立刻对不上。
 */

/** 统一返回体 */
export interface R<T> {
  code: number
  message: string
  data: T
  traceId: string
  timestamp: string
}

/** 分页出参 */
export interface PageResult<T> {
  page: number
  size: number
  total: number
  records: T[]
}

/** 分页入参 */
export interface PageQuery {
  page?: number
  size?: number
  sort?: string
}

export interface UserVO {
  id: number
  username: string
  displayName: string
  role: 'ADMIN' | 'SUPPLIER' | 'VIEWER'
  supplierId?: number | null
}

export interface LoginResponse {
  token: string
  expiresIn: number
  user: UserVO
}

/** 存证进度，随写接口一起返回 */
export interface EvidenceInfo {
  eventId: string | null
  status: 'PENDING' | 'CONFIRMED' | 'FAILED' | 'UNREACHABLE' | string | null
}

export interface SupplierVO {
  id: number
  supplierCode: string
  name: string
  creditCode: string | null
  licenseNo: string | null
  contactName: string | null
  contactPhone: string | null
  address: string | null
  status: 'ACTIVE' | 'SUSPENDED' | string
  createdAt: string
}

export interface SupplierCreateRequest {
  name: string
  creditCode?: string
  licenseNo?: string
  contactName?: string
  contactPhone?: string
  address?: string
}

export interface SupplierCreateVO {
  id: number
  supplierCode: string
  name: string
  status: string
  replayed?: boolean
}

export interface PurchaseVO {
  id: number
  batchNo: string
  supplierId: number
  supplierName: string | null
  meatType: string
  origin: string | null
  slaughterNo: string | null
  quarantineNo: string
  quantity: string
  availableQty: string
  unit: string
  unitPrice: string | null
  arriveTime: string
  coldChainTemp: string | null
  status: PurchaseStatus
  createdAt: string
}

export type PurchaseStatus =
  | 'PENDING_ACCEPT'
  | 'PASSED'
  | 'REJECTED'
  | 'CLOSED'
  | string

export interface PurchaseCreateRequest {
  supplierId: number
  meatType: string
  origin?: string
  slaughterNo?: string
  quarantineNo: string
  quantity: string
  unit?: string
  unitPrice?: string
  arriveTime?: string
  coldChainTemp?: string
}

export interface PurchaseCreateVO {
  id: number
  batchNo: string
  status: string
  availableQty: string
  evidence: EvidenceInfo
  replayed?: boolean
}

export interface AcceptanceVO {
  id: number
  acceptNo: string
  batchId: number
  batchNo: string
  acceptTime: string
  inspector: string
  temperature: string | null
  appearanceOk: boolean
  certComplete: boolean
  sampleRetained: boolean
  result: 'PASS' | 'FAIL' | string
  rejectReason: string | null
  batchStatus: string
  createdAt: string
}

export interface AcceptanceCreateRequest {
  batchId: number
  acceptTime?: string
  inspector: string
  temperature?: string
  appearanceOk: boolean
  certComplete: boolean
  sampleRetained: boolean
  result: 'PASS' | 'FAIL'
  rejectReason?: string
}

export interface ProcessSourceVO {
  purchaseBatchNo: string
  usedQty: string
  remainQty: string
  supplierName: string | null
}

export interface ProcessVO {
  id: number
  processNo: string
  meatType: string
  outputQty: string
  availableQty: string
  inputQty: string | null
  sourceCount: number | null
  unit: string
  processTime: string
  workshop: string | null
  operator: string | null
  retainSampleNo: string | null
  status: ProcessStatus
  createdAt: string
  sources: ProcessSourceVO[] | null
}

export type ProcessStatus = 'CREATED' | 'ON_SALE' | 'SOLD_OUT' | 'ABNORMAL' | 'RECALLED' | string

export interface ProcessSourceItem {
  purchaseBatchNo: string
  usedQty: string
}

export interface ProcessCreateRequest {
  meatType: string
  processTime?: string
  workshop?: string
  operator?: string
  retainSampleNo?: string
  outputQty: string
  sources: ProcessSourceItem[]
}

export interface ProcessCreateVO {
  id: number
  processNo: string
  status: string
  inputQty: string
  outputQty: string
  sources: ProcessSourceVO[]
  evidence: EvidenceInfo
  replayed?: boolean
}

export interface SaleVO {
  id: number
  saleNo: string
  processNo: string
  meatType: string | null
  channel: string | null
  quantity: string
  unitPrice: string | null
  amount: string | null
  saleTime: string
  operator: string | null
  status: 'NORMAL' | 'RECALLED' | string
}

export interface SaleCreateRequest {
  processBatchNo: string
  channel?: string
  quantity: string
  unitPrice?: string
  saleTime?: string
  operator?: string
}

export interface SaleCreateVO {
  id: number
  saleNo: string
  processNo: string
  channel: string | null
  quantity: string
  unitPrice: string | null
  amount: string | null
  saleTime: string
  operator: string | null
  status: string
  processStatus: string
  availableQty: string
  evidence: EvidenceInfo
  replayed?: boolean
}

export interface ColdChainVO {
  id: number
  logNo: string
  batchNo: string
  meatType: string | null
  segment: 'ORIGIN_TO_WAREHOUSE' | 'WAREHOUSE_TO_CANTEEN' | string
  transportNo: string | null
  temperature: string
  threshold: string | null
  exceeded: boolean
  recordedAt: string
  recorder: string | null
  remark: string | null
  abnormalNo: string | null
  evidence: EvidenceInfo
}

export interface ColdChainCreateRequest {
  batchNo: string
  segment: string
  transportNo?: string
  temperature: string
  recordedAt?: string
  recorder?: string
  remark?: string
}

export interface AbnormalVO {
  id: number
  abnormalNo: string
  purchaseBatchNo: string | null
  processNo: string | null
  abnormalType: string
  level: 'LOW' | 'MEDIUM' | 'HIGH' | string
  description: string
  action: string | null
  handler: string | null
  conclusion: string | null
  status: 'OPEN' | 'HANDLING' | 'CLOSED' | string
  occurredAt: string
  closedAt: string | null
  createdAt: string
  evidence: EvidenceInfo
}

export interface AbnormalCreateRequest {
  processBatchNo?: string
  batchNo?: string
  abnormalType: string
  level?: string
  description: string
  occurredAt?: string
  handler?: string
}

export interface AbnormalDisposeRequest {
  action: string
  handler?: string
  conclusion: string
  resumeSale?: boolean
  disposeTime?: string
}

export interface RecallVO {
  id: number
  recallNo: string
  processNo: string
  meatType: string | null
  reason: string
  scope: string | null
  affectedSales: number | null
  recalledQty: string | null
  operator: string | null
  recallTime: string
  status: string
  processStatus: string | null
  evidence: EvidenceInfo
  replayed: boolean | null
}

export interface RecallCreateRequest {
  processBatchNo: string
  reason: string
  scope?: string
  operator?: string
  recallTime?: string
  remark?: string
}

/* ------------------------------------------------------------------ 公开溯源 */

export interface SupplierBrief {
  name: string
  licenseNo: string | null
  status: string
}

export interface TraceSummary {
  sourceCount: number
  totalInputQty: string | null
  outputQty: string | null
  soldQty: string | null
  quarantineNos: string[]
}

export interface TraceConclusion {
  inspectionResult: 'PASS' | 'FAIL' | null
  inspectionNote: string | null
  source: string | null
}

export type VerifyStatus = 'MATCHED' | 'MISMATCHED' | 'UNCONFIRMED' | 'UNREACHABLE'

export interface TimelineItem {
  eventId: string
  eventType: string
  eventLabel: string
  bizTime: string
  digest: string
  verifyStatus: VerifyStatus
  txHash: string | null
  blockNumber: number | null
  batchNo: string
  details: Record<string, unknown> | null
}

export interface VerifySummary {
  matched: number
  mismatched: number
  unconfirmed: number
  unreachable: number
  overall: 'PASS' | 'FAIL' | 'PARTIAL'
  checkedAt: string
}

export interface TraceResponse {
  batchNo: string
  batchType: 'PURCHASE' | 'PROCESS' | string
  meatType: string
  supplier: SupplierBrief | null
  summary: TraceSummary
  conclusion: TraceConclusion
  timeline: TimelineItem[]
  verifySummary: VerifySummary
}

export interface VerifyEventItem {
  eventId: string
  verifyStatus: VerifyStatus
}

export interface VerifyResponse {
  batchNo: string
  overall: 'PASS' | 'FAIL' | 'PARTIAL'
  counts: {
    matched: number
    mismatched: number
    unconfirmed: number
    unreachable: number
  }
  events: VerifyEventItem[]
}
