/*
 * QuickTime USB AV パケットの生成・ヘッダ解析。
 * パケット = [4byte 全長(自身を含む)] [4byte 種別 sync/asyn/ping] [8byte ClockRef] [4byte サブタイプ] ...
 * SYNC はさらに [8byte CorrelationID] が続く。全てリトルエンディアン。
 * Portions derived from quicktime_video_hack (MIT License, Copyright (c) 2019 Daniel Paulus).
 */
package io.github.sekimotosa.c2mirror.protocol

/**
 * 長さフィールドを除いたパケット本体のビュー。
 * buf[off] が種別マジックの先頭。[off, off+len) が有効範囲。
 */
class PacketView(val buf: ByteArray, val off: Int, val len: Int) {
    val end get() = off + len
    val type: Int get() = buf.i32le(off)
    val clockRef: Long get() = buf.u64le(off + 4)
    val subtype: Int get() = buf.i32le(off + 12)
    /** SYNC のみ */
    val correlationId: Long get() = buf.u64le(off + 16)
    /** SYNC ペイロード（CorrelationID の後）の開始位置 */
    val syncPayload get() = off + 24
    /** ASYN ペイロード（サブタイプの後）の開始位置 */
    val asynPayload get() = off + 16

    fun requireLength(min: Int, what: String) {
        if (len < min) throw ProtocolException("$what packet too short: $len < $min")
    }

    override fun toString(): String =
        if (len >= 16) "${fourCCToString(type)}/${fourCCToString(subtype)} len=${len + 4}" else "${fourCCToString(type)} len=${len + 4}"
}

object Packets {
    const val PING_LENGTH = 16

    fun ping(): ByteArray = ByteArray(16).also {
        it.putI32le(0, PING_LENGTH)
        it.putI32le(4, Magic.PING)
        it.putI64le(8, 0x0000000100000000L)
    }

    /** CWPA / CVRP / CLOK への返信（自分側の ClockRef を返す）。28 バイト。 */
    fun clockRefReply(correlationId: Long, clockRef: Long): ByteArray = ByteArray(28).also {
        it.putI32le(0, 28); it.putI32le(4, Magic.RPLY); it.putI64le(8, correlationId)
        it.putI32le(16, 0); it.putI64le(20, clockRef)
    }

    /** AFMT への返信: {"Error": 0} */
    fun afmtReply(correlationId: Long): ByteArray {
        val dict = Dicts.serialize(StringKeyDict(listOf("Error" to NSNumber.ofInt32(0))))
        val out = ByteArray(20 + dict.size)
        out.putI32le(0, out.size); out.putI32le(4, Magic.RPLY); out.putI64le(8, correlationId); out.putI32le(16, 0)
        System.arraycopy(dict, 0, out, 20, dict.size)
        return out
    }

    /** OG / STOP / 未知の SYNC への空返信。24 バイト。 */
    fun emptyReply(correlationId: Long): ByteArray = ByteArray(24).also {
        it.putI32le(0, 24); it.putI32le(4, Magic.RPLY); it.putI64le(8, correlationId)
    }

    fun skewReply(correlationId: Long, skew: Double): ByteArray = ByteArray(28).also {
        it.putI32le(0, 28); it.putI32le(4, Magic.RPLY); it.putI64le(8, correlationId); it.putI32le(16, 0)
        it.putI64le(20, java.lang.Double.doubleToRawLongBits(skew))
    }

    fun timeReply(correlationId: Long, time: CMTime): ByteArray = ByteArray(44).also {
        it.putI32le(0, 44); it.putI32le(4, Magic.RPLY); it.putI64le(8, correlationId); it.putI32le(16, 0)
        time.serialize(it, 20)
    }

    private fun asyn20(clockRef: Long, subtype: Int): ByteArray = ByteArray(20).also {
        it.putI32le(0, 20); it.putI32le(4, Magic.ASYN); it.putI64le(8, clockRef); it.putI32le(16, subtype)
    }

    /** 映像フレームの送出要求。CVRP で受け取ったデバイス側 ClockRef を入れる。 */
    fun need(deviceVideoClockRef: Long) = asyn20(deviceVideoClockRef, Magic.NEED)
    fun hpd0() = asyn20(EMPTY_CF_TYPE, Magic.HPD0)
    fun hpa0(deviceAudioClockRef: Long) = asyn20(deviceAudioClockRef, Magic.HPA0)

    fun asynDict(clockRef: Long, subtype: Int, dict: StringKeyDict): ByteArray {
        val d = Dicts.serialize(dict)
        val out = ByteArray(20 + d.size)
        out.putI32le(0, out.size); out.putI32le(4, Magic.ASYN); out.putI64le(8, clockRef); out.putI32le(16, subtype)
        System.arraycopy(d, 0, out, 20, d.size)
        return out
    }

    /** 映像ストリーム開始要求（受信側の情報）。値は参考実装と同一。 */
    fun hpd1(): ByteArray = asynDict(EMPTY_CF_TYPE, Magic.HPD1, hpd1Dict())

    fun hpd1Dict() = StringKeyDict(listOf(
        "Valeria" to true,
        "HEVCDecoderSupports444" to true,
        "DisplaySize" to StringKeyDict(listOf(
            "Width" to NSNumber.ofFloat64(1920.0),
            "Height" to NSNumber.ofFloat64(1200.0),
        )),
    ))

    /** 音声ストリーム開始要求。映像のみが目的でも、参考実装同様にセッション確立のため送信する。 */
    fun hpa1(deviceAudioClockRef: Long): ByteArray = asynDict(deviceAudioClockRef, Magic.HPA1, hpa1Dict())

    fun hpa1Dict() = StringKeyDict(listOf(
        "BufferAheadInterval" to NSNumber.ofFloat64(0.07300000000000001),
        "deviceUID" to "Valeria",
        "ScreenLatency" to NSNumber.ofFloat64(0.04),
        "formats" to AudioStreamBasicDescription.DEFAULT.serialize56(),
        "EDIDAC3Support" to NSNumber.ofInt32(0),
        "deviceName" to "Valeria",
    ))

    /** CWPA / CVRP の ClockRef フィールドは空(1)であるべき。 */
    fun requireEmptyClockRef(p: PacketView, what: String) {
        if (p.clockRef != EMPTY_CF_TYPE) throw ProtocolException("$what should have empty ClockRef but has ${p.clockRef.toString(16)}")
    }
}
