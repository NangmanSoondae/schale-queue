/** 공통 fetch 래퍼. 실패 응답(ErrorResponse{code,message})을 ApiError 로 승격한다. */

export class ApiError extends Error {
  readonly status: number
  readonly code: string

  constructor(status: number, code: string, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

interface ApiOptions extends RequestInit {
  /** X-Member-Id 헤더로 전달할 회원 식별자(인증 아님 — 백엔드 규약). */
  memberId?: number
}

export async function api<T>(path: string, options: ApiOptions = {}): Promise<T> {
  const { memberId, headers, ...rest } = options
  const res = await fetch(path, {
    ...rest,
    headers: {
      'Content-Type': 'application/json',
      ...(memberId != null ? { 'X-Member-Id': String(memberId) } : {}),
      ...headers,
    },
  })
  if (!res.ok) {
    let code = 'UNKNOWN'
    let message = `요청 실패 (HTTP ${res.status})`
    try {
      const body = await res.json()
      code = body.code ?? code
      message = body.message ?? message
    } catch {
      // 본문이 JSON 이 아니면 기본 메시지를 유지한다.
    }
    throw new ApiError(res.status, code, message)
  }
  return res.status === 204 ? (undefined as T) : res.json()
}
