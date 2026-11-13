package tech.mmarca.openvitals.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import tech.mmarca.openvitals.data.repository.contract.ActivityDeletionListener
import tech.mmarca.openvitals.features.activity.ActivityDetailExtension
import tech.mmarca.openvitals.features.settings.SettingsExtension

/**
 * The extension points optional integrations bind into. Declared here so each set exists, empty,
 * in the default build: nothing in it binds one. The opt-in VertMatch build
 * (`-Popenvitals.vertmatch=true`, sources in `app/src/vertmatch`) adds one to each.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class ExtensionsModule {
    @Multibinds
    abstract fun settingsExtensions(): Set<SettingsExtension>

    @Multibinds
    abstract fun activityDetailExtensions(): Set<ActivityDetailExtension>

    @Multibinds
    abstract fun activityDeletionListeners(): Set<ActivityDeletionListener>
}
