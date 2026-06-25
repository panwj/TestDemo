package com.example.similarscandemo.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * 用于图库网格的稳定正方形容器。
 *
 * RecyclerView 初次测量时如果 item 高度依赖异步图片或后置 layout 回调，
 * 滑出再滑入可能才会得到正确高度。这里在 onMeasure 阶段直接让高度等于列宽，
 * 保证 Other 等大网格分类首次进入屏幕时尺寸就是稳定的。
 */
class SquareFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}
