package com.appexecutors.picker

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.HapticFeedbackConstants.LONG_PRESS
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.View.*
import android.view.animation.AccelerateDecelerateInterpolator
import android.webkit.MimeTypeMap
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.net.toFile
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup
import androidx.recyclerview.widget.RecyclerView
import com.appexecutors.picker.gallery.BottomSheetMediaRecyclerAdapter
import com.appexecutors.picker.gallery.BottomSheetMediaRecyclerAdapter.Companion.HEADER
import com.appexecutors.picker.gallery.BottomSheetMediaRecyclerAdapter.Companion.SPAN_COUNT
import com.appexecutors.picker.gallery.InstantMediaRecyclerAdapter
import com.appexecutors.picker.gallery.MediaModel
import com.appexecutors.picker.interfaces.MediaClickInterface
import com.appexecutors.picker.interfaces.PermissionCallback
import com.appexecutors.picker.utils.GeneralUtils.getStringDate
import com.appexecutors.picker.utils.GeneralUtils.hideStatusBar
import com.appexecutors.picker.utils.GeneralUtils.manipulateBottomSheetVisibility
import com.appexecutors.picker.utils.GeneralUtils.showStatusBar
import com.appexecutors.picker.utils.HeaderItemDecoration
import com.appexecutors.picker.utils.MediaConstants.IMAGE_VIDEO_URI
import com.appexecutors.picker.utils.MediaConstants.getImageVideoCursor
import com.appexecutors.picker.utils.PermissionUtils
import com.appexecutors.picker.utils.PickerOptions
import com.google.android.material.bottomsheet.BottomSheetBehavior
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.collections.ArrayList
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@Suppress("DEPRECATION")
class Picker : AppCompatActivity() {

    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var flashMode: Int = ImageCapture.FLASH_MODE_OFF
    private var statusBarSize = 0

    private lateinit var outputDirectory: File
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var mPickerOptions: PickerOptions
    private lateinit var imageViewChangeCamera: ImageView
    private lateinit var viewFinder: PreviewView
    private lateinit var imageViewClick: ImageButton
    private lateinit var imageViewFlash: ImageView
    private lateinit var constraintCheck: ConstraintLayout
    private lateinit var textViewOk: TextView
    private lateinit var imageViewCheck: ImageView
    private lateinit var constraintBottomSheetTop: ConstraintLayout
    private lateinit var imageViewBack: ImageView
    private lateinit var textViewImageCount: TextView
    private lateinit var textViewTopSelect: TextView
    private lateinit var recyclerViewInstantMedia: RecyclerView
    private lateinit var recyclerViewBottomSheetMedia: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_picker)

        mPickerOptions = intent?.getSerializableExtra(PICKER_OPTIONS) as PickerOptions

        constraintCheck = findViewById(R.id.constraint_check)
        textViewOk = findViewById(R.id.text_view_ok)
        imageViewCheck = findViewById(R.id.image_view_check)
        constraintBottomSheetTop = findViewById(R.id.constraint_bottom_sheet_top)
        imageViewBack = findViewById(R.id.image_view_back)
        textViewImageCount = findViewById(R.id.text_view_image_count)
        textViewTopSelect = findViewById(R.id.text_view_top_select)
        recyclerViewInstantMedia = findViewById(R.id.recycler_view_instant_media)
        recyclerViewBottomSheetMedia = findViewById<RecyclerView>(R.id.recycler_view_bottom_sheet_media)

        viewFinder = findViewById(R.id.viewFinder)
        viewFinder.post {
            if (allPermissionsGranted()) {
                //to avoid NullPointerException for Display.getRealMetrics which comes for some cases
                Handler().postDelayed({ startCamera() }, 100)
            }
        }

        // Setup the listener for take photo button
        imageViewClick = findViewById(R.id.image_view_click)
        imageViewClick.setOnClickListener { takePhoto() }

        outputDirectory = getOutputDirectory()

        cameraExecutor = Executors.newSingleThreadExecutor()

        window.decorView.setOnApplyWindowInsetsListener { _, insets ->
            statusBarSize = insets.systemWindowInsetTop
            insets
        }
        showStatusBar(this)
        Handler().postDelayed({ getMedia() }, 500)
        initViews()
    }


    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener(Runnable {
            // Used to bind the lifecycle of cameras to the lifecycle owner
            cameraProvider = cameraProviderFuture.get()

            // Select lensFacing depending on the available cameras
            lensFacing = when {
                hasBackCamera() -> CameraSelector.LENS_FACING_BACK
                hasFrontCamera() -> CameraSelector.LENS_FACING_FRONT
                else -> throw IllegalStateException("Back and front camera are unavailable")
            }

            bindCameraUseCases()
            setUpPinchToZoom()
            setupFlash()

        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initViews(){
        imageViewChangeCamera = findViewById(R.id.image_view_change_camera)
        imageViewChangeCamera.let {
            it.setOnClickListener {
                lensFacing = if (CameraSelector.LENS_FACING_FRONT == lensFacing) {
                    CameraSelector.LENS_FACING_BACK
                } else {
                    CameraSelector.LENS_FACING_FRONT
                }
                // Re-bind use cases to update selected camera
                bindCameraUseCases()
                setUpPinchToZoom()
                setupFlash()
            }
        }

        if (!mPickerOptions.allowFrontCamera) imageViewChangeCamera.visibility = GONE
        if (mPickerOptions.excludeVideos) imageViewChangeCamera.visibility = INVISIBLE
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setUpPinchToZoom() {
        val listener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val currentZoomRatio: Float = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1F
                val delta = detector.scaleFactor
                camera?.cameraControl?.setZoomRatio(currentZoomRatio * delta)
                return true
            }
        }
        val scaleGestureDetector = ScaleGestureDetector(this, listener)

        viewFinder.setOnTouchListener { _, event ->
            viewFinder.post {
                scaleGestureDetector.onTouchEvent(event)
            }
            return@setOnTouchListener true
        }
    }

    private fun setupFlash() {

        flashMode = ImageCapture.FLASH_MODE_OFF
        imageViewFlash = findViewById(R.id.image_view_flash)
        imageViewFlash.setImageDrawable(
            ResourcesCompat.getDrawable(
                resources, R.drawable.ic_baseline_flash_off_36, null
            )
        )

        val hasFlash = camera?.cameraInfo?.hasFlashUnit()

        if (hasFlash != null && hasFlash) imageViewFlash.visibility = VISIBLE
        else imageViewFlash.visibility = GONE

        imageViewFlash.setOnClickListener {
            when (flashMode) {
                ImageCapture.FLASH_MODE_OFF -> {
                    imageViewFlash.setImageDrawable(
                        ResourcesCompat.getDrawable(
                            resources, R.drawable.ic_baseline_flash_on_36, null
                        )
                    )
                    camera?.cameraControl?.enableTorch(true)
                    flashMode = ImageCapture.FLASH_MODE_ON
                }
                ImageCapture.FLASH_MODE_ON -> {
                    imageViewFlash.setImageDrawable(
                        ResourcesCompat.getDrawable(
                            resources, R.drawable.ic_baseline_flash_off_36, null
                        )
                    )
                    camera?.cameraControl?.enableTorch(false)
                    flashMode = ImageCapture.FLASH_MODE_OFF
                }

            }
        }

    }

    /** Declare and bind preview, capture and analysis use cases */
    private fun bindCameraUseCases() {

        // Get screen metrics used to setup camera for full screen resolution
        val metrics = DisplayMetrics().also { viewFinder.display.getRealMetrics(it) }
        Log.d(TAG, "Screen metrics: ${metrics.widthPixels} x ${metrics.heightPixels}")

        val screenAspectRatio = aspectRatio(metrics.widthPixels, metrics.heightPixels)
        Log.d(TAG, "Preview aspect ratio: $screenAspectRatio")

        val rotation = viewFinder.display.rotation

        // CameraProvider
        val cameraProvider = cameraProvider
            ?: throw IllegalStateException("Camera initialization failed.")

        // CameraSelector
        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        // Preview
        preview = Preview.Builder()
            // We request aspect ratio but no resolution
            .setTargetAspectRatio(screenAspectRatio)
            // Set initial target rotation
            .setTargetRotation(rotation)
            .build()

        // ImageCapture
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            // We request aspect ratio but no resolution to match preview config, but letting
            // CameraX optimize for whatever specific resolution best fits our use cases
            .setTargetAspectRatio(screenAspectRatio)
            // Set initial target rotation, we will have to call this again if rotation changes
            // during the lifecycle of this use case
            .setTargetRotation(rotation)
            .build()

        videoCapture = VideoCapture.Builder().build()

        // Must unbind the use-cases before rebinding them
        cameraProvider.unbindAll()

        try {
            // A variable number of use-cases can be passed here -
            // camera provides access to CameraControl & CameraInfo
            camera = cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, imageCapture, videoCapture
            )

            // Attach the viewfinder's surface provider to preview use case
            preview?.setSurfaceProvider(viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }

        videoTouchListener()
    }

    private fun takePhoto() {

        if (isTakingVideo) return
        // Get a stable reference of the modifiable image capture use case
        imageCapture?.let { imageCapture ->

            // Create output file to hold the image
            val photoFile = File(
                outputDirectory,
                SimpleDateFormat(
                    FILENAME_FORMAT, Locale.ENGLISH
                ).format(System.currentTimeMillis()) + PHOTO_EXTENSION
            )

            // Setup image capture metadata
            val metadata = ImageCapture.Metadata().apply {

                // Mirror image when using the front camera
                isReversedHorizontal = lensFacing == CameraSelector.LENS_FACING_FRONT
            }

            // Create output options object which contains file + metadata
            val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile)
                .setMetadata(metadata)
                .build()

            // Setup image capture listener which is triggered after photo has been taken
            imageCapture.takePicture(
                outputOptions, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
                    override fun onError(exc: ImageCaptureException) {
                        Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                    }

                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        val savedUri = output.savedUri ?: Uri.fromFile(photoFile)
                        Log.d(TAG, "Photo capture succeeded: $savedUri")

                        // If the folder selected is an external media directory, this is
                        // unnecessary but otherwise other apps will not be able to access our
                        // images unless we scan them using [MediaScannerConnection]
                        val mimeType = MimeTypeMap.getSingleton()
                            .getMimeTypeFromExtension(savedUri.toFile().extension)
                        MediaScannerConnection.scanFile(
                            this@Picker,
                            arrayOf(savedUri.toFile().absolutePath),
                            arrayOf(mimeType)
                        ) { _, uri ->
                            Log.d(TAG, "Image capture scanned into media store: $uri")
                        }

                        val mPathList = ArrayList<String>()
                        mPathList.add(savedUri.toString())

                        val intent = Intent()
                        intent.putExtra(PICKED_MEDIA_LIST, mPathList)
                        setResult(Activity.RESULT_OK, intent)
                        finish()
                    }
                })

            // We can only change the foreground Drawable using API level 23+ API
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val container = findViewById<ConstraintLayout>(R.id.container)
                // Display flash animation to indicate that photo was captured
                container.postDelayed({
                    container.foreground = ColorDrawable(Color.WHITE)
                    container.postDelayed(
                        { container.foreground = null }, ANIMATION_FAST_MILLIS
                    )
                }, ANIMATION_SLOW_MILLIS)
            }
        }
    }

    private var isTakingVideo = false

    @SuppressLint("ClickableViewAccessibility", "RestrictedApi")
    private fun videoTouchListener() {
        val imageViewVideoRedBg = findViewById<ImageView>(R.id.image_view_video_red_bg)

        imageViewClick.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                imageViewVideoRedBg.visibility = GONE
                imageViewVideoRedBg.animate().scaleX(1f).scaleY(1f)
                    .setDuration(300).setInterpolator(AccelerateDecelerateInterpolator()).start()

                imageViewClick.animate().scaleX(1f).scaleY(1f).setDuration(300)
                    .setInterpolator(AccelerateDecelerateInterpolator()).start()
            } else if (event.action == MotionEvent.ACTION_DOWN) {
                imageViewVideoRedBg.visibility = VISIBLE
                imageViewVideoRedBg.animate().scaleX(1.2f).scaleY(1.2f)
                    .setDuration(300).setInterpolator(AccelerateDecelerateInterpolator()).start()
                imageViewClick.animate().scaleX(1.2f).scaleY(1.2f).setDuration(300)
                    .setInterpolator(AccelerateDecelerateInterpolator()).start()
            }
            if (event.action == MotionEvent.ACTION_UP && isTakingVideo) {
                //stop video
                videoCapture?.stopRecording()
            }

            false
        }

        imageViewClick.setOnLongClickListener {
            if (!mPickerOptions.excludeVideos) {
                try{
                    takeVideo(it)
                }catch (e : Exception) {
                    e.printStackTrace()
                }
            }
            false
        }
    }

    private var mVideoCounterProgress = 0
    private var mVideoCounterHandler: Handler? = Handler()
    private var mVideoCounterRunnable = Runnable {}

    @SuppressLint("RestrictedApi")
    private fun takeVideo(it: View) {

        val videoFile = File(
            outputDirectory,
            SimpleDateFormat(
                FILENAME_FORMAT, Locale.ENGLISH
            ).format(System.currentTimeMillis()) + VIDEO_EXTENSION
        )

        val mOutputFileOptions = VideoCapture.OutputFileOptions.Builder(videoFile).build()

        isTakingVideo = true
        it.performHapticFeedback(LONG_PRESS)

        findViewById<ConstraintLayout>(R.id.constraint_timer).visibility = VISIBLE

        val progressbarVideoCounter = findViewById<ProgressBar>(R.id.progressbar_video_counter)

        progressbarVideoCounter.progress = 0

        progressbarVideoCounter.max = mPickerOptions.maxVideoDuration
        progressbarVideoCounter.invalidate()

        val textViewVideoTimer = findViewById<TextView>(R.id.text_view_video_timer)

        mVideoCounterRunnable = Runnable {
            ++mVideoCounterProgress
            progressbarVideoCounter.progress = mVideoCounterProgress

            var min = 0

            var secondBuilder = "$mVideoCounterProgress"

            if (mVideoCounterProgress > 59) {
                min = mVideoCounterProgress / 60
                secondBuilder = "${mVideoCounterProgress - (60 * min)}"
            }

            if (secondBuilder.length == 1) secondBuilder = "0$mVideoCounterProgress"

            val time = "0$min:$secondBuilder"

            textViewVideoTimer.text = time

            if (mVideoCounterProgress == (mPickerOptions.maxVideoDuration)) {
                mVideoCounterHandler?.removeCallbacks(mVideoCounterRunnable)
                videoCapture?.stopRecording()
                mVideoCounterHandler = null
            }

            mVideoCounterHandler?.postDelayed(mVideoCounterRunnable, 1000)
        }

        mVideoCounterHandler?.postDelayed(mVideoCounterRunnable, 1000)

        imageViewClick.animate().scaleX(1.2f).scaleY(1.2f).setDuration(300)
            .setInterpolator(AccelerateDecelerateInterpolator()).start()

        imageViewFlash.visibility = GONE
        imageViewChangeCamera.visibility = GONE
        findViewById<TextView>(R.id.text_view_message_bottom).visibility = GONE

        //start video
        videoCapture?.startRecording(
            mOutputFileOptions,
            cameraExecutor,
            object : VideoCapture.OnVideoSavedCallback {
                override fun onVideoSaved(output: VideoCapture.OutputFileResults) {
                    val savedUri = output.savedUri ?: Uri.fromFile(videoFile)
                    if (mVideoCounterHandler != null)
                        mVideoCounterHandler?.removeCallbacks(mVideoCounterRunnable)

                    Log.d(TAG, "Video capture succeeded: $savedUri")

                    // If the folder selected is an external media directory, this is
                    // unnecessary but otherwise other apps will not be able to access our
                    // images unless we scan them using [MediaScannerConnection]
                    val mimeType = MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(savedUri.toFile().extension)
                    MediaScannerConnection.scanFile(
                        this@Picker,
                        arrayOf(savedUri.toFile().absolutePath),
                        arrayOf(mimeType)
                    ) { _, uri ->
                        Log.d(TAG, "Image capture scanned into media store: $uri")
                    }

                    val mPathList = ArrayList<String>()
                    mPathList.add(savedUri.toString())

                    val intent = Intent()
                    intent.putExtra(PICKED_MEDIA_LIST, mPathList)
                    setResult(Activity.RESULT_OK, intent)
                    finish()

                    isTakingVideo = false
                }

                override fun onError(
                    videoCaptureError: Int,
                    message: String,
                    cause: Throwable?
                ) {
                    //
                }

            })
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()

    }

    private val galleryImageList = ArrayList<MediaModel>()
    private var mInstantMediaAdapter: InstantMediaRecyclerAdapter? = null
    private var mBottomMediaAdapter: BottomSheetMediaRecyclerAdapter? = null

    private fun getMedia() {

        CoroutineScope(Dispatchers.Main).launch {
            val cursor: Cursor? = withContext(Dispatchers.IO) {
                getImageVideoCursor(this@Picker, mPickerOptions)
            }

            if (cursor != null) {

                Log.e(TAG, "getMedia: ${cursor.count}")

                val index = cursor.getColumnIndex(MediaStore.Files.FileColumns._ID)
                val dateIndex = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_ADDED)
                val typeIndex = cursor.getColumnIndex(MediaStore.Files.FileColumns.MEDIA_TYPE)

                var headerDate = ""

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(index)
                    val path = ContentUris.withAppendedId(IMAGE_VIDEO_URI, id)
                    val mediaType = cursor.getInt(typeIndex)
                    val longDate = cursor.getLong(dateIndex)
                    val mediaDate = getStringDate(this@Picker, longDate)

                    if (!headerDate.equals(mediaDate, true)) {
                        headerDate = mediaDate
                        galleryImageList.add(MediaModel(null, mediaType, headerDate))
                    }

                    galleryImageList.add(MediaModel(path, mediaType, ""))
                }


                val selectedList = ArrayList<MediaModel>()
                for (i in 0 until mPickerOptions.preSelectedMediaList.size) {
                    for (j in 0 until galleryImageList.size) {
                        if (Uri.parse(mPickerOptions.preSelectedMediaList[i]) == galleryImageList[j].mMediaUri) {
                            galleryImageList[j].isSelected = true
                            selectedList.add(galleryImageList[j])
                            break
                        }
                    }
                }

                mInstantMediaAdapter =
                    InstantMediaRecyclerAdapter(galleryImageList, mMediaClickListener, this@Picker)
                mInstantMediaAdapter?.maxCount = mPickerOptions.maxCount
                recyclerViewInstantMedia.adapter = mInstantMediaAdapter

                for (i in 0 until selectedList.size) {
                    mInstantMediaAdapter?.imageCount = mInstantMediaAdapter?.imageCount!! + 1
                    mMediaClickListener.onMediaLongClick(
                        selectedList[i],
                        InstantMediaRecyclerAdapter::class.java.simpleName
                    )
                }

                handleBottomSheet()
            }

        }
    }

    private val mMediaClickListener = object : MediaClickInterface {
        override fun onMediaClick(media: MediaModel) {
            pickImages()
        }

        override fun onMediaLongClick(media: MediaModel, intentFrom: String) {
            if (intentFrom == InstantMediaRecyclerAdapter::class.java.simpleName) {
                if (mInstantMediaAdapter?.imageCount!! > 0) {
                    textViewImageCount.text = mInstantMediaAdapter?.imageCount?.toString()
                    textViewTopSelect.text = String.format(
                        getString(R.string.images_selected),
                        mInstantMediaAdapter?.imageCount?.toString()
                    )
                    showTopViews()
                } else hideTopViews()
            }


            if (intentFrom == BottomSheetMediaRecyclerAdapter::class.java.simpleName) {
                if (mBottomMediaAdapter?.imageCount!! > 0) {
                    textViewImageCount.text = mBottomMediaAdapter?.imageCount?.toString()
                    textViewTopSelect.text = String.format(
                        getString(R.string.images_selected),
                        mBottomMediaAdapter?.imageCount?.toString()
                    )
                    showTopViews()
                } else hideTopViews()

            }
        }

    }

    private fun showTopViews() {
        constraintCheck.visibility = VISIBLE
        textViewOk.visibility = VISIBLE

        imageViewCheck.visibility = GONE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            constraintBottomSheetTop.setBackgroundColor(
                resources.getColor(
                    R.color.colorPrimary,
                    null
                )
            )
            DrawableCompat.setTint(
                imageViewBack.drawable,
                resources.getColor(R.color.colorWhite, null)
            )
        } else {
            constraintBottomSheetTop.setBackgroundColor(resources.getColor(R.color.colorPrimary))
            DrawableCompat.setTint(
                imageViewBack.drawable,
                resources.getColor(R.color.colorWhite)
            )
        }
    }

    private fun hideTopViews() {
        constraintCheck.visibility = GONE
        textViewOk.visibility = GONE
        imageViewCheck.visibility = VISIBLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            constraintBottomSheetTop.setBackgroundColor(
                resources.getColor(
                    R.color.colorWhite,
                    null
                )
            )
            DrawableCompat.setTint(
                imageViewBack.drawable,
                resources.getColor(R.color.colorBlack, null)
            )
        } else {
            constraintBottomSheetTop.setBackgroundColor(resources.getColor(R.color.colorWhite))
            DrawableCompat.setTint(
                imageViewBack.drawable,
                resources.getColor(R.color.colorBlack)
            )
        }
    }

    private var bottomSheetBehavior: BottomSheetBehavior<ConstraintLayout>? = null

    private fun handleBottomSheet() {

        mBottomMediaAdapter =
            BottomSheetMediaRecyclerAdapter(galleryImageList, mMediaClickListener, this@Picker)
        mBottomMediaAdapter?.maxCount = mPickerOptions.maxCount

        val layoutManager = GridLayoutManager(this, SPAN_COUNT)
        recyclerViewBottomSheetMedia.layoutManager = layoutManager

        layoutManager.spanSizeLookup = object : SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (mBottomMediaAdapter?.getItemViewType(position) == HEADER) {
                    SPAN_COUNT
                } else 1
            }
        }

        recyclerViewBottomSheetMedia.adapter = mBottomMediaAdapter
        recyclerViewBottomSheetMedia.addItemDecoration(
            HeaderItemDecoration(
                mBottomMediaAdapter!!,
                this
            )
        )

        bottomSheetBehavior = BottomSheetBehavior.from(findViewById(R.id.bottom_sheet))

        var notifiedUp = false
        var notifiedDown = false

        val imageViewArrowUp = findViewById<AppCompatImageView>(R.id.image_view_arrow_up)

        bottomSheetBehavior?.addBottomSheetCallback(object :
            BottomSheetBehavior.BottomSheetCallback() {
            var oldOffSet = 0f
            override fun onSlide(bottomSheet: View, slideOffset: Float) {

                val inRangeExpanding = oldOffSet < slideOffset
                val inRangeCollapsing = oldOffSet > slideOffset
                oldOffSet = slideOffset

                if (slideOffset == 1f) {
                    notifiedUp = false
                    notifiedDown = false
                    imageViewArrowUp.visibility = INVISIBLE
                } else if (slideOffset == 0f) {
                    notifiedUp = false
                    notifiedDown = false
                    imageViewArrowUp.visibility = VISIBLE
                }

                if (slideOffset > 0.6f && slideOffset < 0.8f) {
                    if (!notifiedUp && inRangeExpanding) {
                        Log.e(TAG, "onSlide 1: $slideOffset")
                        mBottomMediaAdapter?.notifyDataSetChanged()
                        notifiedUp = true

                        var count = 0
                        galleryImageList.map { mediaModel ->
                            if (mediaModel.isSelected) count++
                        }
                        mBottomMediaAdapter?.imageCount = count
                    }


                } else if (slideOffset > 0.1f && slideOffset < 0.3f) {
                    if (!notifiedDown && inRangeCollapsing) {
                        Log.e(TAG, "onSlide 2: $slideOffset")
                        mInstantMediaAdapter?.notifyDataSetChanged()
                        notifiedDown = true

                        var count = 0
                        galleryImageList.map { mediaModel ->
                            if (mediaModel.isSelected) count++
                        }
                        mInstantMediaAdapter?.imageCount = count
                        textViewImageCount.text = count.toString()
                    }

                }

                val imageCount =
                    if (mInstantMediaAdapter?.imageCount!! > 0) mInstantMediaAdapter?.imageCount!! else mBottomMediaAdapter?.imageCount!!

                manipulateBottomSheetVisibility(this@Picker, slideOffset, imageCount, recyclerViewInstantMedia, constraintCheck, constraintBottomSheetTop, recyclerViewBottomSheetMedia)

            }

            override fun onStateChanged(bottomSheet: View, newState: Int) {/*Not Required*/
            }

        })

        imageViewBack.setOnClickListener {
            bottomSheetBehavior?.setState(BottomSheetBehavior.STATE_COLLAPSED)
        }

        constraintCheck.setOnClickListener { pickImages() }
        textViewOk.setOnClickListener { pickImages() }
        imageViewCheck.setOnClickListener {
            constraintCheck.visibility = VISIBLE
            textViewOk.visibility = VISIBLE

            textViewTopSelect.text = resources.getString(R.string.tap_to_select)

            mBottomMediaAdapter?.mTapToSelect = true

            imageViewCheck.visibility = GONE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                constraintBottomSheetTop.setBackgroundColor(
                    resources.getColor(
                        R.color.colorPrimary,
                        null
                    )
                )
                DrawableCompat.setTint(
                    imageViewBack.drawable,
                    resources.getColor(R.color.colorWhite, null)
                )
            } else {
                constraintBottomSheetTop.setBackgroundColor(resources.getColor(R.color.colorPrimary))
                DrawableCompat.setTint(
                    imageViewBack.drawable,
                    resources.getColor(R.color.colorWhite)
                )
            }
        }

        if (statusBarSize > 0) {
            val params =
                constraintBottomSheetTop.layoutParams as ConstraintLayout.LayoutParams
            params.setMargins(0, statusBarSize, 0, 0)
        }
        hideStatusBar(this)
    }

    private fun pickImages() {
        val mPathList = ArrayList<String>()

        galleryImageList.map { mediaModel ->
            if (mediaModel.isSelected) {
                mPathList.add(mediaModel.mMediaUri.toString())
            }
        }

        val intent = Intent()
        intent.putExtra(PICKED_MEDIA_LIST, mPathList)
        setResult(Activity.RESULT_OK, intent)
        finish()
    }

    override fun onBackPressed() {
        when {
            bottomSheetBehavior?.state == BottomSheetBehavior.STATE_EXPANDED -> bottomSheetBehavior?.state = BottomSheetBehavior.STATE_COLLAPSED
            mInstantMediaAdapter?.imageCount != null && mInstantMediaAdapter?.imageCount!! > 0 -> removeSelection()
            else -> super.onBackPressed()
        }
    }

    private fun removeSelection(){
        mInstantMediaAdapter?.imageCount = 0
        mBottomMediaAdapter?.imageCount = 0
        for (i in 0 until galleryImageList.size) galleryImageList[i].isSelected = false
        mInstantMediaAdapter?.notifyDataSetChanged()
        mBottomMediaAdapter?.notifyDataSetChanged()
        constraintCheck.visibility = GONE
        textViewOk.visibility = GONE
        imageViewCheck.visibility = VISIBLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            constraintBottomSheetTop.setBackgroundColor(
                resources.getColor(
                    R.color.colorWhite,
                    null
                )
            )
            DrawableCompat.setTint(
                imageViewBack.drawable,
                resources.getColor(R.color.colorBlack, null)
            )
        } else {
            constraintBottomSheetTop.setBackgroundColor(resources.getColor(R.color.colorWhite))
            DrawableCompat.setTint(
                imageViewBack.drawable,
                resources.getColor(R.color.colorBlack)
            )
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun getOutputDirectory(): File {
        val mediaDir = externalMediaDirs.firstOrNull()?.let {
            File(it, resources.getString(R.string.app_name)).apply { mkdirs() }
        }
        return if (mediaDir != null && mediaDir.exists())
            mediaDir else filesDir
    }

    /**
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

    /** Returns true if the device has an available back camera. False otherwise */
    private fun hasBackCamera(): Boolean {
        return cameraProvider?.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ?: false
    }

    /** Returns true if the device has an available front camera. False otherwise */
    private fun hasFrontCamera(): Boolean {
        return cameraProvider?.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ?: false
    }

    companion object {
        private const val TAG = "Picker"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        const val REQUEST_CODE_PICKER = 10
        const val PICKER_OPTIONS = "PICKER_OPTIONS"
        const val PICKED_MEDIA_LIST = "PICKED_MEDIA_LIST"
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private const val PHOTO_EXTENSION = ".jpg"
        private const val VIDEO_EXTENSION = ".mp4"
        private const val RATIO_4_3_VALUE = 4.0 / 3.0
        private const val RATIO_16_9_VALUE = 16.0 / 9.0

        const val ANIMATION_FAST_MILLIS = 50L
        const val ANIMATION_SLOW_MILLIS = 100L

        @JvmStatic
        fun startPicker(fragment: Fragment, mPickerOptions: PickerOptions) {
            PermissionUtils.checkForCameraWritePermissions(fragment, object : PermissionCallback {
                override fun onPermission(approved: Boolean) {
                    val mPickerIntent = Intent(fragment.activity, Picker::class.java)
                    mPickerIntent.putExtra(PICKER_OPTIONS, mPickerOptions)
                    mPickerIntent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    fragment.startActivityForResult(mPickerIntent, REQUEST_CODE_PICKER)
                }
            })
        }

        @JvmStatic
        fun startPicker(activity: FragmentActivity, mPickerOptions: PickerOptions) {
            PermissionUtils.checkForCameraWritePermissions(activity, object : PermissionCallback {
                override fun onPermission(approved: Boolean) {
                    val mPickerIntent = Intent(activity, Picker::class.java)
                    mPickerIntent.putExtra(PICKER_OPTIONS, mPickerOptions)
                    mPickerIntent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    activity.startActivityForResult(mPickerIntent, REQUEST_CODE_PICKER)
                }
            })
        }
    }
}