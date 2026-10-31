/*
 * CoreMedia 構造体のシリアライズ形式（CMTime / NSNumber / Dictionary / AudioStreamBasicDescription）。
 * Portions derived from quicktime_video_hack (MIT License, Copyright (c) 2019 Daniel Paulus).
 */
package io.github.sekimotosa.c2mirror.protocol

object Magic {
    // 4byte 長の直後に置かれるパケット種別
    val SYNC = fourCC("sync")
    val ASYN = fourCC("asyn")
    val PING = fourCC("ping")
    val RPLY = fourCC("rply")

    // SYNC サブタイプ
    val CWPA = fourCC("cwpa")
    val AFMT = fourCC("afmt")
    val CVRP = fourCC("cvrp")
    val CLOK = fourCC("clok")
    val TIME = fourCC("time")
    val OG = fourCC("go! ")
    val SKEW = fourCC("skew")
    val STOP = fourCC("stop")

    // ASYN サブタイプ
    val FEED = fourCC("feed")
    val EAT = fourCC("eat!")
    val NEED = fourCC("need")
    val SPRP = fourCC("sprp")
    val SRAT = fourCC("srat")
    val TBAS = fourCC("tbas")
    val TJMP = fourCC("tjmp")
    val RELS = fourCC("rels")
    val HPD1 = fourCC("hpd1")
    val HPA1 = fourCC("hpa1")
    val HPD0 = fourCC("hpd0")
    val HPA0 = fourCC("hpa0")

    // Dictionary
    val KEYV = fourCC("keyv")
    val STRK = fourCC("strk")
    val IDXK = fourCC("idxk")
    val BULV = fourCC("bulv")
    val DICT = fourCC("dict")
    val DATV = fourCC("datv")
    val STRV = fourCC("strv")
    val NMBV = fourCC("nmbv")

    // CMFormatDescription
    val FDSC = fourCC("fdsc")
    val MDIA = fourCC("mdia")
    val VDIM = fourCC("vdim")
    val CODC = fourCC("codc")
    val EXTN = fourCC("extn")
    val ASBD = fourCC("asbd")
    val MEDIA_VIDEO = fourCC("vide")
    val MEDIA_SOUND = fourCC("soun")
    val CODEC_AVC1 = fourCC("avc1")
    val CODEC_HVC1 = fourCC("hvc1")
    val CODEC_HEV1 = fourCC("hev1")

    // CMSampleBuffer
    val SBUF = fourCC("sbuf")
    val OPTS = fourCC("opts")
    val STIA = fourCC("stia")
    val SDAT = fourCC("sdat")
    val SATT = fourCC("satt")
    val SARY = fourCC("sary")
    val SSIZ = fourCC("ssiz")
    val NSMP = fourCC("nsmp")

    val LPCM = fourCC("lpcm")
}

/** CFTypeID の "空" 値。 */
const val EMPTY_CF_TYPE: Long = 1L

data class CMTime(val value: Long, val scale: Int, val flags: Int, val epoch: Long) {
    fun seconds(): Long = if (value == 0L || scale == 0) 0 else java.lang.Long.divideUnsigned(value, scale.toLong() and 0xFFFFFFFFL)

    fun serialize(target: ByteArray, off: Int) {
        target.putI64le(off, value)
        target.putI32le(off + 8, scale)
        target.putI32le(off + 12, flags)
        target.putI64le(off + 16, epoch)
    }

    companion object {
        const val LENGTH = 24
        const val FLAGS_VALID = 0x0
        const val FLAGS_HAS_BEEN_ROUNDED = 0x1
        const val NANOSECOND_SCALE = 1_000_000_000

        fun parse(buf: ByteArray, off: Int, end: Int = buf.size): CMTime {
            if (end - off < LENGTH) throw ProtocolException("CMTime truncated")
            return CMTime(buf.u64le(off), buf.i32le(off + 8), buf.i32le(off + 12), buf.u64le(off + 16))
        }
    }
}

/** ホスト時刻ベースの CMClock（参考実装 NewCMClockWithHostTime 相当）。 */
class CMClock(val id: Long, private val nanoTime: () -> Long = System::nanoTime) {
    private val start = nanoTime()
    fun getTime(): CMTime = CMTime(nanoTime() - start, CMTime.NANOSECOND_SCALE, CMTime.FLAGS_HAS_BEEN_ROUNDED, 0)

    companion object {
        /** 参考実装 coremedia.CalculateSkew と同じ計算。 */
        fun calculateSkew(startLocal: CMTime, endLocal: CMTime, startDevice: CMTime, endDevice: CMTime): Double {
            val diffLocal = (endLocal.value - startLocal.value).toDouble()
            val diffDevice = (endDevice.value - startDevice.value).toDouble()
            if (diffDevice == 0.0 || startLocal.scale == 0) return Double.NaN
            val scaled = diffLocal * (startDevice.scale.toDouble() / startLocal.scale.toDouble())
            return startDevice.scale.toDouble() * scaled / diffDevice
        }
    }
}

/** NSNumber のシリアライズ表現。type 3/5 = int32, 4 = int64, 6 = float64。 */
data class NSNumber(val type: Int, val longValue: Long = 0, val doubleValue: Double = 0.0) {
    fun toBytes(): ByteArray = when (type) {
        6 -> ByteArray(9).also { it[0] = 6; it.putI64le(1, java.lang.Double.doubleToRawLongBits(doubleValue)) }
        4 -> ByteArray(9).also { it[0] = 4; it.putI64le(1, longValue) }
        3, 5 -> ByteArray(5).also { it[0] = type.toByte(); it.putI32le(1, longValue.toInt()) }
        else -> throw IllegalStateException("unknown NSNumber type $type")
    }

    override fun toString(): String = when (type) {
        6 -> "Float64[$doubleValue]"
        4 -> "UInt64[$longValue]"
        else -> "Int32[$longValue]"
    }

    companion object {
        fun ofInt32(v: Int) = NSNumber(3, longValue = v.toLong() and 0xFFFFFFFFL)
        fun ofInt64(v: Long) = NSNumber(4, longValue = v)
        fun ofFloat64(v: Double) = NSNumber(6, doubleValue = v)

        fun parse(buf: ByteArray, off: Int, len: Int): NSNumber {
            if (len < 1) throw ProtocolException("NSNumber empty")
            return when (val t = buf.u8(off)) {
                6 -> { if (len != 9) throw ProtocolException("NSNumber type 6 needs 8 bytes"); NSNumber(6, doubleValue = java.lang.Double.longBitsToDouble(buf.u64le(off + 1))) }
                4 -> { if (len != 9) throw ProtocolException("NSNumber type 4 needs 8 bytes"); NSNumber(4, longValue = buf.u64le(off + 1)) }
                3, 5 -> { if (len != 5) throw ProtocolException("NSNumber type $t needs 4 bytes"); NSNumber(t, longValue = buf.u32le(off + 1)) }
                else -> throw ProtocolException("unknown NSNumber type $t")
            }
        }
    }
}

/** 未知の値型。新しい iOS で未知の型が来てもパース全体を失敗させないために使う。 */
class UnknownValue(val magic: Int, val length: Int) {
    override fun toString() = "Unknown(${fourCCToString(magic)}, $length bytes)"
}

class StringKeyDict(val entries: List<Pair<String, Any>>) {
    operator fun get(key: String): Any? = entries.firstOrNull { it.first == key }?.second
    override fun toString() = entries.joinToString(prefix = "{", postfix = "}") { "${it.first}: ${valueToString(it.second)}" }
}

class IndexKeyDict(val entries: List<Pair<Int, Any>>) {
    operator fun get(key: Int): Any? = entries.firstOrNull { it.first == key }?.second
    override fun toString() = entries.joinToString(prefix = "{", postfix = "}") { "${it.first}: ${valueToString(it.second)}" }
}

private fun valueToString(v: Any): String = when (v) {
    is ByteArray -> "0x" + v.joinToString("") { "%02x".format(it) }
    else -> v.toString()
}

object Dicts {
    /** dict アトム（もしくはカスタムマーカー extn 等）を解析する。キーの種類で String/Index を自動判別。 */
    fun parse(buf: ByteArray, off: Int, end: Int, magic: Int = Magic.DICT): Any {
        val len = checkAtom(buf, off, end, magic)
        val dictEnd = off + len
        var p = off + 8
        if (p == dictEnd) return StringKeyDict(emptyList())
        // 最初のエントリのキー種別で判定
        checkAtom(buf, p, dictEnd, Magic.KEYV)
        val firstKeyMagic = if (dictEnd - p >= 16) buf.i32le(p + 12) else 0
        return if (firstKeyMagic == Magic.IDXK) {
            val entries = ArrayList<Pair<Int, Any>>()
            while (p < dictEnd) {
                val kvLen = checkAtom(buf, p, dictEnd, Magic.KEYV)
                val kvEnd = p + kvLen
                val kLen = checkAtom(buf, p + 8, kvEnd, Magic.IDXK)
                if (kLen < 10) throw ProtocolException("idxk too short")
                val key = buf.u16le(p + 16)
                entries += key to parseValue(buf, p + 8 + kLen, kvEnd)
                p = kvEnd
            }
            IndexKeyDict(entries)
        } else {
            val entries = ArrayList<Pair<String, Any>>()
            while (p < dictEnd) {
                val kvLen = checkAtom(buf, p, dictEnd, Magic.KEYV)
                val kvEnd = p + kvLen
                entries += parseStringEntry(buf, p + 8, kvEnd)
                p = kvEnd
            }
            StringKeyDict(entries)
        }
    }

    fun parseStringDict(buf: ByteArray, off: Int, end: Int): StringKeyDict =
        parse(buf, off, end) as? StringKeyDict ?: throw ProtocolException("expected string-key dict")

    fun parseIndexDict(buf: ByteArray, off: Int, end: Int, magic: Int = Magic.DICT): IndexKeyDict =
        when (val d = parse(buf, off, end, magic)) {
            is IndexKeyDict -> d
            is StringKeyDict -> if (d.entries.isEmpty()) IndexKeyDict(emptyList()) else throw ProtocolException("expected index-key dict")
            else -> throw ProtocolException("expected index-key dict")
        }

    /** keyv アトムの中身（strk + value）を解析する。 */
    fun parseStringEntry(buf: ByteArray, off: Int, end: Int): Pair<String, Any> {
        val kLen = checkAtom(buf, off, end, Magic.STRK)
        val key = String(buf, off + 8, kLen - 8, Charsets.UTF_8)
        return key to parseValue(buf, off + kLen, end)
    }

    /** keyv アトム全体（SPRP のペイロード等）を解析する。 */
    fun parseKeyValue(buf: ByteArray, off: Int, end: Int): Pair<String, Any> {
        val kvLen = checkAtom(buf, off, end, Magic.KEYV)
        return parseStringEntry(buf, off + 8, off + kvLen)
    }

    fun parseValue(buf: ByteArray, off: Int, end: Int): Any {
        val len = atomLength(buf, off, end)
        val magic = buf.i32le(off + 4)
        return when (magic) {
            Magic.STRV -> String(buf, off + 8, len - 8, Charsets.UTF_8)
            Magic.DATV -> buf.copyOfRange(off + 8, off + len)
            Magic.BULV -> { if (len < 9) throw ProtocolException("bulv too short"); buf[off + 8].toInt() == 1 }
            Magic.NMBV -> NSNumber.parse(buf, off + 8, len - 8)
            Magic.DICT -> parse(buf, off, off + len)
            Magic.FDSC -> FormatDescription.parse(buf, off, off + len)
            else -> UnknownValue(magic, len)
        }
    }

    /** StringKeyDict を dict アトムとしてシリアライズする（参考実装 SerializeStringKeyDict と同一バイト列）。 */
    fun serialize(dict: StringKeyDict): ByteArray {
        val w = ByteWriter()
        writeDict(w, dict)
        return w.toByteArray()
    }

    private fun writeDict(w: ByteWriter, dict: StringKeyDict) {
        val start = w.size
        w.i32(0); w.i32(Magic.DICT)
        for ((key, value) in dict.entries) {
            val kvStart = w.size
            w.i32(0); w.i32(Magic.KEYV)
            val keyBytes = key.toByteArray(Charsets.UTF_8)
            w.i32(keyBytes.size + 8); w.i32(Magic.STRK); w.bytes(keyBytes)
            writeValue(w, value)
            w.patchI32(kvStart, w.size - kvStart)
        }
        w.patchI32(start, w.size - start)
    }

    private fun writeValue(w: ByteWriter, value: Any) {
        when (value) {
            is Boolean -> { w.i32(9); w.i32(Magic.BULV); w.u8(if (value) 1 else 0) }
            is NSNumber -> { val b = value.toBytes(); w.i32(b.size + 8); w.i32(Magic.NMBV); w.bytes(b) }
            is String -> { val b = value.toByteArray(Charsets.UTF_8); w.i32(b.size + 8); w.i32(Magic.STRV); w.bytes(b) }
            is ByteArray -> { w.i32(value.size + 8); w.i32(Magic.DATV); w.bytes(value) }
            is StringKeyDict -> writeDict(w, value)
            else -> throw IllegalArgumentException("cannot serialize ${value::class.java}")
        }
    }
}

data class AudioStreamBasicDescription(
    val sampleRate: Double,
    val formatId: Int,
    val formatFlags: Int,
    val bytesPerPacket: Int,
    val framesPerPacket: Int,
    val bytesPerFrame: Int,
    val channelsPerFrame: Int,
    val bitsPerChannel: Int,
    val reserved: Int,
) {
    /** HPA1 の formats に入れる 56 バイト表現（参考実装 SerializeAudioStreamBasicDescription と同一）。 */
    fun serialize56(): ByteArray {
        val b = ByteArray(56)
        b.putI64le(0, java.lang.Double.doubleToRawLongBits(sampleRate))
        b.putI32le(8, Magic.LPCM)
        b.putI32le(12, formatFlags); b.putI32le(16, bytesPerPacket); b.putI32le(20, framesPerPacket)
        b.putI32le(24, bytesPerFrame); b.putI32le(28, channelsPerFrame); b.putI32le(32, bitsPerChannel)
        b.putI32le(36, reserved)
        b.putI64le(40, java.lang.Double.doubleToRawLongBits(sampleRate))
        b.putI64le(48, java.lang.Double.doubleToRawLongBits(sampleRate))
        return b
    }

    companion object {
        const val LENGTH = 40
        val DEFAULT = AudioStreamBasicDescription(48000.0, Magic.LPCM, 12, 4, 1, 4, 2, 16, 0)

        fun parse(buf: ByteArray, off: Int, end: Int): AudioStreamBasicDescription {
            if (end - off < LENGTH) throw ProtocolException("ASBD truncated")
            return AudioStreamBasicDescription(
                java.lang.Double.longBitsToDouble(buf.u64le(off)), buf.i32le(off + 8), buf.i32le(off + 12),
                buf.i32le(off + 16), buf.i32le(off + 20), buf.i32le(off + 24), buf.i32le(off + 28),
                buf.i32le(off + 32), buf.i32le(off + 36)
            )
        }
    }
}
