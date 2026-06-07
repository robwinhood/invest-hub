package com.investhub.application.port.input

import com.investhub.domain.dashboard.ForeignStockPortfolioResult

/**
 * 해외 주식 잔고 단독 조회 유스케이스 (실시간 도메인).
 *
 * 실시간성이 높아 캐시하지 않으며, 모바일의 "당겨서 새로고침"처럼
 * 이 도메인만 독립적으로 자주 갱신하는 시나리오를 위한 입력 포트다.
 */
interface GetForeignStockPortfolioUseCase {
    fun getForeignStockPortfolio(userId: String): ForeignStockPortfolioResult
}
