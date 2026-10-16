package com.solidplayer.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 数据库迁移（v1 → v2）。
 *
 * v2 是为「自动发现的媒体库」加列：媒体库首页需要**按目录聚合**出「文件夹 + N 个视频」，
 * 目录信息必须跟着文件一起落库 —— 否则每次进首页都得把全机重扫一遍。
 *
 * **旧数据的处理是刻意的**：v1 里用 SAF 扫进来的行，`folderPath` 一律置为 `NULL`
 * （不加默认值、也不从 `relativePath` 回填）。原因是 v1 的 `relativePath` 是
 * 「相对用户所选目录树根」的路径，与 v2 的「相对存储根」不是同一套语义，
 * 强行回填会造出一堆名字诡异、点进去却是空的「文件夹」。
 * 这些行留在库里不参与新的文件夹页，等用户扫到同一文件时会以新的绝对路径重新入库。
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE media_file ADD COLUMN folderPath TEXT")
        db.execSQL("ALTER TABLE media_file ADD COLUMN folderName TEXT")
        db.execSQL("ALTER TABLE media_file ADD COLUMN dateAdded INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE media_file ADD COLUMN subtitleFormats TEXT")
        db.execSQL("ALTER TABLE media_file ADD COLUMN source TEXT")
        db.execSQL("ALTER TABLE media_file ADD COLUMN lastSeenAt INTEGER NOT NULL DEFAULT 0")
        // 索引名必须与 Room 生成的一致（index_<表>_<列>），否则 schema 校验会不通过
        db.execSQL("CREATE INDEX IF NOT EXISTS index_media_file_folderPath ON media_file(folderPath)")
        db.execSQL("UPDATE media_file SET source = 'saf' WHERE source IS NULL")
    }
}

/**
 * 数据库迁移（v2 → v3）。
 *
 * v3 为「NEW 角标点开即消」加一列 `lastViewedAt`：
 * v2 的 NEW 只看 `dateAdded`（7 天窗口），看过与否完全不参与，于是角标里的数字
 * 既不会因为看过而减少，也不能回答「还剩几个没看」。
 *
 * **默认值 0 = 「从未看过」是刻意的**：迁移上来的老行因此全部保持 NEW 语义不变
 * （仍然由 `dateAdded` 那半边条件决定），不会因为升级而凭空冒出一堆 NEW。
 * `ALTER TABLE … ADD COLUMN … NOT NULL` 在非空表上必须带 `DEFAULT`，这也是实体侧
 * 标 `@ColumnInfo(defaultValue = "0")` 的原因（两侧不一致 schema 校验会失败）。
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE media_file ADD COLUMN lastViewedAt INTEGER NOT NULL DEFAULT 0")
    }
}
