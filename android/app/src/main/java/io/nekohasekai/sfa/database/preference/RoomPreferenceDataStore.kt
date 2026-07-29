package io.nekohasekai.sfa.database.preference

class RoomPreferenceDataStore(private val kvPairDao: KeyValueEntity.Dao) {
    fun getBoolean(key: String, defaultValue: Boolean) = kvPairDao[key]?.boolean ?: defaultValue

    fun getLong(key: String, defaultValue: Long) = kvPairDao[key]?.long ?: defaultValue

    fun putBoolean(key: String, value: Boolean) {
        kvPairDao.put(KeyValueEntity(key).put(value))
    }

    fun putLong(key: String, value: Long) {
        kvPairDao.put(KeyValueEntity(key).put(value))
    }
}
