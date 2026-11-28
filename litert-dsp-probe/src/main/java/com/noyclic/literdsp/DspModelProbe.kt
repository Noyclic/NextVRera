package com.noyclic.literdsp

import android.content.Context
import android.os.SystemClock
import com.qualcomm.qti.QnnDelegate
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

data class TensorSnapshot(val index: Int, val name: String, val shape: List<Int>, val type: String, val bytes: Int)
data class DspReport(
    val model: String, val input: TensorSnapshot, val outputs: List<TensorSnapshot>,
    val warmupMs: Double, val medianMs: Double, val p95Ms: Double,
    val delegate: String = "QNN DSP requested", val invocation: String = "PASS",
    val operatorPlacement: String = "NOT_VERIFIED"
)

/** Strict DSP-only probe. CPU/XNNPACK/NNAPI fallback is disabled; placement remains unverified. */
class DspModelProbe(context: Context, private val file: File) : Closeable {
    private val appContext = context.applicationContext
    private var interpreter: Interpreter? = null
    private var delegate: QnnDelegate? = null
    private var closed = false

    fun run(iterations: Int = 20): DspReport {
        check(!closed) { "Probe is closed." }
        require(iterations in 1..100)
        check(file.isFile && file.length() > 0L) { "Model file is missing." }
        val bytes = file.readBytes()
        val modelBuffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        modelBuffer.put(bytes); modelBuffer.rewind()
        val options = Interpreter.Options().apply {
            setUseNNAPI(false); setUseXNNPACK(false); setNumThreads(1)
        }
        val qnnOptions = QnnDelegate.Options().apply {
            setBackendType(QnnDelegate.Options.BackendType.DSP_BACKEND)
            setSkelLibraryDir(appContext.applicationInfo.nativeLibraryDir)
        }
        val activeDelegate = QnnDelegate(qnnOptions)
        delegate = activeDelegate
        try {
            options.addDelegate(activeDelegate)
            val active = Interpreter(modelBuffer, options)
            interpreter = active
            check(active.inputTensorCount == 1) { "Expected one input tensor, got ${active.inputTensorCount}." }
            check(active.outputTensorCount > 0) { "Model exposes no output tensors." }
            val inputTensor = active.getInputTensor(0)
            val inputInfo = inputTensor.snapshot(0)
            val outputInfo = (0 until active.outputTensorCount).map { active.getOutputTensor(it).snapshot(it) }
            val input = ByteBuffer.allocateDirect(inputTensor.numBytes()).order(ByteOrder.nativeOrder())
            val outputBuffers = LinkedHashMap<Int, ByteBuffer>()
            val outputs = LinkedHashMap<Int, Any>()
            outputInfo.forEach { info ->
                val buffer = ByteBuffer.allocateDirect(info.bytes).order(ByteOrder.nativeOrder())
                outputBuffers[info.index] = buffer; outputs[info.index] = buffer
            }
            fun invoke(): Long {
                input.clear(); outputBuffers.values.forEach { it.clear() }
                val start = SystemClock.elapsedRealtimeNanos()
                active.runForMultipleInputsOutputs(arrayOf(input), outputs)
                return (SystemClock.elapsedRealtimeNanos() - start).coerceAtLeast(1L)
            }
            val warmup = invoke().toDouble() / NANOS_PER_MS
            val samples = LongArray(iterations) { invoke() }.sorted()
            return DspReport(file.name, inputInfo, outputInfo, warmup,
                percentile(samples, .50), percentile(samples, .95))
        } catch (error: Throwable) {
            close()
            throw IllegalStateException("QNN DSP failed for ${file.name}: ${error.javaClass.simpleName}: ${error.message}", error)
        }
    }
    override fun close() {
        if (closed) return
        closed = true
        runCatching { interpreter?.close() }; interpreter = null
        runCatching { delegate?.javaClass?.getMethod("close")?.invoke(delegate) }; delegate = null
    }
    private fun org.tensorflow.lite.Tensor.snapshot(index: Int) =
        TensorSnapshot(index, name(), shape().toList(), dataType().name, numBytes())
    private fun percentile(sorted: List<Long>, fraction: Double): Double {
        val index = (ceil(fraction * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)
        return sorted[index].toDouble() / NANOS_PER_MS
    }
    companion object { private const val NANOS_PER_MS = 1_000_000.0 }
}
