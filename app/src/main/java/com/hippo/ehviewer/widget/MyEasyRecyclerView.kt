package com.hippo.ehviewer.widget

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import com.hippo.easyrecyclerview.EasyRecyclerView

class MyEasyRecyclerView : EasyRecyclerView {
    var touchCancelListener: Runnable? = null

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) touchCancelListener?.run()
        return super.dispatchTouchEvent(event)
    }

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(
        context,
        attrs,
        defStyle
    )

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent?): Boolean {
        try {
            return super.onTouchEvent(ev)
        } catch (e: IllegalArgumentException) {
            Log.e("RecyclerView", "IllegalArgumentException", e)
        }
        return true
    }
}
