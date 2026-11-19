/**
 * 状态字典——**全系统唯一出处**。
 *
 * 为什么单独拎一个文件：同样的 PASSED 在采购列表、详情、公开溯源页各写一遍中文，
 * 迟早出现「已验收 / 验收通过 / 合格」三种说法，别人分不清这两个是不是一个状态。
 *
 * 配色口径（Element Plus tag type）：
 *   success=绿（正向终态） info=灰（中性） warning=橙（待处理/需关注） danger=红（异常/拒绝）
 */

export type TagType = 'success' | 'info' | 'warning' | 'danger' | 'primary'

export interface DictItem {
  label: string
  type: TagType
  /** 鼠标悬停时的解释，放上去就能说清楚 */
  hint?: string
}

/** 采购批次状态 */
export const PURCHASE_STATUS: Record<string, DictItem> = {
  PENDING_ACCEPT: { label: '待到货验收', type: 'warning', hint: '已建单，尚未登记验收结果' },
  PASSED: { label: '验收通过', type: 'success', hint: '可投入加工' },
  REJECTED: { label: '整批拒收', type: 'danger', hint: '限制流转，不可投料' },
  CLOSED: { label: '已用尽', type: 'info', hint: '可用余量为 0，自动结案' },
}

/** 加工批次状态 */
export const PROCESS_STATUS: Record<string, DictItem> = {
  CREATED: { label: '待上架', type: 'info', hint: '已投料完成，尚未开放销售' },
  ON_SALE: { label: '在售', type: 'success', hint: '可登记销售' },
  SOLD_OUT: { label: '已售罄', type: 'info', hint: '可用余量为 0' },
  ABNORMAL: { label: '异常冻结', type: 'danger', hint: '销售入口已关闭，待处置' },
  RECALLED: { label: '已召回', type: 'danger', hint: '该批次全部销售记录已标记召回' },
}

/** 销售记录状态 */
export const SALE_STATUS: Record<string, DictItem> = {
  NORMAL: { label: '正常', type: 'success' },
  RECALLED: { label: '已召回', type: 'danger' },
}

/** 异常工单状态 */
export const ABNORMAL_STATUS: Record<string, DictItem> = {
  OPEN: { label: '待处置', type: 'danger', hint: '问题已登记，尚未给出处置结论' },
  HANDLING: { label: '处置中', type: 'warning' },
  CLOSED: { label: '已闭环', type: 'success', hint: '已记录处置措施与结论' },
}

/** 异常等级 */
export const ABNORMAL_LEVEL: Record<string, DictItem> = {
  LOW: { label: '低', type: 'info' },
  MEDIUM: { label: '中', type: 'warning' },
  HIGH: { label: '高', type: 'danger' },
}

/** 异常类型 */
export const ABNORMAL_TYPE: Record<string, DictItem> = {
  TEMPERATURE_EXCEEDED: { label: '冷链温度超限', type: 'warning' },
  SENSORY_ABNORMAL: { label: '感官异常', type: 'danger' },
  CERT_INCOMPLETE: { label: '票证不齐', type: 'warning' },
  EXPIRED: { label: '超过保质期', type: 'danger' },
  OTHER: { label: '其他', type: 'info' },
}

/** 冷链运输段 */
export const COLD_SEGMENT: Record<string, DictItem> = {
  ORIGIN_TO_WAREHOUSE: { label: '产地 → 仓库', type: 'info' },
  WAREHOUSE_TO_CANTEEN: { label: '仓库 → 食堂', type: 'info' },
}

/** 召回单状态 */
export const RECALL_STATUS: Record<string, DictItem> = {
  INITIATED: { label: '已发起', type: 'warning' },
  COMPLETED: { label: '已完成', type: 'success' },
}

/** 供应商状态 */
export const SUPPLIER_STATUS: Record<string, DictItem> = {
  ACTIVE: { label: '正常备案', type: 'success' },
  SUSPENDED: { label: '已暂停', type: 'danger', hint: '暂停期间不可新增采购' },
}

/**
 * 四类核验状态——**全项目最重要的字典**（BR-12 / BR-15）。
 *
 * UNREACHABLE 必须独立存在：链暂时连不上时，如果退化成 MATCHED 是掩盖问题，
 * 退化成 MISMATCHED 则等于诬告数据被篡改。
 */
export const VERIFY_STATUS: Record<string, DictItem> = {
  MATCHED: {
    label: '记录已核验',
    type: 'success',
    hint: '链上存在同一事件，且链上摘要与本地重算的 SHA-256 完全一致',
  },
  MISMATCHED: {
    label: '摘要不一致',
    type: 'danger',
    hint: '链上有记录，但摘要与本地重算结果不同——数据可能被改动过',
  },
  UNCONFIRMED: {
    label: '待上链确认',
    type: 'info',
    hint: '本地已生成快照，但链上尚无对应记录（存证任务未完成）',
  },
  UNREACHABLE: {
    label: '链上暂不可达',
    type: 'warning',
    hint: '查询超时或节点异常，本次无法判断——不代表数据有问题',
  },
}

/** 核验总体的三态 */
export const VERIFY_OVERALL: Record<string, DictItem> = {
  PASS: { label: '全部核验通过', type: 'success' },
  FAIL: { label: '存在摘要不一致', type: 'danger' },
  PARTIAL: { label: '部分待确认', type: 'warning' },
}

/** 存证任务状态 */
export const EVIDENCE_STATUS: Record<string, DictItem> = {
  PENDING: { label: '待上链', type: 'info' },
  SUBMITTING: { label: '提交中', type: 'warning' },
  CONFIRMED: { label: '已上链', type: 'success' },
  FAILED: { label: '失败待重试', type: 'danger' },
  UNREACHABLE: { label: '链不可达', type: 'warning' },
}

export function dictOf(dict: Record<string, DictItem>, key?: string | null): DictItem {
  if (!key) return { label: '—', type: 'info' }
  return dict[key] ?? { label: key, type: 'info' }
}

/** 肉类品类下拉用的固定选项（可自由输入，这里只是常用项） */
export const MEAT_TYPES = ['冷鲜猪肉', '冷鲜牛肉', '冷鲜羊肉', '冷冻猪肉', '冷冻牛肉', '禽肉', '猪肉丸（调理肉制品）']

/** 食堂窗口 */
export const SALE_CHANNELS = ['一食堂 1 号窗口', '一食堂 2 号窗口', '一食堂 3 号窗口', '一食堂 5 号窗口', '二食堂 1 号窗口', '二食堂 2 号窗口']

/** 加工车间 */
export const WORKSHOPS = ['一食堂后厨', '二食堂后厨', '中央厨房']
