import { http } from './http'
import type {
  ColdChainCreateRequest,
  ColdChainVO,
  PageQuery,
  PageResult,
} from '@/types/api'

export interface ColdChainQuery extends PageQuery {
  batchNo?: string
  segment?: string
  exceeded?: boolean
}

export const coldChainApi = {
  page: (query: ColdChainQuery) => http.get<PageResult<ColdChainVO>>('/cold-chain-logs', { ...query }),
  detail: (id: number) => http.get<ColdChainVO>(`/cold-chain-logs/${id}`),
  create: (body: ColdChainCreateRequest) => http.post<ColdChainVO>('/cold-chain-logs', body),
}
