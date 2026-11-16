package io.github.soichi11208.zinna.keyboard

import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/**
 * キーボードの軽いアニメーション。
 *
 * どれも 80〜140ミリ秒の短いもので、透明度と大きさ（alpha / scale）だけを動かす。
 * この2つは画面の描き直しをせずにGPUが処理するので、入力の遅れや電池への影響はほぼない。
 * 1文字打つごとに動くもの（候補の更新など）には付けていない。
 *
 *   fadeIn  … パネルを開く・画面を切り替えるとき、ふわっと出す
 *   tap     … 候補やボタンを押したとき、少し縮んで戻る
 *   pop     … アイコンが変わったとき（鍵の開け閉めなど）、ぽんと出す
 */
object Anim {
    private val decelerate = DecelerateInterpolator()

    fun fadeIn(view: View, from: Float = 0f, durationMs: Long = 140L) {
        view.animate().cancel()
        view.alpha = from
        view.animate().alpha(1f).setDuration(durationMs).setInterpolator(decelerate)
            .withLayer().start()
    }

    fun tap(view: View) {
        view.animate().cancel()
        view.animate().scaleX(0.9f).scaleY(0.9f).setDuration(60L).withEndAction {
            view.animate().scaleX(1f).scaleY(1f).setDuration(90L).setInterpolator(decelerate).start()
        }.start()
    }

    fun pop(view: View) {
        view.animate().cancel()
        view.scaleX = 0.6f
        view.scaleY = 0.6f
        view.animate().scaleX(1f).scaleY(1f).setDuration(160L)
            .setInterpolator(OvershootInterpolator(2f)).start()
    }
}
