/*
 * QuickTime USB AV セッションの制御フロー（参考実装 MessageProcessor の Kotlin 移植）。
 * Portions derived from quicktime_video_hack (MIT License, Copyright (c) 2019 Daniel Paulus).
 *
 * セッション開始手順（technical_documentation.md 2.1）:
 *  PING受信→PING返信 / CWPA→HPD1,RPLY,HPA1 / AFMT→RPLY / CVRP→NEED,RPLY / CLOK→RPLY / TIME→RPLY
 *  以後 FEED（映像）を受信するたびに NEED を返す。
 */
package io.github.sekimotosa.c2mirror.protocol

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** USB Bulk OUT への書き込み口。 */
fun interface PacketWriter {
    fun write(packet: ByteArray)
}

sealed class ProtocolEvent {
    object PingReceived : ProtocolEvent()
    data class AudioClockNegotiated(val deviceClockRef: Long) : ProtocolEvent()
    data class VideoClockNegotiated(val deviceClockRef: Long, val format: FormatDescription?) : ProtocolEvent()
    object ClockSynced : ProtocolEvent()
    object StopRequested : ProtocolEvent()
    object Released : ProtocolEvent()
    data class Warning(val message: String) : ProtocolEvent()
}

interface ProtocolListener {
    /** 映像サンプル。sample.buffer はこの呼び出し中だけ有効（コピーせずに渡している）。 */
    fun onVideoSample(sample: CMSampleBuffer)
    fun onAudioSample(sample: CMSampleBuffer) {}
    fun onEvent(event: ProtocolEvent)
}

class QuickTimeProtocol(
    private val writer: PacketWriter,
    private val listener: ProtocolListener,
    private val clockFactory: (Long) -> CMClock = { CMClock(it) },
) {
    private var clock: CMClock? = null
    private var localAudioClock: CMClock? = null
    @Volatile var deviceAudioClockRef: Long = 0; private set
    @Volatile var deviceVideoClockRef: Long = 0; private set
    private var needPacket: ByteArray? = null

    private var firstAudioTimeTaken = false
    private var startTimeDeviceAudio = ZERO
    private var startTimeLocalAudio = ZERO
    private var lastDeviceAudio = ZERO
    private var lastLocalAudio = ZERO

    private val releaseLatch = CountDownLatch(2)

    // 統計（診断表示用）
    @Volatile var videoSamples = 0L; private set
    @Volatile var audioSamples = 0L; private set
    @Volatile var feedParseErrors = 0L; private set
    @Volatile var unknownPackets = 0L; private set
    @Volatile var pingReceived = false; private set
    @Volatile var lastUnknown: String? = null; private set

    /** 長さフィールドを除いたパケット本体を処理する。 */
    fun handlePacket(buf: ByteArray, off: Int, len: Int) {
        val p = PacketView(buf, off, len)
        p.requireLength(4, "any")
        when (p.type) {
            Magic.PING -> {
                pingReceived = true
                writer.write(Packets.ping())
                listener.onEvent(ProtocolEvent.PingReceived)
            }
            Magic.SYNC -> { p.requireLength(24, "SYNC"); handleSync(p) }
            Magic.ASYN -> { p.requireLength(16, "ASYN"); handleAsyn(p) }
            else -> {
                unknownPackets++
                lastUnknown = fourCCToString(p.type)
                listener.onEvent(ProtocolEvent.Warning("unknown packet type ${fourCCToString(p.type)}"))
            }
        }
    }

    private fun handleSync(p: PacketView) {
        val corr = p.correlationId
        when (p.subtype) {
            Magic.CWPA -> {
                Packets.requireEmptyClockRef(p, "CWPA")
                p.requireLength(32, "CWPA")
                val deviceClock = p.buf.u64le(p.syncPayload)
                val clockRef = deviceClock + 1000
                localAudioClock = clockFactory(clockRef)
                deviceAudioClockRef = deviceClock
                // 参考実装は HPD1 を2回送信している
                val hpd1 = Packets.hpd1()
                writer.write(hpd1)
                writer.write(hpd1)
                writer.write(Packets.clockRefReply(corr, clockRef))
                writer.write(Packets.hpa1(deviceClock))
                listener.onEvent(ProtocolEvent.AudioClockNegotiated(deviceClock))
            }
            Magic.AFMT -> writer.write(Packets.afmtReply(corr))
            Magic.CVRP -> {
                Packets.requireEmptyClockRef(p, "CVRP")
                p.requireLength(32, "CVRP")
                val deviceClock = p.buf.u64le(p.syncPayload)
                deviceVideoClockRef = deviceClock
                val need = Packets.need(deviceClock)
                needPacket = need
                // ペイロード辞書（FormatDescription を含む）は失敗しても致命的にしない
                val format = try {
                    val dict = Dicts.parseStringDict(p.buf, p.syncPayload + 8, p.end)
                    dict["FormatDescription"] as? FormatDescription
                } catch (e: ProtocolException) {
                    listener.onEvent(ProtocolEvent.Warning("CVRP payload: ${e.message}"))
                    null
                }
                writer.write(need)
                writer.write(Packets.clockRefReply(corr, deviceClock + 0x1000AF))
                listener.onEvent(ProtocolEvent.VideoClockNegotiated(deviceClock, format))
            }
            Magic.CLOK -> {
                val clockRef = p.clockRef + 0x10000
                clock = clockFactory(clockRef)
                writer.write(Packets.clockRefReply(corr, clockRef))
                listener.onEvent(ProtocolEvent.ClockSynced)
            }
            Magic.TIME -> {
                val c = clock ?: clockFactory(p.clockRef).also { clock = it }
                writer.write(Packets.timeReply(corr, c.getTime()))
            }
            Magic.SKEW -> {
                var skew = CMClock.calculateSkew(startTimeLocalAudio, lastLocalAudio, startTimeDeviceAudio, lastDeviceAudio)
                if (skew.isNaN() || skew.isInfinite()) skew = AudioStreamBasicDescription.DEFAULT.sampleRate
                writer.write(Packets.skewReply(corr, skew))
            }
            Magic.OG -> writer.write(Packets.emptyReply(corr))
            Magic.STOP -> {
                writer.write(Packets.emptyReply(corr))
                listener.onEvent(ProtocolEvent.StopRequested)
            }
            else -> {
                // 参考実装はここでセッションを停止する。デバイス側が応答待ちで止まらないよう空の RPLY を返して継続する。
                unknownPackets++
                lastUnknown = "sync/" + fourCCToString(p.subtype)
                writer.write(Packets.emptyReply(corr))
                listener.onEvent(ProtocolEvent.Warning("unknown SYNC ${fourCCToString(p.subtype)}"))
            }
        }
    }

    private fun handleAsyn(p: PacketView) {
        when (p.subtype) {
            Magic.FEED -> {
                // 低遅延化: デコード処理より先に NEED を返し、次フレームの転送を並行して開始させる
                needPacket?.let { writer.write(it) }
                val sample = try {
                    CMSampleBuffer.parse(p.buf, p.asynPayload, p.end, Magic.MEDIA_VIDEO)
                } catch (e: ProtocolException) {
                    feedParseErrors++
                    listener.onEvent(ProtocolEvent.Warning("FEED parse error: ${e.message}"))
                    return
                }
                videoSamples++
                listener.onVideoSample(sample)
            }
            Magic.EAT -> {
                val sample = try {
                    CMSampleBuffer.parse(p.buf, p.asynPayload, p.end, Magic.MEDIA_SOUND)
                } catch (e: ProtocolException) {
                    return
                }
                audioSamples++
                val ts = sample.outputPresentationTimestamp
                val local = localAudioClock
                if (ts != null && local != null) {
                    val now = local.getTime()
                    if (!firstAudioTimeTaken) {
                        startTimeDeviceAudio = ts; startTimeLocalAudio = now
                        firstAudioTimeTaken = true
                    }
                    lastDeviceAudio = ts; lastLocalAudio = now
                }
                listener.onAudioSample(sample)
            }
            Magic.RELS -> {
                releaseLatch.countDown()
                listener.onEvent(ProtocolEvent.Released)
            }
            Magic.SPRP, Magic.SRAT, Magic.TBAS, Magic.TJMP -> Unit // 情報通知のみ。返信不要。
            else -> {
                unknownPackets++
                lastUnknown = "asyn/" + fourCCToString(p.subtype)
                listener.onEvent(ProtocolEvent.Warning("unknown ASYN ${fourCCToString(p.subtype)}"))
            }
        }
    }

    /**
     * ストリーミング停止（technical_documentation.md 2.3）。HPA0 / HPD0 を送り RELS を待つ。
     * 受信スレッドが動作している間に別スレッドから呼ぶこと。
     */
    fun closeSession(timeoutMs: Long = 3000): Boolean {
        writer.write(Packets.hpa0(deviceAudioClockRef))
        writer.write(Packets.hpd0())
        val released = releaseLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (released) writer.write(Packets.hpd0())
        return released
    }

    companion object {
        private val ZERO = CMTime(0, CMTime.NANOSECOND_SCALE, 0, 0)
    }
}
