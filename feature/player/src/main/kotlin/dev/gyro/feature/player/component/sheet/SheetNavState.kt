package dev.gyro.feature.player.component.sheet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember

/**
 * 抽屉内部导航的唯一状态源。
 *
 * 只保存路由返回栈；当前页、层级、能否返回都从栈派生，避免宿主、内容体、外部门控各自
 * 维护一份"当前页"导致不一致。设置抽屉用 [push]/[goBack] 走层级导航，播放列表抽屉用
 * [replace] 做同一层内的平铺切换。
 */
@Stable
class SheetNavState(initial: PlayerSheetRoute) {

    private val backStack = mutableStateListOf(initial)

    val current: PlayerSheetRoute get() = backStack.last()

    val depth: Int get() = backStack.size

    val canGoBack: Boolean get() = backStack.size > 1

    /** 进入下一层。重复点击当前页不会重复入栈。 */
    fun push(route: PlayerSheetRoute) {
        if (route == current) return
        backStack.add(route)
    }

    /** 同层替换，不产生返回层级（选集/章节的 pill 切换）。 */
    fun replace(route: PlayerSheetRoute) {
        if (route == current) return
        backStack[backStack.lastIndex] = route
    }

    /** 返回上一层；已在根页时返回 false，由调用方决定是否关闭抽屉。 */
    fun goBack(): Boolean {
        if (!canGoBack) return false
        backStack.removeAt(backStack.lastIndex)
        return true
    }
}

/**
 * 抽屉导航状态随抽屉实例创建：关闭后重新打开会回到初始页，而不是继承上次的二级页。
 * 宿主 Activity 已声明处理 orientation/screenSize 配置变化，旋转不会重建组合，
 * 因此这里不需要 rememberSaveable。
 */
@Composable
fun rememberSheetNavState(initial: PlayerSheetRoute): SheetNavState =
    remember(initial) { SheetNavState(initial) }
