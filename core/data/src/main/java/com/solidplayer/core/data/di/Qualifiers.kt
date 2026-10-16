package com.solidplayer.core.data.di

import javax.inject.Qualifier

/**
 * 注意：这三处**刻意不声明**显式 `@Target(PROPERTY)`。
 *
 * Kotlin 2.x 对「构造参数上的注解」只作用于 value parameter，会给出 KT-73255 警告；
 * 但一旦显式加上 `AnnotationTarget.PROPERTY`，注解会被同时写入 property/field，
 * Dagger(KSP) 便无法再把 `@BareClient OkHttpClient` 与无限定符的 `OkHttpClient`
 * 区分开，直接报 `[Dagger/DuplicateBindings]`（已实测）。故保持默认 target。
 */

/** 应用级 CoroutineScope，生命周期与进程一致（用于凭据缓存预热等长驻任务）。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/** IO 调度器。约定：所有磁盘 / 网络 I/O 必须显式切到这个调度器（文档 §14）。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/**
 * **不带签名拦截器**的裸 OkHttpClient。
 * 用于「测试连接」手工构造请求 —— 否则签名拦截器会覆盖掉手工设置的请求头（文档 §4.5.5）。
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BareClient
