package com.lagradost.cloudstream3.ui.account

import android.app.Activity
import android.os.Bundle
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.utils.UIHelper.navigate

object AccountHelper {
    fun Activity?.showAccountSelectLinear() {
        val activity = this as? MainActivity ?: return
        activity.navigate(
            R.id.accountSelectActivity,
            Bundle().apply { putBoolean(AccountSelectActivity2.IS_FROM_MAIN_ACTIVITY, true) }
        )
    }
}
