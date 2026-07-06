import type { AdmissionNotice, QueuePositionResponse } from './types'

/**
 * 대기열 SSE 구독 클라이언트.
 *
 * 브라우저 내장 EventSource 는 커스텀 헤더(X-Member-Id)를 붙일 수 없어,
 * fetch 스트리밍으로 text/event-stream 을 직접 파싱한다.
 */
export interface QueueStreamHandlers {
  onPosition: (data: QueuePositionResponse) => void
  onAdmitted: (data: AdmissionNotice) => void
  /** 스트림이 admitted 없이 닫힘(서버 emitter 타임아웃 등) — 호출측이 재구독을 결정한다. */
  onClose: () => void
  onError: (err: unknown) => void
}

export async function subscribeQueueStream(
  goodsId: number,
  memberId: number,
  handlers: QueueStreamHandlers,
  signal: AbortSignal,
): Promise<void> {
  try {
    const res = await fetch(`/api/v1/queue/${goodsId}/subscribe`, {
      headers: { 'X-Member-Id': String(memberId), Accept: 'text/event-stream' },
      signal,
    })
    if (!res.ok || !res.body) {
      res.body?.cancel().catch(() => {})   // 미소비 응답 본문의 커넥션 잔류 방지
      handlers.onError(new Error(`SSE 연결 실패 (HTTP ${res.status})`))
      return
    }

    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    let admitted = false

    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })

      // 이벤트 경계는 빈 줄(\n\n 또는 \r\n\r\n)이다.
      let boundary: number
      while ((boundary = buffer.search(/\r?\n\r?\n/)) >= 0) {
        const rawEvent = buffer.slice(0, boundary)
        buffer = buffer.slice(boundary).replace(/^\r?\n\r?\n/, '')

        let eventName = 'message'
        const dataLines: string[] = []
        for (const line of rawEvent.split(/\r?\n/)) {
          if (line.startsWith('event:')) eventName = line.slice(6).trim()
          else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
        }
        if (dataLines.length === 0) continue

        // 이벤트 단위 파싱 가드: 비정상 프레임 1건이 스트림 전체를 죽이지 않게 해당 이벤트만 스킵.
        let data
        try {
          data = JSON.parse(dataLines.join('\n'))
        } catch {
          continue
        }
        if (eventName === 'position') handlers.onPosition(data)
        else if (eventName === 'admitted') {
          admitted = true
          handlers.onAdmitted(data)
        }
      }
      if (admitted) {
        reader.cancel().catch(() => {})   // 이탈 통지 겸 리소스 정리(언마운트 abort 에만 의존하지 않음)
        return // admitted 후 스트림은 서버가 닫는다 — 더 읽지 않는다.
      }
    }
    handlers.onClose()
  } catch (err) {
    if (signal.aborted) return // 화면 이탈로 인한 정상 중단.
    handlers.onError(err)
  }
}
