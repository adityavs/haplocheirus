package com.twitter.haplocheirus

import java.util.concurrent.{LinkedBlockingQueue, TimeoutException, TimeUnit, ConcurrentHashMap}
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable
import com.twitter.ostrich.Stats
import com.twitter.util.Time
import com.twitter.util.TimeConversions._
import com.twitter.gizzard.shards.{ShardInfo, ShardBlackHoleException}
import net.lag.logging.Logger
import org.jredis.ClientRuntimeException


class RedisPool(name: String, config: RedisPoolConfig) {
  case class ClientPool(available: LinkedBlockingQueue[PipelinedRedisClient], val count: AtomicInteger)

  val log = Logger(getClass.getName)

  val poolTimeout = config.poolTimeoutMsec.toInt.milliseconds
  val concurrentServerMap = new ConcurrentHashMap[String, ClientPool]
  val concurrentErrorMap = new ConcurrentHashMap[String, AtomicInteger]
  val concurrentDisabledMap = new ConcurrentHashMap[String, Time]
  val serverMap = scala.collection.jcl.Map(concurrentServerMap)

  Stats.makeGauge("redis-pool-" + name) {
    serverMap.values.foldLeft(0) { _ + _.available.size }
  }

  def makeClient(hostname: String) = {
    val timeout = config.timeoutMsec.milliseconds
    val keysTimeout = config.keysTimeoutMsec.milliseconds
    val expiration = config.expirationHours.hours
    new PipelinedRedisClient(hostname, config.pipeline, timeout, keysTimeout, expiration)
  }

  def get(hostname: String): PipelinedRedisClient = {
    var pool = concurrentServerMap.get(hostname);
    if(pool eq null) {
      val newPool = ClientPool(new LinkedBlockingQueue[PipelinedRedisClient](), new AtomicInteger())
      pool = concurrentServerMap.putIfAbsent(hostname, newPool);
      if(pool eq null) {
        pool = newPool
      }
    }
    while({
      val poolCount = pool.count.get()
      if(poolCount >= config.poolSize) {
        false
      }
      else if(pool.count.compareAndSet(poolCount, poolCount + 1)) {
        pool.available.offer(makeClient(hostname))
        false
      }
      else {
        true
      }
    }) {}
    val client = pool.available.poll(poolTimeout.inMilliseconds, TimeUnit.MILLISECONDS)
    if (client eq null) {
      throw new TimeoutException("Unable to get redis connection to " + hostname)
    }
    client
  }

  def throwAway(hostname: String, client: PipelinedRedisClient) {
    try {
      client.shutdown()
    } catch {
      case e: Throwable =>
        log.warning(e, "Error discarding dead redis client: %s", e)
    }
    serverMap.get(hostname).foreach { _.count.decrementAndGet() }
  }

  def giveBack(hostname: String, client: PipelinedRedisClient) {
    if (client.alive) {
      serverMap(hostname).available.offer(client)
    }
  }

  def countErrorAndThrow(hostname:String, e: Throwable) = {
    var count = concurrentErrorMap.get(hostname)
    if (count eq null) {
      val newCount = new AtomicInteger()
      count = concurrentErrorMap.putIfAbsent(hostname, newCount)
      if (count eq null) {
        count = newCount
      }
    }
    val c = count.incrementAndGet
    if (c > config.autoDisableErrorLimit) {
      // TODO: log
      concurrentDisabledMap.put(hostname, config.autoDisableDuration.fromNow)
      countNonError(hostname) // To remove from the error map
    }
    throw e
  }

  def countNonError(hostname: String) = {
    if (concurrentErrorMap.containsKey(hostname)) {
      try {
        concurrentErrorMap.remove(hostname)
      } catch {
        case e: NullPointerException => {}
      }
    }
  }

  def checkErrorCount(shardInfo: ShardInfo) = {
    val timeout = concurrentDisabledMap.get(shardInfo.hostname)
    if (!(timeout eq null)) {
      if (timeout < Time.now) {
        throw new ShardBlackHoleException(shardInfo.id)
      } else {
        try {
          concurrentDisabledMap.remove(shardInfo.hostname)
        } catch {
          case e: NullPointerException => {}
        }
      }
    }
  }

  def withClient[T](shardInfo: ShardInfo)(f: PipelinedRedisClient => T): T = {
    var client: PipelinedRedisClient = null
    val hostname = shardInfo.hostname
    checkErrorCount(shardInfo)
    try {
      client = Stats.timeMicros("redis-acquire-usec") { get(hostname) }
    } catch {
      case e =>
        countErrorAndThrow(hostname, e)
    }
    try {
      f(client)
    } catch {
      case e: ClientRuntimeException =>
        log.error(e, "Redis client error: %s", e)
        throwAway(hostname, client)
        countErrorAndThrow(hostname, e)
    } finally {
      Stats.timeMicros("redis-release-usec") { giveBack(hostname, client) }
      countNonError(hostname)
    }
  }

  def shutdown() {
      serverMap.foreach { case (hostname, pool) =>
        while (pool.available.size > 0) {
          try {
            pool.available.take().shutdown()
          } catch {
            case e: Throwable =>
              log.error(e, "Failed to shutdown client: %s", e)
          }
        }
      }
      serverMap.clear()
  }

  override def toString = {
    "<RedisPool: %s>".format(serverMap.map { case (hostname, pool) =>
      "%s=(%d available, %d total)".format(hostname, pool.available.size, pool.count.get())
    }.mkString(", "))
  }
}
