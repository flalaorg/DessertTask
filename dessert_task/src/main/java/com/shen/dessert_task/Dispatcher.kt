package com.shen.dessert_task

import android.content.Context
import android.os.Looper
import androidx.annotation.UiThread
import com.shen.dessert_task.annotation_tools.AnnotationConvertTools
import com.shen.dessert_task.ext.isMainProcess
import com.shen.dessert_task.sort.getSortResult
import com.shen.dessert_task.state.markTaskDone
import com.shen.dessert_task.utils.DebugLog
import java.lang.ref.WeakReference
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * created by shen on 2019/10/24
 * at 22:30
 **/
class DessertDispatcher {

    private var startTime = 0L

    private val futures: MutableList<Future<*>> by lazy { mutableListOf<Future<*>>() }

    private var allTasks = mutableListOf<DessertTask>()

    private val dependOnTasks by lazy { mutableListOf<Class<out DessertTask>>() }

    @Volatile
    private var mainThreadTask: MutableList<DessertTask> = mutableListOf()

    private var countDownLatch: CountDownLatch? = null

    ///需要的等待的任务数
    private val needWaitCount by lazy { AtomicInteger() }

    private val needWaitTasks by lazy { mutableListOf<DessertTask>() }

    ///已经结束的 Task
    @Volatile
    private var finishTasks: ArrayList<Class<out DessertTask>> = ArrayList(100)

    private val dependedHasMap by lazy { hashMapOf<Class<out DessertTask>?, ArrayList<DessertTask>>() }

    private var interfaceCreate: Boolean = false

    /**
     * 启动器分析的次数，统计下分析的耗时；
     */
    private val analyseCount = AtomicInteger()

    fun addTask(task: DessertTask?): DessertDispatcher {
        task?.let {
            it.collectDepends()
            allTasks.add(it)
            dependOnTasks.add(task.javaClass)

            it.ifNeedWait {
                needWaitTasks.add(this)
                needWaitCount.getAndIncrement()
            }
        }

        return this
    }

    fun <T> create(
        interfaceObj : Class<T>,
        interfaceObjImpl: T
    ) : DessertDispatcher {
        AnnotationConvertTools.instance
            .dispatcher(this)
            .create(interfaceObj, interfaceObjImpl).also {
                this.interfaceCreate = true
            }
        return this
    }


    @UiThread
    fun start() {
        if (interfaceCreate) {
            AnnotationConvertTools.instance.autoAdd()
        }

        startTime = System.currentTimeMillis()
        require(Looper.getMainLooper() == Looper.myLooper()) { "must be called from UiThread" }

        if (allTasks.isNotEmpty()) {
            analyseCount.getAndIncrement()
            printDependedMsg()
            allTasks = getSortResult(allTasks, dependOnTasks) as MutableList<DessertTask>
            countDownLatch = CountDownLatch(needWaitCount.get())

            sendAndExecuteAsyncTask()
            DebugLog.logD("task analyse duration", "${System.currentTimeMillis() - startTime} begin main ")
            executeTaskMain()
        }
        DebugLog.logD("task analyse duration startTime cost ", System.currentTimeMillis() - startTime)
    }

    fun cancel() {
        futures.forEach {
            it.cancel(true)
        }
    }

    fun await() {
        try {
            if (DebugLog.isDebug) {
                DebugLog.logD("still has", needWaitCount.get())
                needWaitTasks.forEach {
                    DebugLog.logD("needWait", it.javaClass.simpleName)
                }
            }

            if (needWaitCount.get() > 0) {
                require(countDownLatch != null) { "You have to call start() before call await()" }
                countDownLatch?.await(WAITING_TIME.toLong(), TimeUnit.MILLISECONDS)
            }
        } catch (e: Throwable) {
            //ignore
        }
    }

    /**
     * 通知Children一个前置任务已完成
     */
    fun DessertTask.satisfyChildren() {
        val arrayDepended = dependedHasMap[javaClass]
        arrayDepended?.forEach {
            it.satisfy()
        }
    }

    fun DessertTask.executeTask() {
        ifNeedWait {
            needWaitCount.getAndIncrement()
        }

        runOn.execute(DessertDispatchRunnable(this, this@DessertDispatcher))
    }

    fun DessertTask.makeTaskDone() {
        ifNeedWait {
            finishTasks.add(javaClass)
            needWaitTasks.remove(this)
            countDownLatch?.countDown()
            needWaitCount.getAndDecrement()
        }
    }

    private fun executeTaskMain() {
        startTime = System.currentTimeMillis()
        mainThreadTask.forEach {
            val time = System.currentTimeMillis()
            DessertDispatchRunnable(it, this).run()
            DebugLog.logD(it.javaClass.simpleName, "real main " + (System.currentTimeMillis() - time))
        }

        DebugLog.logD("main task duration", System.currentTimeMillis() - startTime)
    }

    private fun sendAndExecuteAsyncTask() {
        allTasks.forEach {
            if (it.onlyInMainProcess and !isMainProcess) {
                it.makeTaskDone()
            } else{
                it.sendTaskReal()
            }

            it.isSend = true
        }
    }

    companion object {
        const val WAITING_TIME = 10000

        @JvmStatic
        private lateinit var contextWeakRef: WeakReference<Context>

        @JvmStatic
        private var isMainProcess = false

        @JvmStatic
        @Volatile
        private var hasInit: Boolean = false

        @JvmStatic
        fun init(context: Context?) {
            context?.let {
                contextWeakRef = WeakReference(it)
                hasInit = true
                isMainProcess = context.isMainProcess()
            }
        }

        @JvmStatic
        fun getContext() = contextWeakRef.get()

        @JvmStatic
        fun isMainProcess() = isMainProcess

        @JvmStatic
        private val instanceReal by lazy {
            DessertDispatcher()
        }

        @JvmStatic
        fun getInstance(): DessertDispatcher {
            require(hasInit) { throw RuntimeException("must call TaskDispatcher.init first") }
            return instanceReal
        }
    }

    private fun DessertTask.sendTaskReal() {
        if (runOnMainThread) {
            mainThreadTask.add(this)
            if (needCall) {
                callback = {
                    markTaskDone()
                    isFinish = true
                    makeTaskDone()
                    DebugLog.logD(javaClass.simpleName, "finish")
                }
            }
        } else {
            val future = runOn.submit(DessertDispatchRunnable(this, this@DessertDispatcher))
            futures.add(future)
        }
    }

    private fun DessertTask.collectDepends() {
        if (!dependOn.isNullOrEmpty()) {
            dependOn.forEach {
                if (dependedHasMap[it] == null) {
                    dependedHasMap[it] = arrayListOf()
                }

                dependedHasMap[it]?.add(this)
                if (finishTasks.contains(it)) satisfy()
            }
        }
    }

    private fun DessertTask.ifNeedWait(action: DessertTask.() -> Unit) {
        if (!runOnMainThread and needWait) {
            action.invoke(this)
        }
    }

    private fun printDependedMsg() {
        DebugLog.logD(javaClass.simpleName, needWaitCount.get())
        if (DebugLog.isDebug) {
            for ((key, value) in dependedHasMap) {
                DebugLog.logD(key?.simpleName, "size -> ${value.size}")
                value.forEach {
                    DebugLog.logD("dessert task", it.javaClass.simpleName)
                }
            }
        }
    }
}
