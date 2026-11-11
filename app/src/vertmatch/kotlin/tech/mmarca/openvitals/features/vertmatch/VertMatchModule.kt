package tech.mmarca.openvitals.features.vertmatch

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton
import tech.mmarca.openvitals.BuildConfig
import tech.mmarca.openvitals.data.repository.contract.ActivityDeletionListener
import tech.mmarca.openvitals.features.activity.ActivityDetailExtension
import tech.mmarca.openvitals.features.settings.SettingsExtension
import tech.mmarca.openvitals.vertmatch.VertMatchConfig
import tech.mmarca.openvitals.vertmatch.VertMatchTokenClient

/**
 * Binds the VertMatch integration into the app's extension points. Compiled only into the opt-in
 * build (`-Popenvitals.vertmatch=true`); the client id comes from `openvitals.vertmatch.clientId`
 * at build time and is empty otherwise, in which case the integration shows as not available.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class VertMatchModule {
    @Binds
    @IntoSet
    abstract fun settingsEntry(entry: VertMatchSettingsEntry): SettingsExtension

    @Binds
    @IntoSet
    abstract fun activityCard(card: VertMatchActivityCard): ActivityDetailExtension

    @Binds
    @IntoSet
    abstract fun deletionListener(listener: VertMatchDeletionListener): ActivityDeletionListener

    companion object {
        @Provides
        @Singleton
        fun config(@ApplicationContext context: Context): VertMatchConfig {
            val clientId = BuildConfig.VERTMATCH_CLIENT_ID
            return VertMatchConfig(
                clientId = clientId,
                redirectUri = if (clientId.isBlank()) "" else VertMatchConnection.redirectUriFor(context.packageName),
                requestRefreshToken = BuildConfig.VERTMATCH_REFRESH_TOKENS,
            )
        }

        @Provides
        @Singleton
        fun vault(@ApplicationContext context: Context): VertMatchVault = VertMatchVault(context)

        @Provides
        @Singleton
        fun tokenClient(config: VertMatchConfig): VertMatchTokenClient = VertMatchTokenClient(config)
    }
}
