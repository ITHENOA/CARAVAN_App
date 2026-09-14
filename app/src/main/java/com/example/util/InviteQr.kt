package com.example.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

object InviteQr {
    fun joinPayload(tripId: String, inviteCode: String): String =
        "caravan://join/$tripId?code=$inviteCode"

    /** Extract invite code from raw QR / paste (code, URL, or caravan:// payload). */
    fun parseInviteCode(raw: String): String {
        val input = raw.trim()
        if (input.isEmpty()) return ""
        if (input.contains("code=")) {
            return input.substringAfter("code=").substringBefore("&").trim()
        }
        if (input.contains("|")) {
            return input.substringAfter("|").trim()
        }
        return input
    }

    fun bitmap(content: String, sizePx: Int = 512): Bitmap {
        val matrix = QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            sizePx,
            sizePx,
            mapOf(EncodeHintType.MARGIN to 1)
        )
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }
}
