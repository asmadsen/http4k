package org.reekwest.http.lens

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.*

class Get<in IN, MID, out OUT> private constructor(private val rootFn: (String, IN) -> List<MID>, private val fn: (MID) -> OUT) {
    operator fun invoke(name: String) = { target: IN -> rootFn(name, target).map(fn) }

    fun <NEXT> map(nextFn: (OUT) -> NEXT) = Get(rootFn, { nextFn(fn(it)) })

    companion object {
        operator fun <IN, OUT> invoke(rootFn: (String, IN) -> List<OUT>): Get<IN, OUT, OUT> = Get(rootFn, { it })
    }
}

class Set<IN, MID, in OUT> private constructor(private val rootFn: (String, List<MID>, IN) -> IN, private val fn: (OUT) -> MID) {
    operator fun invoke(name: String) = { values: List<OUT>, target: IN -> rootFn(name, values.map(fn), target) }
    fun <NEXT> map(nextFn: (NEXT) -> OUT) = Set(rootFn, { value: NEXT -> fn(nextFn(value)) })

    companion object {
        operator fun <IN, OUT> invoke(rootFn: (String, List<OUT>, IN) -> IN): Set<IN, OUT, OUT> = Set(rootFn, { it })
    }
}

interface MultiLensSpec<in IN, OUT> {
    fun optional(name: String, description: String? = null, default: List<OUT>? = null): Lens<IN, List<OUT>?>
    fun required(name: String, description: String? = null): Lens<IN, List<OUT>>
}

open class LensSpec<IN, MID, OUT>(protected val location: String, internal val get: Get<IN, MID, OUT>) {
    fun <NEXT> map(nextIn: (OUT) -> NEXT) = LensSpec(location, get.map(nextIn))

    open fun optional(name: String, description: String? = null, default: OUT? = null): Lens<IN, OUT?> {
        val meta = Meta(false, location, name, description)
        val getLens = get(name)
        return Lens(meta, { getLens(it).let { if (it.isEmpty()) default else it.first() } })
    }

    open fun required(name: String, description: String? = null): Lens<IN, OUT> {
        val meta = Meta(true, location, name, description)
        val getLens = get(name)
        return Lens(meta, { getLens(it).firstOrNull() ?: throw LensFailure(meta.missing()) })
    }

    open val multi = object : MultiLensSpec<IN, OUT> {
        override fun optional(name: String, description: String?, default: List<OUT>?): Lens<IN, List<OUT>?> {
            val meta = Meta(false, location, name, description)
            val getLens = get(name)
            return Lens(meta, { getLens(it).let { if (it.isEmpty()) default else it } })
        }

        override fun required(name: String, description: String?): Lens<IN, List<OUT>> {
            val meta = Meta(true, location, name, description)
            val getLens = get(name)
            return Lens(meta, { getLens(it).let { if (it.isEmpty()) throw LensFailure(meta.missing()) else it } })
        }
    }
}

interface BiDiMultiLensSpec<in IN, OUT> : MultiLensSpec<IN, OUT> {
    override fun optional(name: String, description: String?, default: List<OUT>?): BiDiLens<IN, List<OUT>?>
    override fun required(name: String, description: String?): BiDiLens<IN, List<OUT>>
}

open class BiDiLensSpec<IN, MID, OUT>(location: String, get: Get<IN, MID, OUT>,
                                      private val set: Set<IN, MID, OUT>) : LensSpec<IN, MID, OUT>(location, get) {

    fun <NEXT> map(nextIn: (OUT) -> NEXT, nextOut: (NEXT) -> OUT) = BiDiLensSpec(location, get.map(nextIn), set.map(nextOut))

    override fun optional(name: String, description: String?, default: OUT?): BiDiLens<IN, OUT?> {
        val meta = Meta(false, location, name, description)
        val getLens = get(name)
        val setLens = set(name)
        return BiDiLens(meta,
            { getLens(it).let { if (it.isEmpty()) default else it.first() } },
            { out: OUT?, target: IN -> setLens(out?.let { listOf(it) } ?: emptyList(), target) }
        )
    }

    override fun required(name: String, description: String?): BiDiLens<IN, OUT> {
        val meta = Meta(true, location, name, description)
        val getLens = get(name)
        val setLens = set(name)
        return BiDiLens(meta,
            { getLens(it).firstOrNull() ?: throw LensFailure(meta.missing()) },
            { out: OUT, target: IN -> setLens(listOf(out), target) })
    }

    override val multi = object : BiDiMultiLensSpec<IN, OUT> {
        override fun optional(name: String, description: String?, default: List<OUT>?): BiDiLens<IN, List<OUT>?> {
            val meta = Meta(false, location, name, description)
            val getLens = get(name)
            val setLens = set(name)
            return BiDiLens(meta,
                { getLens(it).let { if (it.isEmpty()) default else it } },
                { out: List<OUT>?, target: IN -> setLens(out ?: emptyList(), target) }
            )
        }

        override fun required(name: String, description: String?): BiDiLens<IN, List<OUT>> {
            val meta = Meta(true, location, name, description)
            val getLens = get(name)
            val setLens = set(name)
            return BiDiLens(meta,
                { getLens(it).let { if (it.isEmpty()) throw LensFailure(meta.missing()) else it } },
                { out: List<OUT>, target: IN -> setLens(out, target) })
        }
    }
}

fun <IN> BiDiLensSpec<IN, String, String>.int() = this.map(String::toInt, Int::toString)
fun <IN> BiDiLensSpec<IN, String, String>.long() = this.map(String::toLong, Long::toString)
fun <IN> BiDiLensSpec<IN, String, String>.double() = this.map(String::toDouble, Double::toString)
fun <IN> BiDiLensSpec<IN, String, String>.float() = this.map(String::toFloat, Float::toString)
fun <IN> BiDiLensSpec<IN, String, String>.boolean() = this.map(::safeBooleanFrom, Boolean::toString)
fun <IN> BiDiLensSpec<IN, String, String>.localDate() = this.map(LocalDate::parse, DateTimeFormatter.ISO_LOCAL_DATE::format)
fun <IN> BiDiLensSpec<IN, String, String>.dateTime() = this.map(LocalDateTime::parse, DateTimeFormatter.ISO_LOCAL_DATE_TIME::format)
fun <IN> BiDiLensSpec<IN, String, String>.zonedDateTime() = this.map(ZonedDateTime::parse, DateTimeFormatter.ISO_ZONED_DATE_TIME::format)
fun <IN> BiDiLensSpec<IN, String, String>.uuid() = this.map(UUID::fromString, java.util.UUID::toString)

internal fun safeBooleanFrom(value: String): Boolean =
    if (value.toUpperCase() == "TRUE") true
    else if (value.toUpperCase() == "FALSE") false
    else throw kotlin.IllegalArgumentException("illegal boolean")