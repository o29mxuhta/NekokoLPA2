package ee.nekoko.nlpa2
import android.content.Context
import android.telephony.IccOpenLogicalChannelResponse
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
// Fallback for #61: when OMAPI refuses the ISD-R, open it through TelephonyManager (needs carrier privileges for the SIM).
internal class TelephonyChannelFallback(private val context: Context) {
  private val channels = java.util.concurrent.ConcurrentHashMap<String, Pair<TelephonyManager, Int>>()
  private fun key(reader: String, aid: String) = reader + "|" + aid.uppercase()
  private fun slotOf(reader: String): Int = (Regex("\\d+").find(reader)?.value?.toIntOrNull() ?: 1) - 1
  private fun candidates(reader: String): List<TelephonyManager> {
    val base = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    val subs = runCatching { (context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager).activeSubscriptionInfoList }.getOrNull().orEmpty()
    val slot = slotOf(reader)
    return subs.sortedBy { if (it.simSlotIndex == slot) 0 else 1 }.map { base.createForSubscriptionId(it.subscriptionId) } + base
  }
  fun open(reader: String, aid: String): String? {
    for (tm in candidates(reader)) {
      val r = runCatching { tm.iccOpenLogicalChannel(aid, 0) }.onFailure { Log.w("TelephonyChannel", "open failed: " + it.message) }.getOrNull() ?: continue
      if (r.status == IccOpenLogicalChannelResponse.STATUS_NO_ERROR && r.channel != IccOpenLogicalChannelResponse.INVALID_CHANNEL) {
        channels[key(reader, aid)] = Pair(tm, r.channel)
        return r.selectResponse?.joinToString("") { "%02X".format(it) } ?: ""
      }
      Log.w("TelephonyChannel", "open status " + r.status)
    }
    return null
  }
  fun has(reader: String, aid: String) = channels.containsKey(key(reader, aid))
  fun transmit(reader: String, aid: String, apdu: ByteArray): String? {
    val (tm, ch) = channels[key(reader, aid)] ?: return null
    if (apdu.size < 4) return null
    val p3 = if (apdu.size > 4) apdu[4].toInt() and 0xFF else 0
    val data = if (apdu.size > 5) apdu.copyOfRange(5, minOf(apdu.size, 5 + p3)).joinToString("") { "%02X".format(it) } else ""
    return tm.iccTransmitApduLogicalChannel(ch, apdu[0].toInt() and 0xFF, apdu[1].toInt() and 0xFF, apdu[2].toInt() and 0xFF, apdu[3].toInt() and 0xFF, p3, data)
  }
  fun close(reader: String, aid: String) { channels.remove(key(reader, aid))?.let { (tm, ch) -> runCatching { tm.iccCloseLogicalChannel(ch) } } }
  fun closeAll(reader: String) { channels.keys.filter { it.startsWith(reader + "|") }.forEach { close(reader, it.substringAfter("|")) } }
}
