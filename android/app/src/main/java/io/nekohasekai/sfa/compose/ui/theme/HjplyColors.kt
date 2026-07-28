package io.nekohasekai.sfa.compose.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Single source of truth for the hjply dashboard palette. Every `Color(0xff…)`
 * used in the dashboard lives here so re-skinning is a one-file change.
 *
 * The dashboard reads colors via [LocalHjplyPalette] so it can switch between
 * [LightHjplyPalette] and [DarkHjplyPalette] without touching call sites.
 * MainActivity wires the active palette in `setContent { ... }`.
 *
 * Note: the inner plate behind the mini app icon is intentionally kept
 * white-ish in both themes so the launcher vector stays readable.
 */
data class HjplyPalette(
    // Brand
    val blue: Color,
    val green: Color,
    // Page background + surfaces
    val surface: Color,
    val card: Color,
    val pillGreenSoft: Color,
    val offBg: Color,
    val offBorder: Color,
    val offIcon: Color,
    val iconPlate: Color,
    // Text
    val textPrimary: Color,
    val textMuted: Color,
    // Error / seed-failure banner
    val errorBannerBg: Color,
    val errorBannerTitle: Color,
    val errorBannerBody: Color,
)

val LightHjplyPalette = HjplyPalette(
    // Brand
    blue = Color(0xff2563eb),
    green = Color(0xff16a34a),
    // Page background + surfaces
    surface = Color(0xfff2f2f7),
    card = Color(0xffffffff),
    pillGreenSoft = Color(0xffdcfce7),
    offBg = Color(0xffedf1f7),
    offBorder = Color(0xffc8d0dc),
    offIcon = Color(0xff8a94a6),
    iconPlate = Color(0xffffffff),
    // Text
    textPrimary = Color(0xff111827),
    textMuted = Color(0xff8a8e97),
    // Error / seed-failure banner
    errorBannerBg = Color(0xfffdecec),
    errorBannerTitle = Color(0xffb91c1c),
    errorBannerBody = Color(0xff7f1d1d),
)

val DarkHjplyPalette = HjplyPalette(
    // Brand — bumped a step lighter for contrast on dark surfaces
    blue = Color(0xff3b82f6),
    green = Color(0xff22c55e),
    // Page background + surfaces
    surface = Color(0xff0f1115),
    card = Color(0xff1a1d24),
    pillGreenSoft = Color(0xff14532d),
    offBg = Color(0xff1f2937),
    offBorder = Color(0xff374151),
    offIcon = Color(0xff9ca3af),
    // Keep the icon plate light so the launcher vector stays high-contrast
    // regardless of system theme. This is a deliberate "logo lockup" choice.
    iconPlate = Color(0xffffffff),
    // Text
    textPrimary = Color(0xfff3f4f6),
    textMuted = Color(0xff9ca3af),
    // Error / seed-failure banner
    errorBannerBg = Color(0xff3b0a0a),
    errorBannerTitle = Color(0xfffca5a5),
    errorBannerBody = Color(0xfffecaca),
)

/**
 * CompositionLocal that carries the active [HjplyPalette] down the tree.
 * Defaults to [LightHjplyPalette] so a screen that forgets to wrap itself
 * (e.g. previews, isolated tests) still gets a sensible look.
 *
 * `staticCompositionLocalOf` is intentional: the value rarely changes within
 * a single composition, so tracking reads at this level is unnecessary.
 */
val LocalHjplyPalette = staticCompositionLocalOf { LightHjplyPalette }
