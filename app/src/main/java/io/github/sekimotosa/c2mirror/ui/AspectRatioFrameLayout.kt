package io.github.sekimotosa.c2mirror.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/** 映像の縦横比を維持して最大化表示するコンテナ（レターボックス / ピラーボックス）。 */
class AspectRatioFrameLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private var ratio = 0f // width / height

    fun setAspectRatio(width: Int, height: Int) {
        val r = if (width > 0 && height > 0) width.toFloat() / height else 0f
        if (r != ratio) { ratio = r; requestLayout() }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (ratio <= 0f) { super.onMeasure(widthMeasureSpec, heightMeasureSpec); return }
        val maxW = MeasureSpec.getSize(widthMeasureSpec)
        val maxH = MeasureSpec.getSize(heightMeasureSpec)
        var w = maxW
        var h = (w / ratio).toInt()
        if (h > maxH) { h = maxH; w = (h * ratio).toInt() }
        super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }
}
