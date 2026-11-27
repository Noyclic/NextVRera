package com.noyclic.literthtpprobe

import android.content.Context
import android.os.SystemClock
import com.qualcomm.qti.QnnDelegate
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

data class TensorInfo(
    val index: Int,
    val name: String,
    val shape: List<Int>,
    val shapeSignature: List<Int>,
    val type: String,
    val bytes: Int,
    val elementCount: Int,
    val quantizationScale: Float,
    val quantizationZeroPoint: Int
)
data class HtpReport(
    val model: String,
    val input: TensorInfo,
    val outputs: List<TensorInfo>,
    val warmupMs: Double,
    val medianMs: Double,
    val p95Ms: Double,
    val placement: String = "NOT_VERIFIED"
)

/** Strict HTP-only Interpreter probe. No NNAPI, XNNPACK, GPU, generic DSP or CPU fallback. */
class HtpModelProbe(context: Context, private val file: File) : Closeable {
    private val appContext = context.applicationContext
    private var interpreter: Interpreter? = null
    private var delegate: QnnDelegate? = null
    private var closed = false

    fun run(iterations: Int = 15): HtpReport {
        check(!closed) { "Probe is closed." }
        require(iterations in 1..100)
        check(file.isFile && file.length() > 0L) { "Model file is missing." }
        val bytes = file.readBytes()
        val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        model.put(bytes)
        model.rewind()
        val options = Interpreter.Options().apply {
            setUseNNAPI(false)
            setUseXNNPACK(false)
            setNumThreads(2)
        }
        val qnnOptions = QnnDelegate.Options().apply {
            setBackendType(QnnDelegate.Options.BackendType.HTP_BACKEND)
            setSkelLibraryDir(appContext.applicationInfo.nativeLibraryDir)
        }
        val createdDelegate = QnnDelegate(qnnOptions)
        delegate = createdDelegate
        try {
            options.addDelegate(createdDelegate)
            val active = Interpreter(model, options)
            interpreter = active
            check(active.inputTensorCount == 1) { "Expected one input, got ${active.inputTensorCount}." }
            check(active.outputTensorCount > 0) { "No output tensors found." }
            val inputTensor = active.getInputTensor(0)
            check(inputTensor.dataType() == DataType.FLOAT32) {
                "Input type ${inputTensor.dataType()} is not FLOAT32."
            }
            val inputInfo = inputTensor.info(0)
            val outputInfo = (0 until active.outputTensorCount).map { active.getOutputTensor(it).info(it) }
            val input = ByteBuffer.allocateDirect(inputTensor.numBytes()).order(ByteOrder.nativeOrder())
            val outputBuffers = LinkedHashMap<Int, ByteBuffer>()
            val outputs = LinkedHashMap<Int, Any>()
            outputInfo.forEach { info ->
                val buffer = ByteBuffer.allocateDirect(info.bytes).order(ByteOrder.nativeOrder())
                outputBuffers[info.index] = buffer
                outputs[info.index] = buffer
            }
            fun infer(): Long {
                input.clear()
                outputBuffers.values.forEach { it.clear() }
                val start = SystemClock.elapsedRealtimeNanos()
                active.runForMultipleInputsOutputs(arrayOf(input), outputs)
                return (SystemClock.elapsedRealtimeNanos() - start).coerceAtLeast(1L)
            }
            val warmup = infer() / NANOS_PER_MS
            val sorted = LongArray(iterations) { infer() }.sorted()
            return HtpReport(file.name, inputInfo, outputInfo, warmup, percentile(sorted, .50), percentile(sorted, .95))
        } catch (error: Throwable) {
            close()
            throw IllegalStateException(
                "QNN HTP failed for ${file.name}: ${error.javaClass.simpleName}: ${error.message}", error
            )
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { interpreter?.close() }
        interpreter = null
        runCatching { delegate?.javaClass?.getMethod("close")?.invoke(delegate) }
        delegate = null
    }

    private fun org.tensorflow.lite.Tensor.info(index: Int): TensorInfo {
        val quantization = quantizationParams()
        return TensorInfo(
            index = index,
            name = name(),
            shape = shape().toList(),
            shapeSignature = shapeSignature().toList(),
            type = dataType().name,
            bytes = numBytes(),
            elementCount = numElements(),
            quantizationScale = quantization.scale,
            quantizationZeroPoint = quantization.zeroPoint
        )
    }

    private fun percentile(sorted: List<Long>, fraction: Double): Double {
        val index = (ceil(fraction * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)
        return sorted[index].toDouble() / NANOS_PER_MS
    }

    companion object { private const val NANOS_PER_MS = 1_000_000.0 }
}
