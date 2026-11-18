import { http } from './http'
import type {
  PageQuery,
  PageResult,
  SupplierCreateRequest,
  SupplierCreateVO,
  SupplierVO,
} from '@/types/api'

export interface SupplierQuery extends PageQuery {
  keyword?: string
  status?: string
}

export const supplierApi = {
  page: (query: SupplierQuery) =>
    http.get<PageResult<SupplierVO>>('/suppliers', { ...query }),
  detail: (id: number) => http.get<SupplierVO>(`/suppliers/${id}`),
  create: (body: SupplierCreateRequest) => http.post<SupplierCreateVO>('/suppliers', body),
  update: (id: number, body: SupplierCreateRequest) =>
    http.put<SupplierVO>(`/suppliers/${id}`, body),
}
