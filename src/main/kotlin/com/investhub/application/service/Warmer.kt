package com.investhub.application.service

/**
 * 웜업 작업 단위 인터페이스.
 *
 * 구현체는 Spring Bean으로 등록하면 [WarmupService]가 자동으로 수집한다.
 * 각 구현체는 독립적으로 실패해도 전체 웜업을 중단시키지 않는다.
 */
interface Warmer {
    fun warm()

    fun name(): String = this.javaClass.simpleName
}
