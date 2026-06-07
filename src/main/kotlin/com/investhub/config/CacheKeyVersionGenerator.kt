package com.investhub.config

import io.github.oshai.kotlinlogging.KotlinLogging
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.full.declaredMemberProperties

/**
 * 캐시 키에 데이터 클래스 구조 기반 버전을 자동 삽입하는 유틸리티.
 *
 * **문제**: Redis(영속 캐시)에서 직렬화된 클래스에 필드가 추가/삭제되면
 * 구 포맷 JSON과 새 클래스가 충돌해 역직렬화 오류가 발생한다.
 * 이를 방지하기 위해 개발자가 수동으로 키 버전을 관리하면 휴먼 에러가 발생한다.
 *
 * **해결**: 클래스의 프로퍼티(이름+타입)를 SHA-256으로 해시해 키에 삽입한다.
 * 클래스 구조가 변경되면 해시도 자동으로 변경되어 새 키를 사용하게 된다.
 * 개발자가 버전을 관리할 필요가 없다.
 *
 * **사용 예시**:
 * ```kotlin
 * // InvestmentProduct 필드 구조 기반으로 캐시 이름 자동 버전화
 * val name = CacheKeyVersionGenerator.versionedName("recommendations", InvestmentProduct::class)
 * // → "recommendations:v1a2b3c4" (InvestmentProduct 구조에서 결정)
 *
 * // InvestmentProduct에 필드 추가 시:
 * // → "recommendations:v5e6f7a8b" (자동 변경 — 사람 손 불필요)
 * ```
 *
 * **현재**: Caffeine 인메모리 캐시에 적용 (재시작 시 자동 소멸이라 충돌 없음).
 * **향후**: Redis 도입 시 동일 메커니즘으로 키 충돌 자동 방지.
 */
object CacheKeyVersionGenerator {
    private val versionCache = ConcurrentHashMap<String, String>()
    private val log = KotlinLogging.logger {}

    /**
     * 기본 이름과 클래스 구조 해시를 결합한 버전화된 캐시 이름을 반환한다.
     *
     * @param baseName  기본 캐시 이름 (예: "recommendations")
     * @param clazz     캐시할 데이터 클래스 (예: InvestmentProduct::class)
     * @return 버전화된 이름 (예: "recommendations:v1a2b3c4")
     */
    fun <T : Any> versionedName(
        baseName: String,
        clazz: KClass<T>,
    ): String = "$baseName:${getVersion(clazz)}"

    /**
     * 클래스 구조(프로퍼티 이름+타입)를 기반으로 버전 문자열을 반환한다.
     * 같은 클래스는 항상 같은 버전을 반환하며, 결과는 메모리에 캐시된다.
     */
    fun <T : Any> getVersion(clazz: KClass<T>): String {
        val className = clazz.qualifiedName ?: clazz.simpleName ?: return "v1"
        return versionCache.getOrPut(className) {
            computeVersion(clazz)
        }
    }

    private fun <T : Any> computeVersion(clazz: KClass<T>): String =
        runCatching {
            val signature = buildSignature(clazz, mutableSetOf())
            val hash =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(signature.toByteArray())
                    .take(4)
                    .joinToString("") { "%02x".format(it) }
            "v$hash"
        }.getOrElse { ex ->
            log.warn(ex) { "캐시 키 버전 생성 실패: ${clazz.simpleName} — fallback 'v1' 사용" }
            "v1"
        }

    private fun buildSignature(
        clazz: KClass<*>,
        visited: MutableSet<KClass<*>>,
    ): String {
        if (!visited.add(clazz)) {
            // 순환 참조 방지 (예: SelfReferenceClass(val parent: SelfReferenceClass?))
            return "@visited:${clazz.qualifiedName ?: clazz.simpleName ?: "Unknown"}"
        }

        return buildString {
            append(clazz.qualifiedName ?: clazz.simpleName)
            append("::")

            clazz.declaredMemberProperties
                .sortedBy { it.name }
                .forEach { prop ->
                    append(prop.name)
                    append(":")
                    append(prop.returnType.toString())
                    append(";")

                    // 중첩 객체 재귀 처리
                    val directType = prop.returnType.classifier as? KClass<*>
                    if (isRecursible(directType)) {
                        append("{")
                        append(buildSignature(directType!!, visited))
                        append("}")
                    }

                    // 제네릭 인자 처리 (예: List<InvestmentProduct>)
                    prop.returnType.arguments.forEach { arg ->
                        val nested = arg.type?.classifier as? KClass<*>
                        if (isRecursible(nested)) {
                            append("{GENERIC:")
                            append(buildSignature(nested!!, visited))
                            append("}")
                        }
                    }
                }
        }
    }

    private fun isRecursible(clazz: KClass<*>?): Boolean =
        clazz != null && clazz.javaPrimitiveType == null && clazz != String::class
}
