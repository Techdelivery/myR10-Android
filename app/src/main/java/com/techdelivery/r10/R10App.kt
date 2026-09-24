package com.techdelivery.r10

import android.app.Application

/**
 * Application entry. Manual wiring (no Hilt, per DESIGN §2): singletons hang off
 * here or off the foreground service. Kept empty for F1; the R10Device/engine
 * graph is attached in later phases.
 */
class R10App : Application()
