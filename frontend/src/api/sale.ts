import { http } from './http'
import type {
  PageQuery,
  PageResult,
  SaleCreateRequest,
  SaleCreateVO,
  SaleVO,
} from '@/types/api'

export interface SaleQuery extends PageQuery {
  processBatchId?: number
  processBatchNo?: string
  channel?: string
  status?: string
}

export const saleApi = {
  page: (query: SaleQuery) => http.get<PageResult<SaleVO>>('/sales', { ...query }),
  detail: (id: number) => http.get<SaleVO>(`/sales/${id}`),
  create: (body: SaleCreateRequest) => http.post<SaleCreateVO>('/sales', body),
}
