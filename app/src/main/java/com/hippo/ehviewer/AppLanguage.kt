package com.hippo.ehviewer

import android.content.Context
import android.content.res.Configuration
import android.widget.Toast
import androidx.annotation.StringRes

internal object AppLanguage {
    fun context(base: Context): Context {
        val locale = Settings.getAppLocale()
        val configuration = base.resources.configuration
        if (configuration.locales.size() == 1 && configuration.locales[0] == locale) return base
        return base.createConfigurationContext(Configuration().apply { setLocale(locale) })
    }

    fun showToast(base: Context, @StringRes message: Int, duration: Int) {
        Toast.makeText(base, context(base).getText(message), duration).show()
    }
}
