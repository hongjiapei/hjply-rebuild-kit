package io.nekohasekai.sfa.ktx

import io.nekohasekai.sfa.database.preference.RoomPreferenceDataStore
import kotlin.reflect.KProperty

fun RoomPreferenceDataStore.boolean(name: String, defaultValue: () -> Boolean = { false }) = PreferenceProxy(name, defaultValue, ::getBoolean, ::putBoolean)

fun RoomPreferenceDataStore.long(name: String, defaultValue: () -> Long = { 0L }) = PreferenceProxy(name, defaultValue, ::getLong, ::putLong)

class PreferenceProxy<T>(
    private val name: String,
    private val defaultValue: () -> T,
    private val getter: (String, T) -> T,
    private val setter: (String, T) -> Unit,
) {
    operator fun setValue(thisObj: Any?, property: KProperty<*>, value: T) = setter(name, value)

    operator fun getValue(thisObj: Any?, property: KProperty<*>) = getter(name, defaultValue())
}
