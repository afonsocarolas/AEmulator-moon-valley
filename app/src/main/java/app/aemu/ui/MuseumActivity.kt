/* Added for AEmulator Moon Valley, 2026-11-16: the Classic Android Museum, one exhibit per Android version.
 * GPL-3.0; see LICENSE and NOTICE.md. Android is a trademark of Google LLC. */
package app.aemu.ui

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aemu.AppPrefs
import app.aemu.R

class MuseumActivity : ComponentActivity() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppPrefs.wrap(base))
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { AemuTheme { MuseumScreen(::finish) } }
    }
}

/** How an era looked: the colours of its screen and the typeface feel of its version badge. */
private enum class MuseumEra(val screen: List<Color>, val ink: Color, val accent: Color, val mono: Boolean) {
    EARLY(listOf(Color(0xFF3A3A3A), Color(0xFF111111)), Color(0xFFE0E0E0), Color(0xFFA4C639), true),       // 1.x–2.2: grey bars, robot green
    GINGERBREAD(listOf(Color(0xFF1B1B1B), Color(0xFF000000)), Color(0xFFFFFFFF), Color(0xFF8BC34A), true), // 2.3: black and green
    HOLO(listOf(Color(0xFF0A1A24), Color(0xFF000000)), Color(0xFFFFFFFF), Color(0xFF33B5E5), false),        // 3.x–4.4: Holo blue on black
    MATERIAL(listOf(Color(0xFF26A69A), Color(0xFF00695C)), Color.White, Color(0xFFFFC107), false),          // 5–9: Material colour
    MODERN(listOf(Color(0xFF3DDC84), Color(0xFF0F6E44)), Color.White, Color(0xFF073042), false),            // 10–11: new Android green
    YOU(listOf(Color(0xFFE8DEF8), Color(0xFFB9D4F6)), Color(0xFF1D1B20), Color(0xFF6750A4), false),         // 12+: Material You pastels
}

private data class Exhibit(
    val version: String, val name: String, val year: String, val api: String, val era: MuseumEra, val notes: List<String>,
)

private val EXHIBITS = listOf(
    Exhibit("1.0 – 1.1", "The beginning", "2008 – 2009", "API 1 – 2", MuseumEra.EARLY, listOf(
        "Launched on the HTC Dream (T-Mobile G1), with a slide-out keyboard",
        "Android Market, a pull-down notification shade and home-screen shortcuts",
        "Gmail, Maps, a full web browser and YouTube built in")),
    Exhibit("1.5", "Cupcake", "2009", "API 3", MuseumEra.EARLY, listOf(
        "The first on-screen keyboard: phones no longer needed physical keys",
        "Home-screen widgets from other apps",
        "Video recording and uploads straight to YouTube")),
    Exhibit("1.6", "Donut", "2009", "API 4", MuseumEra.EARLY, listOf(
        "Support for different screen sizes and resolutions",
        "Quick Search Box to search the phone and the web together",
        "A battery-usage screen showing what drains power")),
    Exhibit("2.0 – 2.1", "Eclair", "2009 – 2010", "API 5 – 7", MuseumEra.EARLY, listOf(
        "Several accounts on one phone, and contacts synced from all of them",
        "Google Maps Navigation with free turn-by-turn directions",
        "Live wallpapers; the era of the Motorola Droid and Nexus One")),
    Exhibit("2.2", "Froyo", "2010", "API 8", MuseumEra.EARLY, listOf(
        "A JIT compiler made apps much faster",
        "Portable Wi-Fi hotspot and USB tethering",
        "Apps could be installed on the SD card")),
    Exhibit("2.3", "Gingerbread", "2010", "API 9 – 10", MuseumEra.GINGERBREAD, listOf(
        "A darker black-and-green look and a faster keyboard",
        "NFC, front-facing cameras and gyroscope support",
        "Launched on the Nexus S; one of the longest-lived versions ever")),
    Exhibit("3.x", "Honeycomb", "2011", "API 11 – 13", MuseumEra.HOLO, listOf(
        "Made only for tablets, first on the Motorola Xoom",
        "The 'holographic' look, an action bar and on-screen system buttons",
        "Fragments, letting apps split big screens into panes")),
    Exhibit("4.0", "Ice Cream Sandwich", "2011", "API 14 – 15", MuseumEra.HOLO, listOf(
        "Holo for phones and tablets alike, with the new Roboto font",
        "On-screen navigation buttons on the Galaxy Nexus",
        "Face Unlock and a data-usage manager")),
    Exhibit("4.1 – 4.3", "Jelly Bean", "2012 – 2013", "API 16 – 18", MuseumEra.HOLO, listOf(
        "Project Butter: smooth 60 fps animations everywhere",
        "Google Now cards and expandable, actionable notifications",
        "Several user profiles on tablets; launched with the Nexus 7")),
    Exhibit("4.4", "KitKat", "2013", "API 19", MuseumEra.HOLO, listOf(
        "Project Svelte: ran well on phones with just 512 MB of RAM",
        "\"OK Google\" from the home screen, and immersive full-screen mode",
        "ART appeared as an experimental runtime; launched on the Nexus 5")),
    Exhibit("5.x", "Lollipop", "2014", "API 21 – 22", MuseumEra.MATERIAL, listOf(
        "Material Design: bold colours, cards, shadows and motion",
        "ART replaced Dalvik, and 64-bit support arrived",
        "Notifications on the lock screen; Project Volta for battery life")),
    Exhibit("6.0", "Marshmallow", "2015", "API 23", MuseumEra.MATERIAL, listOf(
        "Runtime permissions: apps ask when they need something",
        "Doze mode saves battery while the phone sits still",
        "A standard fingerprint API and USB-C support")),
    Exhibit("7.x", "Nougat", "2016", "API 24 – 25", MuseumEra.MATERIAL, listOf(
        "Split-screen multitasking",
        "Bundled notifications with quick reply",
        "Vulkan graphics and seamless system updates; the first Google Pixel")),
    Exhibit("8.x", "Oreo", "2017", "API 26 – 27", MuseumEra.MATERIAL, listOf(
        "Project Treble separated the system from vendor code",
        "Picture-in-picture, notification dots and autofill",
        "Android Go edition for entry-level phones")),
    Exhibit("9", "Pie", "2018", "API 28", MuseumEra.MATERIAL, listOf(
        "Gesture navigation with the 'pill' home button",
        "Digital Wellbeing and adaptive battery",
        "Support for display cutouts (notches)")),
    Exhibit("10", "Quince Tart (internal)", "2019", "API 29", MuseumEra.MODERN, listOf(
        "The first version without a public dessert name",
        "System-wide dark theme and full gesture navigation",
        "Scoped storage, Live Caption and Project Mainline updates")),
    Exhibit("11", "Red Velvet Cake (internal)", "2020", "API 30", MuseumEra.MODERN, listOf(
        "A Conversations section and chat bubbles",
        "One-time permissions and a built-in screen recorder",
        "Media controls in Quick Settings")),
    Exhibit("12 / 12L", "Snow Cone (internal)", "2021 – 2022", "API 31 – 32", MuseumEra.YOU, listOf(
        "Material You: colours taken from your wallpaper",
        "Privacy dashboard and camera/microphone indicators",
        "12L tuned Android for tablets and foldables")),
    Exhibit("13", "Tiramisu (internal)", "2022", "API 33", MuseumEra.YOU, listOf(
        "Themed app icons and per-app languages",
        "Apps must ask before sending notifications",
        "A system photo picker")),
    Exhibit("14", "Upside Down Cake (internal)", "2023", "API 34", MuseumEra.YOU, listOf(
        "More lock-screen customisation",
        "Predictive back gesture and regional preferences",
        "Health Connect built into the system")),
    Exhibit("15", "Vanilla Ice Cream (internal)", "2024", "API 35", MuseumEra.YOU, listOf(
        "Private space for hiding sensitive apps",
        "Edge-to-edge apps by default",
        "Partial screen sharing of a single app")),
    Exhibit("16", "Baklava (internal)", "2025", "API 36", MuseumEra.YOU, listOf(
        "Released earlier in the year, on a new schedule",
        "Live Updates for progress notifications (rides, deliveries)",
        "Material 3 Expressive design and Advanced Protection mode")),
    Exhibit("17", "Cinnamon Bun (internal)", "2026", "API 37", MuseumEra.YOU, listOf(
        "The newest wing of the museum",
        "AEmulator Moon Valley can boot it too")),
)

@Composable
private fun MuseumScreen(onBack: () -> Unit) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.museum_title)) },
                subtitle = { Text(stringResource(R.string.museum_subtitle)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
                },
                scrollBehavior = scroll,
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 8.dp,
                bottom = pad.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { MuseumIntro() }
            items(EXHIBITS, key = { it.version }) { ExhibitCard(it) }
            item {
                Text(stringResource(R.string.museum_footer), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
            }
        }
    }
}

@Composable
private fun MuseumIntro() {
    Card(shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Text(stringResource(R.string.museum_intro), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(20.dp))
    }
}

/** One exhibit: a little "screen" in the colours of its era, then the plaque with what it brought. */
@Composable
private fun ExhibitCard(e: Exhibit) {
    Card(shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Box(
            Modifier.padding(8.dp).fillMaxWidth().height(116.dp).clip(RoundedCornerShape(22.dp))
                .background(Brush.linearGradient(e.era.screen)),
        ) {
            // the era's status bar: a thin strip with a dot of its accent colour
            Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.25f)).padding(horizontal = 14.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(e.era.accent, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(e.year, color = e.era.ink.copy(alpha = 0.85f), fontSize = 11.sp,
                    fontFamily = if (e.era.mono) FontFamily.Monospace else null)
            }
            Column(Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 12.dp)) {
                Text(e.version, color = e.era.ink, fontSize = 32.sp, fontWeight = FontWeight.Bold,
                    fontFamily = if (e.era.mono) FontFamily.Monospace else null)
                Text(e.name, color = e.era.accent, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        }
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${e.year} · ${e.api}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            e.notes.forEach { note ->
                Row {
                    Text("•", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp))
                    Text(note, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
