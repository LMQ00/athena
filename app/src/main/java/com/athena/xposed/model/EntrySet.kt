package com.athena.xposed.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.builtins.ListSerializer

/**
 * 带包名索引的 [AppEntry] 容器。
 *
 * 序列化时仅保留 [entries] 列表（见 [EntrySetSerializer]）；
 * [indexByPkg] 是运行期构建的索引，反序列化后由自定义 serializer
 * 调用 [reindex] 自动重建，因此无论使用哪个 [kotlinx.serialization.json.Json]
 * 实例进行解码，索引都能保持一致。
 *
 * 本类通过受控的 [add]/[remove]/[clear] API 操作内部列表，
 * 外部不能直接修改 [entries]，避免绕过 [indexByPkg] 索引维护。
 * 如需直接重建，请通过 [EntrySetSerializer] 反序列化或构造新实例。
 *
 * 该类非线程安全；并发访问需通过外层不可变快照机制保证可见性。
 */
@Serializable(with = EntrySetSerializer::class)
class EntrySet(
    entries: MutableList<AppEntry> = mutableListOf()
) {
    /** 条目列表，按插入顺序保留 — 对外仅暴露只读视图。 */
    private val _entries: MutableList<AppEntry> = entries

    /** packageName -> entries 中的下标，运行期索引，不参与序列化 */
    private val indexByPkg: MutableMap<String, Int> = mutableMapOf()

    init {
        reindex()
    }

    /** 序列化器使用的内部列表，仅供 [EntrySetSerializer] 访问。 */
    internal val entries: List<AppEntry> get() = _entries

    /** 重建包名索引。批量修改后调用。 */
    fun reindex() {
        indexByPkg.clear()
        _entries.forEachIndexed { i, e -> indexByPkg[e.packageName] = i }
    }

    /** 条目数量。 */
    val size: Int get() = _entries.size

    /** 是否为空。 */
    fun isEmpty(): Boolean = _entries.isEmpty()

    /** 是否包含指定包名。 */
    operator fun contains(packageName: String): Boolean =
        packageName in indexByPkg

    /** 按包名获取条目，不存在返回 null。 */
    operator fun get(packageName: String): AppEntry? =
        indexByPkg[packageName]?.let { _entries.getOrNull(it) }

    /** 返回所有条目的只读视图。 */
    fun all(): List<AppEntry> = _entries.toList()

    /**
     * 添加条目。若包名已存在则覆盖原条目并返回 false。
     *
     * @return true 表示新增，false 表示覆盖。
     */
    fun add(entry: AppEntry): Boolean {
        val existing = indexByPkg[entry.packageName]
        return if (existing != null) {
            _entries[existing] = entry
            false
        } else {
            indexByPkg[entry.packageName] = _entries.size
            _entries.add(entry)
            true
        }
    }

    /** 按包名移除条目。返回被移除的条目，不存在返回 null。 */
    fun remove(packageName: String): AppEntry? {
        val idx = indexByPkg.remove(packageName) ?: return null
        val removed = _entries.removeAt(idx)
        // 移除后下标变化，整体重建
        reindex()
        return removed
    }

    /** 清空所有条目。 */
    fun clear() {
        _entries.clear()
        indexByPkg.clear()
    }

    /** 迭代器，便于 for-each 遍历。 */
    operator fun iterator(): Iterator<AppEntry> = _entries.iterator()

    override fun toString(): String =
        "EntrySet(size=$size, pkgs=${_entries.map { it.packageName }})"
}

/**
 * [EntrySet] 的自定义序列化器：仅持久化 [EntrySet.entries] 列表，
 * 并在反序列化末尾调用 [EntrySet.reindex] 重建运行期索引。
 *
 * 这样无论上层使用 [com.athena.xposed.data.JsonCodec] 还是
 * [AthenaConfig.fromJson]，反序列化后的 EntrySet 都可直接用于查询。
 */
object EntrySetSerializer : KSerializer<EntrySet> {
    private val delegate = ListSerializer(AppEntry.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: EntrySet) {
        delegate.serialize(encoder, value.entries.toList())
    }

    override fun deserialize(decoder: Decoder): EntrySet {
        val list = delegate.deserialize(decoder)
        // 主构造器 init 块已调用 reindex，此处显式再调一次以应对未来改动
        return EntrySet(list.toMutableList()).also { it.reindex() }
    }
}
