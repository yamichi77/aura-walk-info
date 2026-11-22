package io.github.yamichi77.aurawalkinfo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.xr.compose.platform.LocalSpatialCapabilities
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialPanel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                if (LocalSpatialCapabilities.current.isSpatialUiEnabled) {
                    Subspace {
                        SpatialPanel {
                            HelloLabel(Modifier.padding(16.dp))
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color.Black),
                        contentAlignment = Alignment.Center,
                    ) {
                        HelloLabel()
                    }
                }
            }
        }
    }
}

@Composable
private fun HelloLabel(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.hello),
        modifier = modifier,
        color = Color(0xFFE0F7FF),
        style = MaterialTheme.typography.titleMedium,
    )
}
