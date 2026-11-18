import { http } from './http'
import type {
  AcceptanceCreateRequest,
  AcceptanceVO,
  PageQuery,
  PageResult,
} from '@/types/api'

export interface AcceptanceQuery extends PageQuery {
  batchNo?: string
  result?: string
}

export interface AcceptanceCreateVO {
  acceptNo: string
  batchNo: string
  batchStatus: string
  evidence: { eventId: string | null; status: string | null }
}

export const acceptanceApi = {
  page: (query: AcceptanceQuery) =>
    http.get<PageResult<AcceptanceVO>>('/acceptances', { ...query }),
  create: (body: AcceptanceCreateRequest) =>
    http.post<AcceptanceCreateVO>('/acceptances', body),
}
