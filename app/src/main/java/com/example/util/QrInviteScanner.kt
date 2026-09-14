package com.example.util

import com.journeyapps.barcodescanner.ScanOptions

object QrInviteScanner {
    fun scanOptions(): ScanOptions =
        ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("Scan convoy QR")
            .setBeepEnabled(false)
            .setOrientationLocked(false)
            .setBarcodeImageEnabled(false)
}
