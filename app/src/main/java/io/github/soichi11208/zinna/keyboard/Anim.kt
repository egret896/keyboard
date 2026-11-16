package io.github.soichi11208.zinna.keyboard

import android.view.View
import android.view.animation.PathInterpolator

/**
 * キーボードの控えめなアニメーション。
 *
 * 方針：弾まない・大きさを変えない・目立たない。
 * 透明度と、ほんの少しの位置だけを、なめらかな減速カーブ（Material の標準カーブ）で動かす。
 * どれも GPU だけで処理されるので、入力の遅れや電池への影響はほぼない。
 * 1文字打つごとに動くもの（候補の更新など）には付けていない。
 *
 *   fadeIn  … パネルを開く・画面を切り替えるとき、下から少しだけ浮かび上がるように出す
 *   tap     … 候補やボタンを押したとき、一瞬だけ薄くなって戻る
 *   swap    … アイコンが変わったとき（鍵の開け閉めなど）、静かに入れ替わる
 */
object Anim {
    private val curve = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    fun fadeIn(view: View, from: Float = 0f, durationMs: Long = 200L) {
        view.animate().cancel()
        view.alpha = from
        view.translationY = if (from == 0f) 6f * view.resources.displayMetrics.density else 0f
        view.animate().alpha(1f).translationY(0f).setDuration(durationMs).setInterpolator(curve)
            .withLayer().start()
    }

    fun tap(view: View) {
        view.animate().cancel()
        view.alpha = 0.55f
        view.animate().alpha(1f).setDuration(220L).setInterpolator(curve).start()
    }

    fun swap(view: View) {
        view.animate().cancel()
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(240L).setInterpolator(curve).start()
    }
}
