package com.solidplayer.core.data.di

import android.content.ContentResolver
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.solidplayer.core.data.credential.DandanplayCredentialStore
import com.solidplayer.core.data.db.MIGRATION_1_2
import com.solidplayer.core.data.db.MIGRATION_2_3
import com.solidplayer.core.data.db.SolidPlayerDatabase
import com.solidplayer.core.data.hash.FileHashCalculator
import com.solidplayer.core.data.network.DandanplayApi
import com.solidplayer.core.data.network.DandanplaySignInterceptor
import com.solidplayer.core.data.scanner.MediaScanner
import com.solidplayer.core.data.scanner.MediaStoreVideoScanner
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import okhttp3.MediaType.Companion.toMediaType
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * 基础设施装配（文档 §3.2「基础设施」层）。
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        // 服务端随时可能加字段，未知字段必须忽略，否则整个响应解析失败
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun provideContentResolver(@ApplicationContext context: Context): ContentResolver =
        context.contentResolver

    @Provides
    @Singleton
    fun provideMediaScanner(contentResolver: ContentResolver): MediaScanner =
        MediaScanner(contentResolver)

    /** 媒体库自动发现用的 MediaStore 索引读取器（[com.solidplayer.core.data.scanner.VideoScanner] 用到）。 */
    @Provides
    @Singleton
    fun provideMediaStoreVideoScanner(contentResolver: ContentResolver): MediaStoreVideoScanner =
        MediaStoreVideoScanner(contentResolver)

    @Provides
    @Singleton
    fun provideFileHashCalculator(contentResolver: ContentResolver): FileHashCalculator =
        FileHashCalculator(contentResolver)

    /** 应用级协程作用域，生命周期与进程一致（凭据缓存预热等长驻任务用） */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    /**
     * 凭据落在 DataStore Preferences，位于应用私有目录。
     * manifest 已设 `allowBackup=false` 并排除 datastore 目录，凭据不会被云备份带出（文档 §12.1）。
     */
    @Provides
    @Singleton
    fun providePreferencesDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { context.preferencesDataStoreFile(DATASTORE_NAME) },
        )

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SolidPlayerDatabase =
        Room.databaseBuilder(
            context,
            SolidPlayerDatabase::class.java,
            SolidPlayerDatabase.NAME,
        )
            // v2：媒体库自动发现新增的目录 / 入库时间等列
            // v3：NEW 角标的「已看过」标记 lastViewedAt
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()

    @Provides
    fun provideMediaFileDao(database: SolidPlayerDatabase) = database.mediaFileDao()

    @Provides
    fun provideDanmakuCacheDao(database: SolidPlayerDatabase) = database.danmakuCacheDao()

    @Provides
    fun providePlayHistoryDao(database: SolidPlayerDatabase) = database.playHistoryDao()

    @Provides
    fun provideAnimeDao(database: SolidPlayerDatabase) = database.animeDao()

    /**
     * 默认（无限定符）的 OkHttpClient 是**带签名拦截器**的那个，供 Retrofit 使用。
     *
     * `followRedirects(true)` 是必须的：`/api/v2/comment/{episodeId}` 依赖 302 跳转到弹幕加速服务。
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(credentialStore: DandanplayCredentialStore): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(DandanplaySignInterceptor(credentialStore))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

    /**
     * 裸客户端：**不带**签名拦截器。
     * 「测试连接」要手工构造带签名的请求，用默认客户端的话拦截器会把请求头覆盖掉（文档 §4.5.5）。
     */
    @Provides
    @Singleton
    @BareClient
    fun provideBareOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json): Retrofit = Retrofit.Builder()
        .baseUrl(DandanplayApi.BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory(JSON_MEDIA_TYPE))
        .build()

    @Provides
    @Singleton
    fun provideDandanplayApi(retrofit: Retrofit): DandanplayApi =
        retrofit.create(DandanplayApi::class.java)

    private const val DATASTORE_NAME = "solidplayer_settings"
    private val JSON_MEDIA_TYPE = "application/json".toMediaType()
}
