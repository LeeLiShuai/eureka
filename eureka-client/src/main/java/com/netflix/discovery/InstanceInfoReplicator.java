package com.netflix.discovery;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.util.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A task for updating and replicating the local instanceinfo to the remote server. Properties of this task are:
 * - configured with a single update thread to guarantee sequential update to the remote server
 * - update tasks can be scheduled on-demand via onDemandUpdate()
 * - task processing is rate limited by burstSize
 * - a new update task is always scheduled automatically after an earlier update task. However if an on-demand task
 *   is started, the scheduled automatic update task is discarded (and a new one will be scheduled after the new
 *   on-demand update).
 *
 *   @author dliu
 *   应用实例信息复制器
 */
class InstanceInfoReplicator implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(InstanceInfoReplicator.class);
    /**
     * 客户端
     */
    private final DiscoveryClient discoveryClient;
    /**
     * 实例信息
     */
    private final InstanceInfo instanceInfo;
    /**
     * 定时执行频率
     */
    private final int replicationIntervalSeconds;
    /**
     * 线程池
     */
    private final ScheduledExecutorService scheduler;
    /**
     * 定时执行的任务
     */
    private final AtomicReference<Future> scheduledPeriodicRef;
    /**
     * 是否开启调度
     */
    private final AtomicBoolean started;
    private final RateLimiter rateLimiter;
    private final int burstSize;
    private final int allowedRatePerMinute;

    InstanceInfoReplicator(DiscoveryClient discoveryClient, InstanceInfo instanceInfo, int replicationIntervalSeconds, int burstSize) {
        this.discoveryClient = discoveryClient;
        this.instanceInfo = instanceInfo;
        this.scheduler = Executors.newScheduledThreadPool(1,
                new ThreadFactoryBuilder()
                        .setNameFormat("DiscoveryClient-InstanceInfoReplicator-%d")
                        .setDaemon(true)
                        .build());

        this.scheduledPeriodicRef = new AtomicReference<Future>();

        this.started = new AtomicBoolean(false);
        this.rateLimiter = new RateLimiter(TimeUnit.MINUTES);
        this.replicationIntervalSeconds = replicationIntervalSeconds;
        this.burstSize = burstSize;

        this.allowedRatePerMinute = 60 * this.burstSize / this.replicationIntervalSeconds;
        logger.info("InstanceInfoReplicator onDemand update allowed rate per min is {}", allowedRatePerMinute);
    }

    /**
     * 开启调度
     * @param initialDelayMs
     */
    public void start(int initialDelayMs) {
        if (started.compareAndSet(false, true)) {
            instanceInfo.setIsDirty();  // for initial register
            Future next = scheduler.schedule(this, initialDelayMs, TimeUnit.SECONDS);
            scheduledPeriodicRef.set(next);
        }
    }

    public void stop() {
        shutdownAndAwaitTermination(scheduler);
        started.set(false);
    }

    private void shutdownAndAwaitTermination(ExecutorService pool) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(3, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            logger.warn("InstanceInfoReplicator stop interrupted");
        }
    }

    /**
     * 状态发生变化且不是首次启动
     * @return
     */
    public boolean onDemandUpdate() {
        if (rateLimiter.acquire(burstSize, allowedRatePerMinute)) {
            if (!scheduler.isShutdown()) {
                //提交任务
                scheduler.submit(new Runnable() {
                    @Override
                    public void run() {
                        logger.debug("Executing on-demand update of local InstanceInfo");
                        //获取当前执行的任务，取消
                        Future latestPeriodic = scheduledPeriodicRef.get();
                        if (latestPeriodic != null && !latestPeriodic.isDone()) {
                            logger.debug("Canceling the latest scheduled update, it will be rescheduled at the end of on demand update");
                            latestPeriodic.cancel(false);
                        }
                        //执行run方法
                        InstanceInfoReplicator.this.run();
                    }
                });
                return true;
            } else {
                logger.warn("Ignoring onDemand update due to stopped scheduler");
                return false;
            }
        } else {
            logger.warn("Ignoring onDemand update due to rate limiter");
            return false;
        }
    }

    /**
     * 线程执行的方法
     */
    public void run() {
        try {
            //调用客户端的刷新应用实例方法
            discoveryClient.refreshInstanceInfo();
            //判断应用信息实例数据是否一致，一般来说应该是不一致的
            Long dirtyTimestamp = instanceInfo.isDirtyWithTime();
            if (dirtyTimestamp != null) {
                //注册
                discoveryClient.register();
                instanceInfo.unsetIsDirty(dirtyTimestamp);
            }
        } catch (Throwable t) {
            logger.warn("There was a problem with the instance info replicator", t);
        } finally {
            //提交任务
            Future next = scheduler.schedule(this, replicationIntervalSeconds, TimeUnit.SECONDS);
            scheduledPeriodicRef.set(next);
        }
    }

}
