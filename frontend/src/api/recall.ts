import { http } from './http'
import type {
  PageQuery,
  PageResult,
  RecallCreateRequest,
  RecallVO,
} from '@/types/api'

export interface RecallQuery extends PageQuery {
  processBatchNo?: string
  status?: string
}

export const recallApi = {
  page: (query: RecallQuery) => http.get<PageResult<RecallVO>>('/recalls', { ...query }),
  detail: (id: number) => http.get<RecallVO>(`/recalls/${id}`),
  create: (body: RecallCreateRequest) => http.post<RecallVO>('/recalls', body),
}
