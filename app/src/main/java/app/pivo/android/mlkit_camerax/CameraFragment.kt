package app.pivo.android.mlkit_camerax

import android.content.res.Configuration
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.widget.ImageButton
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.Navigation
import app.pivo.android.mlkit_camerax.barcodescanning.BarcodeGraphic
import app.pivo.android.mlkit_camerax.common.FrameMetadata
import app.pivo.android.mlkit_camerax.common.GraphicOverlay
import com.google.android.gms.vision.CameraSource
import com.google.firebase.ml.vision.FirebaseVision
import com.google.firebase.ml.vision.barcode.FirebaseVisionBarcode
import com.google.firebase.ml.vision.barcode.FirebaseVisionBarcodeDetectorOptions
import com.google.firebase.ml.vision.common.FirebaseVisionImage
import com.google.firebase.ml.vision.common.FirebaseVisionImageMetadata
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class CameraFragment : Fragment() {

    private lateinit var container:ConstraintLayout
    private lateinit var viewFinder:PreviewView
    private lateinit var graphicOverlay:GraphicOverlay

    private var displayId = -1
    private var lensFacing:Int = CameraSelector.LENS_FACING_BACK
    private var preview: Preview? = null
    private var imageAnalyser:ImageAnalysis?= null
    private var imageCapture:ImageCapture?= null
    private var cameraProvider:ProcessCameraProvider?=null

    /**
     * Blocking camera operations are performed using this executor
     */
    private lateinit var cameraExecutor:ExecutorService

    override fun onResume() {
        super.onResume()

        // Make sure that all permissions are still present, since the user could remove them while the app was in pause state
        if (!PermissionsFragment.hasPermissions(requireContext())){
            Navigation.findNavController(requireActivity(), R.id.fragment_container).navigate(CameraFragmentDirections.actionCameraToPermissions())
        }

        // start orientation event listener when the app becomes active
        setOrientationListener(true)
    }

    override fun onPause() {
        super.onPause()

        // stop orientation event listener when the app goes to Pause
        setOrientationListener(false)
    }

    override fun onDestroyView() {
        super.onDestroyView()

        // shut down our background executor
        cameraExecutor.shutdown()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_camera, container, false)


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        container = view as ConstraintLayout
        viewFinder = container.findViewById(R.id.view_finder)
        graphicOverlay = container.findViewById(R.id.graphic_overlay)

        // initialize our background executor
        cameraExecutor = Executors.newSingleThreadExecutor()

        viewFinder.post {
            // keep track of the display in which this view is attached
            displayId = viewFinder.display.displayId

            // Build UI controls
            updateCameraUI()

            // set up camera and its use cases
            setUpCamera()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        // redraw the camera UI controls
        updateCameraUI()

        // enable or disable switching between cameras
        updateCameraSwitchButton()
    }

    private var orientationEventListener: OrientationEventListener? = null
    private fun setOrientationListener(isEnable: Boolean) {
        if (orientationEventListener == null) {
            orientationEventListener = object : OrientationEventListener(requireContext()) {
                override fun onOrientationChanged(orientation: Int) {
                    // Monitors orientation values to determine the target rotation value
                    val rotation = if (orientation in 45..134) {
                        Surface.ROTATION_270
                    } else if (orientation in 135..224) {
                        Surface.ROTATION_180
                    } else if (orientation in 225..314) {
                        Surface.ROTATION_90
                    } else {
                        Surface.ROTATION_0
                    }
                    // set rotation
                    imageCapture?.targetRotation = rotation
                    imageAnalyser?.targetRotation = rotation
                }
            }
        }

        if (isEnable) {
            orientationEventListener!!.enable()
        } else {
            orientationEventListener!!.disable()
        }
    }

    private fun setUpCamera(){
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(Runnable {

            // camera provider
            cameraProvider = cameraProviderFuture.get()

            // select lens facing depending on the available cameras
            lensFacing = when{
                hasBackCamera() -> CameraSelector.LENS_FACING_BACK
                hasFrontCamera() -> CameraSelector.LENS_FACING_FRONT
                else -> throw IllegalStateException("Back and front camera are unavailable")
            }

            // enable or disable switching between cameras
            updateCameraSwitchButton()

            // build and bind the camera use cases
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    /** Enabled or disabled a button to switch cameras depending on the available cameras */
    private fun updateCameraSwitchButton(){
        val switchCamerasButton = container.findViewById<ImageButton>(R.id.camera_switch_button)
        try {
            switchCamerasButton.isEnabled = hasBackCamera() && hasFrontCamera()
        } catch (exception: CameraInfoUnavailableException) {
            switchCamerasButton.isEnabled = false
        }
    }

    private fun updateCameraUI(){

        container.findViewById<ConstraintLayout>(R.id.camera_ui_container)?.let {
            container.removeView(it)
        }

        val controls = View.inflate(requireContext(), R.layout.camera_ui_container, container)

        controls.findViewById<ImageButton>(R.id.camera_switch_button).let {
            // Disable the button until the camera is set up
            it.isEnabled = false

            // Listener for button used to switch cameras. Only called if the button is enabled
            it.setOnClickListener {
                lensFacing = if (CameraSelector.LENS_FACING_FRONT == lensFacing) {
                    CameraSelector.LENS_FACING_BACK
                } else {
                    CameraSelector.LENS_FACING_FRONT
                }
                // Re-bind use cases to update selected camera
                bindCameraUseCases()
            }
        }
    }

    private fun bindCameraUseCases(){
        // Get screen metrics used to setup camera for full screen resolution
        val metrics = DisplayMetrics().also { viewFinder.display.getRealMetrics(it) }
        Log.d(TAG, "Screen metrics: ${metrics.widthPixels} x ${metrics.heightPixels}")

        val screenAspectRatio = aspectRatio(metrics.widthPixels, metrics.heightPixels)
        Log.d(TAG, "Preview aspect ratio: $screenAspectRatio")

        val rotation = viewFinder.display.rotation

        // CameraProvider
        val cameraProvider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")

        // Camera selector
        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        //preview
        preview = Preview.Builder()
             // We request aspect ratio but no resolution
            .setTargetAspectRatio(screenAspectRatio)
            // set initial target rotation
            .setTargetRotation(rotation)
            .build()

        //image capture
        imageCapture = ImageCapture.Builder()
            // We request aspect ratio but no resolution to match preview config, but letting
            // CameraX optimize for whatever specific resolution best fits our use cases
            .setTargetAspectRatio(screenAspectRatio)
            // Set initial target rotation, we will have to call this again if rotation changes
            // during the lifecycle of this use case
            .setTargetRotation(rotation)
            .build()

        // image analyzer
        imageAnalyser = ImageAnalysis.Builder()
            // We request aspect ratio but no resolution
            .setTargetAspectRatio(screenAspectRatio)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            // Set initial target rotation, we will have to call this again if rotation changes
            // during the lifecycle of this use case
            .setTargetRotation(rotation)
            .build()
            // the analyzer can be assigned to the instance
            .also {
                it.setAnalyzer(cameraExecutor, QrCodeAnalyzer { list: List<FirebaseVisionBarcode>, frameMetadata: FrameMetadata -> drawResult(list, frameMetadata) })
            }

        // must unbind the use cases before rebinding them
        cameraProvider.unbindAll()

        try {
            // A variable number of use cases can be passed here -
            // camera process provides access to cameraControl and CameraInfo
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture, imageAnalyser)

            // Attach the viewfinder's surface provider to preview use case
            preview?.setSurfaceProvider(viewFinder.createSurfaceProvider())
        }catch (exc:Exception){
            Log.e(TAG, "Use Case binding failed", exc)
        }
    }

    private fun hasFrontCamera():Boolean{
        return cameraProvider?.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)?:false
    }

    private fun hasBackCamera():Boolean{
        return cameraProvider?.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)?:false
    }

    /**
     *  [androidx.camera.core.ImageAnalysisConfig] requires enum value of
     *  [androidx.camera.core.AspectRatio]. Currently it has values of 4:3 & 16:9.
     *
     *  Detecting the most suitable ratio for dimensions provided in @params by counting absolute
     *  of preview ratio to one of the provided values.
     *
     *  @param width - preview width
     *  @param height - preview height
     *  @return suitable aspect ratio
     */
    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = max(width, height).toDouble() / min(width, height)
        if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }


    inner class QrCodeAnalyzer(private val onQrCodesDetected: (qrCodes: List<FirebaseVisionBarcode>, frameMetadata:FrameMetadata) -> Unit) : ImageAnalysis.Analyzer {

        override fun analyze(image: ImageProxy) {
            val options = FirebaseVisionBarcodeDetectorOptions.Builder()
                .setBarcodeFormats(FirebaseVisionBarcode.FORMAT_QR_CODE, FirebaseVisionBarcode.FORMAT_AZTEC)
                .build()

            val detector = FirebaseVision.getInstance().getVisionBarcodeDetector(options)

            val rotation = rotationDegreesToFirebaseRotation(image.imageInfo.rotationDegrees)
            val visionImage = FirebaseVisionImage.fromMediaImage(image.image!!, rotation)

            val frameMetaData = FrameMetadata.Builder()
                .setWidth(image.width)
                .setHeight(image.height)
                .setRotation(rotation)
                .setCameraFacing(lensFacing)
                .build()

            detector.detectInImage(visionImage)
                .addOnSuccessListener { barCodes ->
                    onQrCodesDetected(barCodes, frameMetaData)
                }
                .addOnFailureListener {
                    Log.e("QrCodeAnalyzer", "something went wrong", it)
                }
            image.close()
        }

        private fun rotationDegreesToFirebaseRotation(rotationDegrees: Int): Int {
            return when (rotationDegrees) {
                0 -> FirebaseVisionImageMetadata.ROTATION_0
                90 -> FirebaseVisionImageMetadata.ROTATION_90
                180 -> FirebaseVisionImageMetadata.ROTATION_180
                270 -> FirebaseVisionImageMetadata.ROTATION_270
                else -> throw IllegalArgumentException("Not supported")
            }
        }
    }

    private fun drawResult(results:List<FirebaseVisionBarcode>, frameMetadata: FrameMetadata){
        results.forEach {
            Log.e(TAG, "QR Code detected: ${it.rawValue}.")
        }

        val facing = if(lensFacing==1)CameraSource.CAMERA_FACING_BACK else CameraSource.CAMERA_FACING_FRONT
        // clear graphic overlay
        graphicOverlay.setCameraInfo(frameMetadata.height, frameMetadata.width, facing)
        graphicOverlay.clear()

        results.forEach {
            val barCodeOverlay = BarcodeGraphic(graphicOverlay, it, facing, frameMetadata.rotation)
            graphicOverlay.add(barCodeOverlay)
        }
        graphicOverlay.postInvalidate()
    }

    companion object{
        private const val TAG = "CameraXBasic"
        private const val RATIO_4_3_VALUE = 4.0 / 3.0
        private const val RATIO_16_9_VALUE = 16.0 / 9.0
    }
}


