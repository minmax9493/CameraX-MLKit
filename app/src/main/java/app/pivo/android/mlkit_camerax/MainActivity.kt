package app.pivo.android.mlkit_camerax

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.FrameLayout

private const val IMMERSIVE_FLAG_TIMEOUT = 500L


class MainActivity : AppCompatActivity() {

    private lateinit var container:FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        container = findViewById(R.id.fragment_container)
    }

    override fun onResume() {
        super.onResume()

        container.postDelayed({
            container.systemUiVisibility = FLAGS_FULLSCREEN
        }, IMMERSIVE_FLAG_TIMEOUT)

    }
}
