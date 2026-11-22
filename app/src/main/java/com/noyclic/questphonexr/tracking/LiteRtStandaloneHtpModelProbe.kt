package com.noyclic.questphonexr.tracking

import android.content.Context
import android.os.SystemClock
import com.qualcomm.qti.QnnDelegate
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

data class LiteRtTensorDescription(
    val index: Int,
    val name: String,
    val shape: List<Int>,
    val dataType: String,
    val byteCount: Int
)

data class LiteRtQnnModelProbeResult(
    val modelName: String,
    val backend: LiteRtExecutionBackend,
    val input: LiteRtTensorDescription,
    val outputs: List<LiteRtTensorDescription>,
    val warmupLatencyMs: Double,
    val medianInferenceLatencyMs: Double,
    val p95InferenceLatencyMs: Double,
    /**
     * Interpreter success proves that the delegate accepted and executed the graph, but
     * does not prove every operator was assigned to HTP. Keep this explicit until QNN
     * partition/graph diagnostics are collected from the device.
     */
    val operatorPlacement: String = "NOT_VERIFIED"
)

/**
 * Strict, model-by-model Qualcomm HTP probe for the independent LiteRT hand models.
 *
 * This deliberately does not publish tracking frames and does not silently fall back to
 * DSP, GPU, NNAPI, or XNNPACK. Run it on a worker thread. A successful warm-up is a real
 * QNN HTP inference attempt, not proof of complete HTP operator placement.
 */
class LiteRtStandaloneHtpModelProbe(
    context: Context,
    private val modelFile: File
) : Closeable {
    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private var interpreter: Interpreter? = null
    private var delegate: QnnDelegate? = null

    fun inspectAndWarmup(benchmarkIterations: Int = DEFAULT_BENCHMARK_ITERATIONS): LiteRtQnnModelProbeResult {
        check(!closed.get()) { "HTP model probe is closed." }
        require(benchmarkIterations in 1..100) { "Benchmark iterations must be between 1 and 100." }
        val installed = checkNotNull(LiteRtStandaloneModelStore.installedFiles(appContext)) {
            "The pinned standalone model pair is missing or failed SHA-256 verification."
        }
        val canonicalModel = modelFile.canonicalFile
        check(
            canonicalModel == installed.palmDetector.canonicalFile ||
                canonicalModel == installed.handLandmark.canonicalFile
        ) {
            "Only the two integrity-pinned standalone models may be probed."
        }

        val modelBytes = modelFile.readBytes()
        val modelBuffer = ByteBuffer.allocateDirect(modelBytes.size).order(ByteOrder.nativeOrder())
        modelBuffer.put(modelBytes)
        modelBuffer.rewind()

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
            val createdInterpreter = Interpreter(modelBuffer, options)
            interpreter = createdInterpreter

            check(createdInterpreter.inputTensorCount == 1) {
                "Expected one model input; got ${createdInterpreter.inputTensorCount}."
            }
            check(createdInterpreter.outputTensorCount > 0) {
                "The model exposes no output tensors."
            }

            val inputTensor = createdInterpreter.getInputTensor(0)
            check(inputTensor.dataType() == DataType.FLOAT32) {
                "Unsupported model input type ${inputTensor.dataType()}; expected FLOAT32."
            }
            val inputDescription = inputTensor.describe(0)
            val outputDescriptions = (0 until createdInterpreter.outputTensorCount).map { index ->
                createdInterpreter.getOutputTensor(index).describe(index)
            }

            val input = ByteBuffer.allocateDirect(inputTensor.numBytes())
                .order(ByteOrder.nativeOrder())
            val outputs = LinkedHashMap<Int, ByteBuffer>()
            outputDescriptions.forEach { description ->
                outputs[description.index] = ByteBuffer.allocateDirect(description.byteCount)
                    .order(ByteOrder.nativeOrder())
            }

            // A zero-input warm-up forces model compilation and one real HTP graph execution.
            input.clear()
            outputs.values.forEach(ByteBuffer::clear)
            val warmupStarted = SystemClock.elapsedRealtimeNanos()
            createdInterpreter.runForMultipleInputsOutputs(arrayOf(input), outputs)
            val warmupMs = elapsedMsSince(warmupStarted)

            val samples = LongArray(benchmarkIterations)
            repeat(benchmarkIterations) { index ->
                check(!closed.get()) { "HTP model probe was cancelled." }
                input.clear()
                outputs.values.forEach(ByteBuffer::clear)
                val started = SystemClock.elapsedRealtimeNanos()
                createdInterpreter.runForMultipleInputsOutputs(arrayOf(input), outputs)
                samples[index] = (SystemClock.elapsedRealtimeNanos() - started).coerceAtLeast(1L)
            }

            return LiteRtQnnModelProbeResult(
                modelName = modelFile.name,
                backend = LiteRtExecutionBackend.QUALCOMM_QNN_HTP,
                input = inputDescription,
                outputs = outputDescriptions,
                warmupLatencyMs = warmupMs,
                medianInferenceLatencyMs = percentileMs(samples, 0.50),
                p95InferenceLatencyMs = percentileMs(samples, 0.95)
            )
        } catch (error: Throwable) {
            close()
            throw IllegalStateException(
                "Strict QNN HTP probe failed for ${modelFile.name}: ${error.javaClass.simpleName}: ${error.message}",
                error
            )
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { interpreter?.close() }
        interpreter = null
        runCatching { delegate?.javaClass?.getMethod("close")?.invoke(delegate) }
        delegate = null
    }

    private fun org.tensorflow.lite.Tensor.describe(index: Int) =
        LiteRtTensorDescription(
            index = index,
            name = name(),
            shape = shape().toList(),
            dataType = dataType().name,
            byteCount = numBytes()
        )

    private fun elapsedMsSince(startedAtNanos: Long): Double =
        (SystemClock.elapsedRealtimeNanos() - startedAtNanos).coerceAtLeast(0L) / NANOS_PER_MILLISECOND

    private fun percentileMs(samplesNanos: LongArray, percentile: Double): Double {
        val sorted = samplesNanos.sorted()
        val index = (ceil(percentile * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)
        return sorted[index].toDouble() / NANOS_PER_MILLISECOND
    }

    companion object {
        const val DEFAULT_BENCHMARK_ITERATIONS = 15
        private const val NANOS_PER_MILLISECOND = 1_000_000.0
    }
}
