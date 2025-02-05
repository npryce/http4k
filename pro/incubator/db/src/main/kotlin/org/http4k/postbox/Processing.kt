package org.http4k.postbox

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Result
import dev.forkhandles.result4k.flatMap
import dev.forkhandles.result4k.get
import dev.forkhandles.result4k.mapFailure
import dev.forkhandles.result4k.peek
import dev.forkhandles.result4k.peekFailure
import dev.forkhandles.time.systemTime
import org.http4k.core.HttpHandler
import org.http4k.core.Response
import org.http4k.db.performAsResult
import org.http4k.events.Event
import org.http4k.events.Events
import org.http4k.postbox.ProcessingEvent.BatchProcessingFailed
import org.http4k.postbox.ProcessingEvent.BatchProcessingSucceeded
import org.http4k.postbox.ProcessingEvent.PollWait
import org.http4k.postbox.ProcessingEvent.RequestProcessingFailed
import org.http4k.postbox.ProcessingEvent.RequestProcessingSucceeded
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors


/**
 * PostboxProcessing is a background process that polls the Postbox for pending requests and processes them.
 */
class PostboxProcessing(
    private val transactor: PostboxTransactor,
    private val target: HttpHandler,
    private val batchSize: Int = 10,
    private val maxPollingTime: Duration = Duration.ofSeconds(5),
    private val events: Events = { },
    private val context: ExecutionContext = DefaultExecutionContext,
    private val successCriteria: (Response) -> Boolean = { it.status.successful }
)  {
    private val task = Runnable {
        while (context.isRunning()) {
            val t0 = context.currentTime()
            val result = processPendingRequests(successCriteria)
            val elapsedTime = Duration.between(t0, context.currentTime())

            result
                .peek { events(BatchProcessingSucceeded(it, elapsedTime)) }
                .peekFailure { events(BatchProcessingFailed(it.reason)) }

            val remainingTime = maxPollingTime - elapsedTime
            if (remainingTime > Duration.ZERO) {
                events(PollWait(remainingTime))
                context.pause(remainingTime)
            }
        }
    }

    fun stop() {
        context.stop()
    }

    fun start() {
        context.start(task)
    }

    fun processPendingRequests(successCriteria: (Response) -> Boolean): Result<Int, RequestProcessingError> =
        transactor.performAsResult { postbox ->
            // TODO: implement max number of retries?
            // TODO: mark requests as "processing" to allow for multiple instances of this function to run concurrently?
            val pendingRequests = postbox.pendingRequests(batchSize, systemTime())
            for (pending in pendingRequests) {
                processPendingRequest(postbox, pending, successCriteria)
                    .peek { events(RequestProcessingSucceeded(pending.requestId)) }
                    .peekFailure { events(RequestProcessingFailed(it.reason)) }
            }
            pendingRequests.size
        }.mapFailure { RequestProcessingError(it.message.orEmpty()) }

    private fun processPendingRequest(
        postbox: Postbox, pending: Postbox.PendingRequest,
        successCriteria: (Response) -> Boolean
    ): Result<Unit, RequestProcessingError> = target(pending.request).let { response ->
        if (successCriteria(response)) {
            postbox.markProcessed(pending.requestId, response)
                .mapFailure { RequestProcessingError(it.description) }
        } else {
            postbox.markDead(pending.requestId, response)
                .mapFailure { RequestProcessingError(it.description) }
                .flatMap { Failure(RequestProcessingError("response did not pass success criteria")) }
                .get().let(::Failure)
        }
    }
}

data class RequestProcessingError(val reason: String)

sealed class ProcessingEvent : Event {
    data class BatchProcessingSucceeded(val batchSize: Int, val duration: Duration) : ProcessingEvent()
    data class BatchProcessingFailed(val reason: String) : ProcessingEvent()
    data class RequestProcessingSucceeded(val requestId: RequestId) : ProcessingEvent()
    data class RequestProcessingFailed(val reason: String) : ProcessingEvent()
    data class PollWait(val duration: Duration) : ProcessingEvent()
}

interface ExecutionContext {
    fun stop()
    fun isRunning(): Boolean
    fun pause(duration: Duration)
    fun start(runnable: Runnable)
    fun currentTime(): Instant
}

object DefaultExecutionContext : ExecutionContext {
    private var running = true
    private var executor = Executors.newVirtualThreadPerTaskExecutor()

    override fun stop() {
        running = false
        executor.shutdown()
    }

    override fun isRunning(): Boolean = running

    override fun start(runnable: Runnable) {
        executor.execute(runnable)
    }

    override fun currentTime(): Instant = Instant.now()

    override fun pause(duration: Duration) {
        Thread.sleep(duration)
    }
}
