package com.investhub.application.port.output

/**
 * 캐시에 현재 보관된 엔트리 키를 열거할 수 있는 캐시의 능력.
 *
 * 표준 `org.springframework.cache.Cache`는 키 열거를 보장하지 않는다.
 * 운영/어드민 화면에서 "이 캐시에 어떤 키가 들어 있는가"를 보여주려면
 * 캐시 구현이 이 능력을 추가로 제공해야 한다.
 *
 * 구현체: [com.investhub.adapter.out.cache.TwoTierCache].
 * (애플리케이션 레이어의 [com.investhub.application.service.CacheInvalidationService]가
 *  이 인터페이스로 다운캐스트해 키 목록을 조회한다 — 어댑터 구체 타입에 의존하지 않는다.)
 */
interface CacheKeyEnumerable {
    /** 현재 보관 중인 엔트리 키 목록(문자열). 만료된 키는 제외한다. */
    fun keys(): Set<String>
}
