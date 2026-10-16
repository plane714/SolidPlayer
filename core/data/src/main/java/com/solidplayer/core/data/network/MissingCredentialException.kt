package com.solidplayer.core.data.network

import java.io.IOException

/**
 * 用户尚未配置 AppId/AppSecret（文档 §4.5.3）。
 * 由 [DandanplaySignInterceptor] 抛出，Repository 的 `safeApiCall` 捕获后转为友好提示。
 */
class MissingCredentialException : IOException("弹弹play AppId/AppSecret 尚未配置")
