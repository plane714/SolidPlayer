package com.solidplayer.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * 应用数据库（文档 §7）。
 *
 * `exportSchema = true` + `room.schemaLocation` ksp 参数（见 build.gradle.kts）会把 schema JSON
 * 输出到 `core/data/schemas/`，纳入版本管理，方便日后写 Migration 时逐版本比对。
 */
@Database(
    entities = [
        MediaFileEntity::class,
        AnimeEntity::class,
        EpisodeEntity::class,
        DanmakuCacheEntity::class,
        PlayHistoryEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class SolidPlayerDatabase : RoomDatabase() {

    abstract fun mediaFileDao(): MediaFileDao

    abstract fun danmakuCacheDao(): DanmakuCacheDao

    abstract fun playHistoryDao(): PlayHistoryDao

    abstract fun animeDao(): AnimeDao

    companion object {
        const val NAME = "solidplayer.db"
    }
}
