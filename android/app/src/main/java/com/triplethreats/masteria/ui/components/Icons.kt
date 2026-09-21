package com.triplethreats.masteria.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Balance
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.ChangeHistory
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Exposure
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Functions
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MilitaryTech
import androidx.compose.material.icons.rounded.Percent
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.SquareFoot
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.TrackChanges
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Maps the backend's symbolic icon names (docs/API.md → Icons) to vectors. */
fun symbol(name: String?): ImageVector = when (name) {
    "function" -> Icons.Rounded.Functions
    "divide" -> Icons.Rounded.Calculate
    "plusminus" -> Icons.Rounded.Exposure
    "triangle" -> Icons.Rounded.ChangeHistory
    "angle" -> Icons.Rounded.SquareFoot
    "dice" -> Icons.Rounded.Casino
    "percent" -> Icons.Rounded.Percent
    "scale" -> Icons.Rounded.Balance
    "coins" -> Icons.Rounded.Savings
    "clock" -> Icons.Rounded.Schedule
    "chart" -> Icons.Rounded.BarChart
    "bank" -> Icons.Rounded.AccountBalance
    "code" -> Icons.Rounded.Code
    "box" -> Icons.Rounded.Inventory2
    "layers" -> Icons.Rounded.Layers
    "shapes" -> Icons.Rounded.Category
    "lock" -> Icons.Rounded.Lock
    "puzzle" -> Icons.Rounded.Extension
    "text" -> Icons.Rounded.TextFields
    "atom" -> Icons.Rounded.Science
    "book" -> Icons.AutoMirrored.Rounded.MenuBook
    "spark" -> Icons.Rounded.AutoAwesome
    "flame" -> Icons.Rounded.LocalFireDepartment
    "trophy" -> Icons.Rounded.EmojiEvents
    "sword" -> SwordIcon
    "crown" -> CrownIcon
    "target" -> Icons.Rounded.TrackChanges
    "brain" -> Icons.Rounded.Psychology
    "rocket" -> Icons.Rounded.RocketLaunch
    "star" -> Icons.Rounded.Star
    "scan" -> Icons.Rounded.DocumentScanner
    "medal" -> Icons.Rounded.MilitaryTech
    else -> Icons.Rounded.AutoAwesome
}

/** Boss-battle sword, drawn on the 24-unit icon grid (Material has none). */
val SwordIcon: ImageVector by lazy {
    ImageVector.Builder("Sword", 24.dp, 24.dp, 24f, 24f).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            // blade
            moveTo(14.5f, 17.5f); lineTo(3f, 6f); lineTo(3f, 3f); lineTo(6f, 3f); lineTo(17.5f, 14.5f)
            // guard
            moveTo(13f, 19f); lineTo(19f, 13f)
            // grip + pommel
            moveTo(16f, 16f); lineTo(20f, 20f)
            moveTo(19f, 21f); lineTo(21f, 19f)
        }
    }.build()
}

val CrownIcon: ImageVector by lazy {
    ImageVector.Builder("Crown", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero) {
            moveTo(3f, 8f); lineTo(7.5f, 12f); lineTo(12f, 5f); lineTo(16.5f, 12f); lineTo(21f, 8f)
            lineTo(19f, 18f); lineTo(5f, 18f); close()
            moveTo(5f, 19.5f); lineTo(19f, 19.5f); lineTo(19f, 21f); lineTo(5f, 21f); close()
        }
    }.build()
}
