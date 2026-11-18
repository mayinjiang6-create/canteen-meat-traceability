import { http } from './http'
import type {
  PageQuery,
  PageResult,
  ProcessCreateRequest,
  ProcessCreateVO,
  ProcessVO,
} from '@/types/api'

export interface ProcessQuery extends PageQuery {
  processNo?: string
  status?: string
  meatType?: string
}

export const processApi = {
  page: (query: ProcessQuery) => http.get<PageResult<ProcessVO>>('/process-batches', { ...query }),
  detail: (id: number) => http.get<ProcessVO>(`/process-batches/${id}`),
  create: (body: ProcessCreateRequest) =>
    http.post<ProcessCreateVO>('/process-batches', body),
  onSale: (id: number) => http.post<ProcessVO>(`/process-batches/${id}/onsale`, {}),
}
