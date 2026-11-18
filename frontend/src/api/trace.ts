import { http } from './http'
import type { TraceResponse, VerifyResponse } from '@/types/api'

/**
 * 公开溯源接口——**免鉴权**。
 * 二维码扫出来直接进这个地址，所以这里绝不能带 token 依赖。
 */
export const traceApi = {
  trace: (batchNo: string) => http.get<TraceResponse>(`/public/trace/${encodeURIComponent(batchNo)}`),
  verify: (batchNo: string) =>
    http.get<VerifyResponse>(`/public/trace/${encodeURIComponent(batchNo)}/verify`, undefined, {
      silent: true,
    }),
}
