package com.triplethreats.masteria.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.triplethreats.masteria.ui.components.GlassSurface
import com.triplethreats.masteria.ui.components.LocalOverlay
import com.triplethreats.masteria.ui.components.OverlayHost
import com.triplethreats.masteria.ui.components.Render
import com.triplethreats.masteria.ui.components.pressable
import com.triplethreats.masteria.ui.home.TodayScreen
import com.triplethreats.masteria.ui.map.MapScreen
import com.triplethreats.masteria.ui.mentor.MentorScreen
import com.triplethreats.masteria.ui.profile.ProfileScreen
import com.triplethreats.masteria.ui.scan.ScanScreen
import com.triplethreats.masteria.ui.theme.Masteria
import com.triplethreats.masteria.ui.theme.Springs
import com.triplethreats.masteria.ui.theme.rememberHaptics

enum class Tab(val label: String, val icon: ImageVector) {
    Today("Today", Icons.Rounded.WbSunny),
    Map("Map", Icons.Rounded.Map),
    Scan("Scan", Icons.Rounded.DocumentScanner),
    Mentor("Mentor", Icons.Rounded.AutoAwesome),
    Profile("Profile", Icons.Rounded.AccountCircle),
}

@Composable
fun MainScreen(
    onStartQuest: (topicId: String?, boss: Boolean) -> Unit,
    onStartPendingQuest: () -> Unit,
    onOpenProgress: () -> Unit,
    onOpenSettings: () -> Unit,
    onNeedsDiagnostic: () -> Unit,
    onSignedOut: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val current = Tab.entries[tab]
    val saveable = rememberSaveableStateHolder()
    val reduced = Masteria.reducedMotion

    val overlay = remember { OverlayHost() }

    BackHandler(enabled = current != Tab.Today) { tab = 0 }

    CompositionLocalProvider(LocalOverlay provides overlay) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Masteria.colors.background)
    ) {
        // Tabs cross-fade in place (iOS tab switches don't slide); each keeps its scroll state.
        AnimatedContent(
            targetState = current,
            transitionSpec = { fadeIn(tween(if (reduced) 120 else 180)) togetherWith fadeOut(tween(120)) },
            label = "tab",
        ) { t ->
            saveable.SaveableStateProvider(t.name) {
                when (t) {
                    Tab.Today -> TodayScreen(
                        onStartQuest = onStartQuest,
                        onOpenMap = { tab = Tab.Map.ordinal },
                        onOpenScan = { tab = Tab.Scan.ordinal },
                        onOpenMentor = { tab = Tab.Mentor.ordinal },
                        onOpenProgress = onOpenProgress,
                        onOpenSettings = onOpenSettings,
                        onNeedsDiagnostic = onNeedsDiagnostic,
                    )
                    Tab.Map -> MapScreen(onStartQuest = onStartQuest)
                    Tab.Scan -> ScanScreen(
                        active = current == Tab.Scan,
                        onQuestReady = onStartPendingQuest,
                    )
                    Tab.Mentor -> MentorScreen()
                    Tab.Profile -> ProfileScreen(
                        onOpenProgress = onOpenProgress,
                        onOpenSettings = onOpenSettings,
                        onNeedsDiagnostic = onNeedsDiagnostic,
                        onSignedOut = onSignedOut,
                    )
                }
            }
        }
        TabBar(
            current = current,
            onSelect = { tab = it.ordinal },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        overlay.Render()
    }
    }
}

/**
 * Floating glass tab bar. Content scrolls beneath it; the selected tab is marked by a tinted
 * capsule that glides between items on a spring, plus a colour and weight change (not colour alone).
 */
@Composable
private fun TabBar(current: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val c = Masteria.colors
    val haptics = rememberHaptics()
    val index by animateFloatAsState(current.ordinal.toFloat(), Springs.sheet(), label = "tabIndicator")
    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        GlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            shape = RoundedCornerShape(32.dp),
            elevation = 18.dp,
            blur = 28.dp,
        ) {
            Box(Modifier.fillMaxSize().padding(6.dp)) {
                // Sliding selection capsule
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                    val w = maxWidth / Tab.entries.size
                    Box(
                        Modifier
                            .graphicsLayer { translationX = (w * index).toPx() }
                            .size(w, maxHeight)
                            .clip(RoundedCornerShape(26.dp))
                            .background(c.brandSoft)
                    )
                }
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Tab.entries.forEach { t ->
                        val selected = t == current
                        val tint by animateColorAsState(if (selected) c.brand else c.secondaryLabel, Springs.snappy(), label = "tabTint")
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .pressable(scale = 0.9f, haptic = false, role = Role.Tab) {
                                    if (!selected) haptics.selection()
                                    onSelect(t)
                                }
                                .semantics {
                                    this.selected = selected
                                    contentDescription = t.label
                                },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            if (t == Tab.Scan) {
                                Box(
                                    Modifier
                                        .size(34.dp)
                                        .shadow(if (selected) 0.dp else 6.dp, CircleShape, ambientColor = c.brand, spotColor = c.brand)
                                        .clip(CircleShape)
                                        .background(if (selected) c.brand else c.brand.copy(alpha = 0.92f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(t.icon, null, tint = Color.White, modifier = Modifier.size(19.dp))
                                }
                            } else {
                                Icon(t.icon, null, tint = tint, modifier = Modifier.size(24.dp))
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    t.label,
                                    style = if (selected) Masteria.type.caption2 else Masteria.type.caption2.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                                    color = tint,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
