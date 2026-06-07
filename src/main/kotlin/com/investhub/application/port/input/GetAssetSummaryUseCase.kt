package com.investhub.application.port.input

import com.investhub.domain.dashboard.AssetSummaryResult

/**
 * 내 계좌/자산 단독 조회 유스케이스.
 *
 * 집계 엔드포인트(`/dashboard`)와 별개로, 자산 도메인만 독립적으로 조회·갱신하기 위한 입력 포트다.
 * [com.investhub.domain.dashboard.AssetSummaryResult] (Sealed)를 반환해
 * 부분 실패를 호출 측(컨트롤러·집계 서비스)이 일관되게 처리하도록 한다.
 */
interface GetAssetSummaryUseCase {
    fun getAssetSummary(userId: String): AssetSummaryResult
}
