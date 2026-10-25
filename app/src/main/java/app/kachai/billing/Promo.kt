package app.kachai.billing

import android.util.Base64
import app.kachai.BuildConfig
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.LocalDate

/**
 * Промокоды на Плюс для соцсетей: «Плюс на 7 дней, код действует до 10 января».
 *
 * Сервера нет: код подписан закрытым ключом разработчика (tools/promo, ключ хранится вне git, как ключ
 * подписи APK), приложение проверяет подпись встроенным открытым ключом [BuildConfig.PROMO_PUBLIC_KEY].
 * Подделать код с другим сроком без закрытого ключа нельзя. Каждый код срабатывает на телефоне один раз.
 *
 * Код: «PLUS-» + base64url(данные 6 байт + подпись ECDSA P-256 64 байта).
 * Данные: версия (1), дней Плюса (1), последний день действия кода — дней от [EPOCH] (2), номер кода (2).
 */
object Promo {
    const val PREFIX = "PLUS-"
    private const val VERSION = 1
    private val EPOCH: LocalDate = LocalDate.of(2026, 1, 1)

    data class Code(val id: Int, val days: Int, val validUntil: LocalDate)

    val enabled: Boolean get() = BuildConfig.PROMO_PUBLIC_KEY.isNotBlank()

    fun looksLikePromo(code: String) = code.trim().startsWith(PREFIX, ignoreCase = true)

    /** Разобрать и проверить подпись. null — код испорчен или подписан не нашим ключом. */
    fun parse(code: String): Code? = runCatching {
        if (!enabled) return null
        val bytes = Base64.decode(
            code.trim().substring(PREFIX.length).filterNot { it.isWhitespace() },
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        if (bytes.size != 6 + 64) return null
        val data = bytes.copyOfRange(0, 6)
        val verifier = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(
                KeyFactory.getInstance("EC").generatePublic(
                    X509EncodedKeySpec(Base64.decode(BuildConfig.PROMO_PUBLIC_KEY, Base64.DEFAULT)),
                ),
            )
            update(data)
        }
        if (!verifier.verify(rawToDer(bytes.copyOfRange(6, 70)))) return null
        val buf = ByteBuffer.wrap(data)
        if (buf.get().toInt() != VERSION) return null
        val days = buf.get().toInt() and 0xFF
        val until = EPOCH.plusDays((buf.short.toInt() and 0xFFFF).toLong())
        val id = buf.short.toInt() and 0xFFFF
        Code(id, days, until)
    }.getOrNull()

    /** Подпись приходит как r||s (по 32 байта) — Java проверяет её в формате DER. */
    private fun rawToDer(raw: ByteArray): ByteArray {
        fun integer(part: ByteArray): ByteArray {
            val v = BigInteger(1, part).toByteArray() // с ведущим нулём, если старший бит 1
            return byteArrayOf(0x02, v.size.toByte()) + v
        }
        val body = integer(raw.copyOfRange(0, 32)) + integer(raw.copyOfRange(32, 64))
        return byteArrayOf(0x30, body.size.toByte()) + body
    }
}
