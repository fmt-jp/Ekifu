package jp.fmt.ekifu.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellIdentityWcdma
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import jp.fmt.ekifu.engine.CellSnapshot
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * いま見えている携帯の基地局を読む（端末の中だけ。通信も外部への送信もしない）。
 * Android の決まりで「正確な位置」の許可が要る。許可がない・SIM がないときは空
 */
class CellReader(context: Context) {
    private val appContext = context.applicationContext
    private val tm = appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    private val executor = Executors.newSingleThreadExecutor()

    val available: Boolean
        get() = tm != null && ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // available で確かめてから読む
    suspend fun read(): CellSnapshot? {
        val t = tm ?: return null
        if (!available) return null
        val cells: List<CellInfo> = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // 新しい情報を頼む（返事がなければ手元の情報を使う）
                withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                    suspendCancellableCoroutine { cont ->
                        t.requestCellInfoUpdate(executor, object : TelephonyManager.CellInfoCallback() {
                            override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                                if (cont.isActive) cont.resume(cellInfo.toList())
                            }

                            override fun onError(errorCode: Int, detail: Throwable?) {
                                if (cont.isActive) cont.resume(t.allCellInfo ?: emptyList())
                            }
                        })
                    }
                } ?: (t.allCellInfo ?: emptyList())
            } else {
                t.allCellInfo ?: emptyList()
            }
        } catch (e: SecurityException) {
            return null
        }
        val serving = cells.filter { it.isRegistered }.mapNotNull { keyOf(it) }.toSet()
        val neighbors = cells.filter { !it.isRegistered }.mapNotNull { keyOf(it) }.toSet()
        return CellSnapshot(serving, neighbors)
    }

    fun release() {
        executor.shutdown()
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 3_000L
        const val UNKNOWN = Int.MAX_VALUE

        /** 「方式:事業者:エリア:セル番号」。番号が分からない基地局（隣の局によくある）は使わない */
        fun keyOf(c: CellInfo): String? = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && c is CellInfoNr -> {
                val id = c.cellIdentity as CellIdentityNr
                if (id.nci == Long.MAX_VALUE || id.nci <= 0) null else "NR:${id.mccString}${id.mncString}:${id.tac}:${id.nci}"
            }
            c is CellInfoLte -> {
                val id: CellIdentityLte = c.cellIdentity
                if (id.ci == UNKNOWN || id.ci <= 0) null else "LTE:${operator(id)}:${id.tac}:${id.ci}"
            }
            c is CellInfoWcdma -> {
                val id: CellIdentityWcdma = c.cellIdentity
                if (id.cid == UNKNOWN || id.cid <= 0) null else "W:${operator(id)}:${id.lac}:${id.cid}"
            }
            c is CellInfoGsm -> {
                val id: CellIdentityGsm = c.cellIdentity
                if (id.cid == UNKNOWN || id.cid <= 0) null else "G:${operator(id)}:${id.lac}:${id.cid}"
            }
            else -> null
        }

        fun mccMnc(mcc: String?, mnc: String?) = "${mcc ?: ""}${mnc ?: ""}"

        /** 事業者の番号（Android 9 より前は数値で取る） */
        @Suppress("DEPRECATION")
        fun operator(id: CellIdentityLte) =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) mccMnc(id.mccString, id.mncString) else "${id.mcc}${id.mnc}"

        @Suppress("DEPRECATION")
        fun operator(id: CellIdentityWcdma) =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) mccMnc(id.mccString, id.mncString) else "${id.mcc}${id.mnc}"

        @Suppress("DEPRECATION")
        fun operator(id: CellIdentityGsm) =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) mccMnc(id.mccString, id.mncString) else "${id.mcc}${id.mnc}"
    }
}
