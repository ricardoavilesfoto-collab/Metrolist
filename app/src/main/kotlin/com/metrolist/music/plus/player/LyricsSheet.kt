/**
 * Metrolist Plus — tarjeta de letra deslizable (como "Muestra de la letra" de Spotify) y botón ⋮ superior.
 *
 * Reemplaza la barra inferior de íconos del reproductor original cuando el Reproductor Plus está activo:
 * la tarjeta se asoma abajo; al deslizarla hacia arriba muestra la letra sincronizada y hacia abajo vuelve
 * al reproductor. Reutiliza la hoja deslizable (`BottomSheet`) y la vista de letra (`InlineLyricsView`) del original.
 */

package com.metrolist.music.plus.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.ui.component.BottomSheet
import com.metrolist.music.ui.component.BottomSheetState
import com.metrolist.music.ui.component.LocalBottomSheetPageState
import com.metrolist.music.ui.component.LocalMenuState
import com.metrolist.music.ui.menu.PlayerMenu
import com.metrolist.music.ui.player.InlineLyricsView
import com.metrolist.music.ui.utils.ShowMediaInfo

/** Visible height of the lyrics card while it peeks at the bottom (without the navigation bar). */
val PlusLyricsPeekHeight = 64.dp

@Composable
fun PlusLyricsSheet(
    state: BottomSheetState,
    mediaMetadata: MediaMetadata?,
    positionProvider: () -> Long,
    contentColor: Color,
    expandedBackground: Color,
    modifier: Modifier = Modifier,
) {
    BottomSheet(
        state = state,
        modifier = modifier,
        collapsedContent = {
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(contentColor.copy(alpha = 0.14f))
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            ) {
                Text(
                    text = stringResource(R.string.lyrics),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    painter = painterResource(R.drawable.expand_less),
                    contentDescription = null,
                    tint = contentColor,
                )
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(expandedBackground)
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom)),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 8.dp, top = 8.dp),
            ) {
                Text(
                    text = stringResource(R.string.lyrics),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { state.collapseSoft() }) {
                    Icon(
                        painter = painterResource(R.drawable.expand_more),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // Lets a downward swipe at the top of the lyrics close the card
                    .nestedScroll(state.preUpPostDownNestedScrollConnection),
            ) {
                InlineLyricsView(
                    mediaMetadata = mediaMetadata,
                    showLyrics = !state.isCollapsed,
                    positionProvider = positionProvider,
                )
            }
            Spacer(Modifier.size(8.dp))
        }
    }
}

@Composable
fun PlusPlayerMenuButton(
    mediaMetadata: MediaMetadata?,
    playerSheetState: BottomSheetState,
    tint: Color,
) {
    val menuState = LocalMenuState.current
    val bottomSheetPageState = LocalBottomSheetPageState.current
    if (LocalPlayerConnection.current == null || mediaMetadata == null) return

    IconButton(
        onClick = {
            menuState.show {
                PlayerMenu(
                    mediaMetadata = mediaMetadata,
                    playerBottomSheetState = playerSheetState,
                    onShowDetailsDialog = {
                        bottomSheetPageState.show { ShowMediaInfo(mediaMetadata.id) }
                    },
                    onDismiss = menuState::dismiss,
                )
            }
        },
    ) {
        Icon(
            painter = painterResource(R.drawable.more_vert),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
    }
}
