package com.tans.socketprogramming

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.AudioRecord
import android.media.AudioTrack
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.disposables.Disposable
import kotlinx.coroutines.*
import java.net.*
import kotlin.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import java.io.*
import java.lang.Runnable
import java.nio.ByteBuffer

/**
 *
 * author: pengcheng.tan
 * date: 2020/5/19
 */

sealed class Result<T> {

    abstract fun isFailure(): Boolean
    abstract fun isSuccess(): Boolean
    abstract fun resultOrNull(): T?
    abstract fun errorOrNull(): Throwable?

    class Success<T>(private val result: T) : Result<T>() {
        override fun isFailure(): Boolean = false

        override fun isSuccess(): Boolean = true

        override fun resultOrNull(): T? = result

        override fun errorOrNull(): Throwable? = null

    }

    class Failure<T>(private val error: Throwable) : Result<T>() {
        override fun isFailure(): Boolean = true

        override fun isSuccess(): Boolean = false

        override fun resultOrNull(): T? = null

        override fun errorOrNull(): Throwable? = error
    }

    companion object {
        fun <T> success(data: T): Success<T> = Success(data)

        fun <T> failure(e: Throwable): Failure<T> = Failure(e)
    }
}

fun <T, R> Result<T>.map(f: Unit.(T) -> Result<R>): Result<R> = map(Unit, f)

inline fun <T, R, Receive> Result<T>.map(receive: Receive, f: Receive.(T) -> Result<R>): Result<R> {
    return if (isSuccess()) {
        receive.f(resultOrNull()!!)
    } else {
        Result.failure(errorOrNull()!!)
    }
}

inline fun <T, R> T.runCatching(block: T.() -> R): Result<R> = try {
    Result.success(block())
} catch (e: Throwable) {
    Result.failure(e)
}

suspend fun ServerSocket.acceptSuspend(workDispatcher: CoroutineDispatcher = Dispatchers.IO): Result<Socket> {
    return try {
        val socket = blockToSuspend(workDispatcher) { accept() }
        Result.success(socket)
    } catch (e: SocketException) {
        println("ServerSocket accept error: $e")
        Result.failure(e)
    }
}

suspend fun ServerSocket.bindSuspend(endPoint: InetSocketAddress, backlog: Int, workDispatcher: CoroutineDispatcher = Dispatchers.IO): Result<Unit> = try {
    blockToSuspend(workDispatcher) { bind(endPoint, backlog) }
    Result.success(Unit)
} catch (e: Throwable) {
    println("ServerSocket bind error: $e")
    Result.failure(e)
}

suspend fun Socket.connectSuspend(workDispatcher: CoroutineDispatcher = Dispatchers.IO,
                                  endPoint: InetSocketAddress,
                                  timeout: Int = CONNECT_TIMEOUT): Result<Unit> = try {
    blockToSuspend(workDispatcher) { connect(endPoint, timeout) }
    Result.success(Unit)
} catch (t: Throwable) {
    println("Socket connect error: $t")
    Result.failure(t)
}

suspend fun DatagramSocket.bindSuspend(endPoint: InetSocketAddress, workDispatcher: CoroutineDispatcher = Dispatchers.IO): Result<Unit> = try {
    blockToSuspend(workDispatcher) {
        bind(endPoint)
    }
    Result.success(Unit)
} catch (e: Throwable) {
    e.printStackTrace()
    Result.failure(e)
}

suspend fun DatagramSocket.receiveSuspend(packet: DatagramPacket, workDispatcher: CoroutineDispatcher = Dispatchers.IO): Result<Unit> = try {
    blockToSuspend(workDispatcher) {
        receive(packet)
    }
    Result.success(Unit)
} catch (e: Throwable) {
    e.printStackTrace()
    Result.failure(e)
}

suspend fun DatagramSocket.sendSuspend(packet: DatagramPacket, workDispatcher: CoroutineDispatcher = Dispatchers.IO): Result<Unit> = try {
    blockToSuspend(workDispatcher) {
        send(packet)
    }
    Result.success(Unit)
} catch (e: Throwable) {
    e.printStackTrace()
    Result.failure(e)
}

suspend fun <T> blockToSuspend(workDispatcher: CoroutineDispatcher = Dispatchers.IO,
                               block: () -> T): T = suspendCancellableCoroutine { cont ->
    val interceptor = cont.context[ContinuationInterceptor]
    if (interceptor is CoroutineDispatcher) {
        workDispatcher.dispatch(cont.context, Runnable {
            try {
                val result = block()
                interceptor.dispatch(cont.context, Runnable { cont.resume(result) })
            } catch (e: Throwable) {
                interceptor.dispatch(cont.context, Runnable { cont.resumeWithException(e) })
            }
        })
    } else {
        cont.resumeWithException(Throwable("Can't find ContinuaDispatcher"))
    }
}

@UseExperimental(ExperimentalUnsignedTypes::class)
fun Int.toIpAddr(isRevert: Boolean = true): IntArray = IntArray(4) { i ->
    if (isRevert) {
        (this shr 8 * i and 0xff).toUByte().toInt()
    } else {
        (this shr 8 * (3 - i) and 0xff).toUByte().toInt()
    }
}

fun IntArray.toInetByteArray(isRevert: Boolean = false): ByteArray = ByteArray(4) { i ->
    if (isRevert) {
        (this[3 - i] and 0xff).toByte()
    } else {
        (this[i] and 0xff).toByte()
    }
}

fun <T> Flow<T>.toObservable(coroutineScope: CoroutineScope,
                             context: CoroutineContext = EmptyCoroutineContext): Observable<T> {
    var job: Job? = null
    var dispose: Disposable? = null
    return Observable.create<T> { emitter ->
        job = coroutineScope.launch(context) {
            try {
                collect { emitter.onNext(it) }
            } finally {
                dispose?.dispose()
                job = null
            }
        }
    }.doOnDispose { job?.cancel(); dispose = null }
        .doOnSubscribe { dispose = it }
}

fun <T> CoroutineScope.asyncAsSingle(context: CoroutineContext = EmptyCoroutineContext,
                      block: suspend CoroutineScope.() -> T): Single<T> {
    var job: Job? = null
    var disposable: Disposable? = null
    return Single.create<T> { emitter ->
        job = this.launch(context) {
            try {
                val deferred = async(block = block)
                val result = deferred.await()
                if (result != null) {
                    emitter.onSuccess(result)
                }
            } finally {
                disposable?.dispose()
                job = null
            }
        }
    }.doOnDispose { job?.cancel(); disposable = null}
        .doOnSubscribe { disposable = it }
}

suspend fun <T> Single<T>.toSuspend(): T = suspendCancellableCoroutine { cont ->
    val d = this
        .subscribe({ t: T ->
            cont.resume(t)
        }, { e: Throwable ->
            cont.resumeWithException(e)
        })
    cont.invokeOnCancellation { if (!d.isDisposed) d.dispose() }
}


suspend fun Context.alertDialog(title: String,
                                msg: String,
                                cancelable: Boolean = true): Boolean = suspendCancellableCoroutine { cont ->
    val d = AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(msg)
        .setCancelable(cancelable)
        .setPositiveButton("OK") { dialog, _ ->
            dialog.cancel()
            cont.resume(true)
        }
        .setNegativeButton("NO") { dialog, _ ->
            dialog.cancel()
            cont.resume(false)
        }
        .setOnCancelListener {
            if (cont.isActive) {
                cont.resume(false)
            }
        }
        .create()
    d.show()
    cont.invokeOnCancellation { if (d.isShowing) d.cancel() }
}

fun Context.createLoadingDialog(): AlertDialog {
    val d = AlertDialog.Builder(this)
        .setCancelable(false)
        .setView(R.layout.loading_layout)
        .create()
    d.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    return d
}

fun ByteArray.toByteBuffer(): ByteBuffer = ByteBuffer.wrap(this)

fun ByteBuffer.toByteArray(): ByteArray {
    rewind()
    val result = ByteArray(limit())
    get(result)
    return result
}

fun Int.toByteArray(): ByteArray = ByteArray(4) { i ->
    (this shr ((3 - i) * 8) and 0xff).toByte()
}

fun ByteArray.toInt(): Int = ByteBuffer.wrap(this).int

suspend fun InputStream.readWithoutRemainSuspend(
    bytes: ByteArray,
    offset: Int = 0,
    len: Int = bytes.size,
    workDispatcher: CoroutineDispatcher = Dispatchers.IO
) = blockToSuspend(workDispatcher) {
    readWithoutRemain(bytes, offset, len)
}

fun InputStream.readWithoutRemain(bytes: ByteArray, offset: Int = 0, len: Int = bytes.size) {
    val readCount = read(bytes, offset, len)
    if (readCount < len) {
        val needRead = len - readCount
        readWithoutRemain(bytes, offset + readCount, needRead)
    } else {
        return
    }
}

suspend fun OutputStream.writeSuspend(
    bytes: ByteArray,
    offset: Int = 0,
    len: Int = bytes.size,
    workDispatcher: CoroutineDispatcher = Dispatchers.IO
) = blockToSuspend(workDispatcher) {
    write(bytes, offset, len)
}

suspend fun BufferedReader.readLineSuspend(workDispatcher: CoroutineDispatcher = Dispatchers.IO) =
    blockToSuspend(workDispatcher) { readLine() ?: "" }

suspend fun BufferedWriter.writeSuspend(s: String, workDispatcher: CoroutineDispatcher = Dispatchers.IO) =
    blockToSuspend(workDispatcher) { write(s); flush() }
