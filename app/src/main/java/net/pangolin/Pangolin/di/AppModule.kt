package net.pangolin.Pangolin.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.pangolin.Pangolin.util.APIClient
import net.pangolin.Pangolin.util.SocketManager
import java.io.File
import javax.inject.Singleton

/**
 * Bindings for app-scoped values that aren't @Inject-constructable
 * (third-party types or types whose constructors take primitive values).
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSocketManager(@ApplicationContext context: Context): SocketManager =
        SocketManager(File(context.filesDir, "pangolin.sock").absolutePath)

    @Provides
    @Singleton
    fun provideApiClient(@ApplicationContext context: Context): APIClient {
        val versionName = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
        return APIClient("https://app.pangolin.net", versionName = versionName)
    }
}
