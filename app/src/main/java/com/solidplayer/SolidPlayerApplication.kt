package com.solidplayer

import android.app.Application
import com.solidplayer.core.data.repository.DanmakuCacheJanitor
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class SolidPlayerApplication : Application() {

    @Inject
    lateinit var cacheJanitor: DanmakuCacheJanitor

    override fun onCreate() {
        super.onCreate()
        // 冷启动时顺手清一遍过期弹幕缓存（P2）。异步执行，不影响首帧。
        cacheJanitor.pruneOnStart()
    }
}
