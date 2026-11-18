import { http } from './http'
import type {
  AbnormalCreateRequest,
  AbnormalDisposeRequest,
  AbnormalVO,
  PageQuery,
  PageResult,
} from '@/types/api'

export interface AbnormalQuery extends PageQuery {
  processBatchNo?: string
  batchNo?: string
  status?: string
  abnormalType?: string
}

export const abnormalApi = {
  page: (query: AbnormalQuery) => http.get<PageResult<AbnormalVO>>('/abnormal-events', { ...query }),
  detail: (id: number) => http.get<AbnormalVO>(`/abnormal-events/${id}`),
  create: (body: AbnormalCreateRequest) => http.post<AbnormalVO>('/abnormal-events', body),
  dispose: (id: number, body: AbnormalDisposeRequest) =>
    http.post<AbnormalVO>(`/abnormal-events/${id}/dispose`, body),
}
