import { http } from './http'
import type {
  PageQuery,
  PageResult,
  PurchaseCreateRequest,
  PurchaseCreateVO,
  PurchaseVO,
} from '@/types/api'

export interface PurchaseQuery extends PageQuery {
  batchNo?: string
  supplierId?: number
  status?: string
  meatType?: string
}

export const purchaseApi = {
  page: (query: PurchaseQuery) => http.get<PageResult<PurchaseVO>>('/purchases', { ...query }),
  detail: (id: number) => http.get<PurchaseVO>(`/purchases/${id}`),
  create: (body: PurchaseCreateRequest) => http.post<PurchaseCreateVO>('/purchases', body),
}
