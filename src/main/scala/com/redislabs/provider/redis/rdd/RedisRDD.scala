package com.redislabs.provider.redis.rdd

import redis.clients.jedis._
import redis.clients.util.JedisClusterCRC16

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._

import java.util
import java.util.concurrent.ConcurrentHashMap

import com.redislabs.provider.redis.{RedisNode, RedisConfig}
import com.redislabs.provider.redis.partitioner._

import org.apache.spark.rdd.RDD
import org.apache.spark._

import scala.reflect.ClassTag


class RedisKVRDD(prev: RDD[String],
                 val rddType: String)
    extends RDD[(String, String)](prev) with Keys {

  override def getPartitions: Array[Partition] = prev.partitions

  override def compute(split: Partition, context: TaskContext): Iterator[(String, String)] = {
    val partition: RedisPartition = split.asInstanceOf[RedisPartition]
    val sPos = partition.slots._1
    val ePos = partition.slots._2
    val nodes = partition.redisConfig.getNodesBySlots(sPos, ePos)
    val keys = firstParent[String].iterator(split, context)
    val auth = partition.redisConfig.getAuth
    val db = partition.redisConfig.getDB
    rddType match {
      case "kv"   => getKV(nodes, keys)
      case "hash" => getHASH(nodes, keys)
    }
  }

  def getKV(nodes: Array[RedisNode], keys: Iterator[String]): Iterator[(String, String)] = {
    groupKeysByNode(nodes, keys).flatMap {
      x =>
        {
          val conn = x._1.endpoint.connect()
          val stringKeys = filterKeysByType(conn, x._2, "string")
          val pipeline = conn.pipelined
          stringKeys.foreach(pipeline.get)
          val res = stringKeys.zip(pipeline.syncAndReturnAll).iterator.
            asInstanceOf[Iterator[(String, String)]]
          conn.close
          res
        }
    }.iterator
  }
  def getHASH(nodes: Array[RedisNode], keys: Iterator[String]): Iterator[(String, String)] = {
    groupKeysByNode(nodes, keys).flatMap {
      x =>
        {
          val conn = x._1.endpoint.connect()
          val hashKeys = filterKeysByType(conn, x._2, "hash")
          val res = hashKeys.flatMap(conn.hgetAll).iterator
          conn.close
          res
        }
    }.iterator
  }
}

class RedisListRDD(prev: RDD[String], val rddType: String) extends RDD[String](prev) with Keys {

  override def getPartitions: Array[Partition] = prev.partitions

  override def compute(split: Partition, context: TaskContext): Iterator[String] = {
    val partition: RedisPartition = split.asInstanceOf[RedisPartition]
    val sPos = partition.slots._1
    val ePos = partition.slots._2
    val nodes = partition.redisConfig.getNodesBySlots(sPos, ePos)
    val keys = firstParent[String].iterator(split, context)
    rddType match {
      case "set"  => getSET(nodes, keys)
      case "list" => getLIST(nodes, keys)
    }
  }

  def getSET(nodes: Array[RedisNode], keys: Iterator[String]): Iterator[String] = {
    groupKeysByNode(nodes, keys).flatMap {
      x =>
        {
          val conn = x._1.endpoint.connect()
          val setKeys = filterKeysByType(conn, x._2, "set")
          val res = setKeys.flatMap(conn.smembers).iterator
          conn.close
          res
        }
    }.iterator
  }
  def getLIST(nodes: Array[RedisNode], keys: Iterator[String]): Iterator[String] = {
    groupKeysByNode(nodes, keys).flatMap {
      x =>
        {
          val conn = x._1.endpoint.connect()
          val listKeys = filterKeysByType(conn, x._2, "list")
          val res = listKeys.flatMap(conn.lrange(_, 0, -1)).iterator
          conn.close
          res
        }
    }.iterator
  }
}

class RedisZSetRDD[K](prev: RDD[String],
                      zsetConf: RedisZSetConf,
                      rddType: K)
                     (implicit val kClassTag: ClassTag[K])
  extends RDD[K](prev) with Keys {

  override def getPartitions: Array[Partition] = prev.partitions

  override def compute(split: Partition, context: TaskContext): Iterator[K] = {
    val partition: RedisPartition = split.asInstanceOf[RedisPartition]
    val sPos = partition.slots._1
    val ePos = partition.slots._2
    val nodes = partition.redisConfig.getNodesBySlots(sPos, ePos)
    val keys = firstParent[String].iterator(split, context)
    val auth = partition.redisConfig.getAuth
    val db = partition.redisConfig.getDB
    zsetConf.getType match {
      case "byRange" => getZSetByRange(nodes, keys, zsetConf.getStartPos, zsetConf.getEndPos).
        asInstanceOf[Iterator[K]]
      case "byScore" => getZSetByScore(nodes, keys, zsetConf.getMinScore, zsetConf.getMaxScore).
        asInstanceOf[Iterator[K]]
    }
  }

  private def getZSetByRange(nodes: Array[RedisNode],
                     keys: Iterator[String],
                     startPos: Long,
                     endPos: Long) = {
    groupKeysByNode(nodes, keys).flatMap {
      x =>
      {
        val conn = x._1.endpoint.connect()
        val zsetKeys = filterKeysByType(conn, x._2, "zset")
        val res = {
          if (zsetConf.getWithScore) {
            zsetKeys.flatMap(k => conn.zrangeWithScores(k, startPos, endPos)).
              map(tup => (tup.getElement, tup.getScore)).iterator
          }
          else {
            zsetKeys.flatMap(k => conn.zrange(k, startPos, endPos)).iterator
          }
        }
        conn.close
        res
      }
    }.iterator
  }

  private def getZSetByScore(nodes: Array[RedisNode],
                     keys: Iterator[String],
                     startScore: Double,
                     endScore: Double) = {
    groupKeysByNode(nodes, keys).flatMap {
      x =>
      {
        val conn = x._1.endpoint.connect()
        val zsetKeys = filterKeysByType(conn, x._2, "zset")
        val res = {
          if (zsetConf.getWithScore) {
            zsetKeys.flatMap(k => conn.zrangeByScoreWithScores(k, startScore, endScore)).
              map(tup => (tup.getElement, tup.getScore)).iterator
          } else {
            zsetKeys.flatMap(k => conn.zrangeByScore(k, startScore, endScore)).iterator
          }
        }
        conn.close
        res
      }
    }.iterator
  }
}

class RedisKeysRDD(sc: SparkContext,
                   val redisConfig: RedisConfig,
                   val keyPattern: String = "*",
                   val partitionNum: Int = 3,
                   val keys: Array[String] = null)
    extends RDD[String](sc, Seq.empty) with Logging with Keys {

  override protected def getPreferredLocations(split: Partition): Seq[String] = {
    Seq(split.asInstanceOf[RedisPartition].redisConfig.initialAddr)
  }

  /**
    * hosts(ip:String, port:Int, startSlot:Int, endSlot:Int) are generated by the redis-cluster's
    * hash tragedy and partitionNum to divide the cluster to partitionNum
    *
    * @return hosts
    */
  private def scaleHostsWithPartitionNum(): Seq[(String, Int, Int, Int)] = {
    def split(host: RedisNode, cnt: Int) = {
      val endpoint = host.endpoint
      val start = host.startSlot
      val end = host.endSlot
      val range = (end - start) / cnt
      (0 until cnt).map(i => {
        (endpoint.host,
          endpoint.port,
          if (i == 0) start else (start + range * i + 1),
          if (i != cnt - 1) (start + range * (i + 1)) else end)
      })
    }

    val hosts = redisConfig.hosts.sortBy(_.startSlot)

    if (hosts.size == partitionNum) {
      hosts.map(x => (x.endpoint.host, x.endpoint.port, x.startSlot, x.endSlot))
    } else if (hosts.size < partitionNum) {
      val presExtCnt = partitionNum / hosts.size
      val lastExtCnt = if (presExtCnt * hosts.size < partitionNum) (presExtCnt + 1) else presExtCnt
      hosts.zipWithIndex.flatMap{
        case(host, idx) => {
          split(host, if (idx == hosts.size - 1) lastExtCnt else presExtCnt)
        }
      }
    } else {
      val presExtCnt = hosts.size / partitionNum
      (0 until partitionNum).map{
        idx => {
          val ip = hosts(idx * presExtCnt).endpoint.host
          val port = hosts(idx * presExtCnt).endpoint.port
          val start = hosts(idx * presExtCnt).startSlot
          val end = hosts(if (idx == partitionNum - 1) {
                            (hosts.size-1)
                          } else {
                            ((idx + 1) * presExtCnt - 1)
                          }).endSlot
          (ip, port, start, end)
        }
      }
    }
  }

  override protected def getPartitions: Array[Partition] = {
    val hosts = scaleHostsWithPartitionNum()
    (0 until partitionNum).map(i => {
      new RedisPartition(i,
        redisConfig,
        (hosts(i)._3, hosts(i)._4)).asInstanceOf[Partition]
    }).toArray
  }

  override def compute(split: Partition, context: TaskContext): Iterator[String] = {
    val partition: RedisPartition = split.asInstanceOf[RedisPartition]
    val sPos = partition.slots._1
    val ePos = partition.slots._2
    val nodes = partition.redisConfig.getNodesBySlots(sPos, ePos)

    if (Option(this.keys).isDefined) {
      this.keys.iterator
    } else {
      getKeys(nodes, sPos, ePos, keyPattern).iterator
    }

  }
  def getSet(): RDD[String] = {
    new RedisListRDD(this, "set")
  }
  def getList(): RDD[String] = {
    new RedisListRDD(this, "list")
  }
  def getKV(): RDD[(String, String)] = {
    new RedisKVRDD(this, "kv")
  }
  def getHash(): RDD[(String, String)] = {
    new RedisKVRDD(this, "hash")
  }
  def getZSet(): RDD[(String, Double)] = {
    val zsetConf: RedisZSetConf = new RedisZSetConf().
      set("withScore", "true").
      set("type", "byRange").
      set("startPos", "0").
      set("endPos", "-1")
    new RedisZSetRDD(this, zsetConf, ("String", 0.1))
  }
  def getZSetByRange(startPos: Long, endPos: Long, withScore: Boolean) = {
    val zsetConf: RedisZSetConf = new RedisZSetConf().
      set("withScore", withScore.toString).
      set("type", "byRange").
      set("startPos", startPos.toString).
      set("endPos", endPos.toString)
//    new RedisZSetRDD(this, zsetConf, ("String", 0.1))
    new RedisZSetRDD(this, zsetConf, if (withScore) ("String", 0.1) else "String")
  }
  def getZSetByScore(min: Double, max: Double, withScore: Boolean) = {
    val zsetConf: RedisZSetConf = new RedisZSetConf().
      set("withScore", withScore.toString).
      set("type", "byScore").
      set("minScore", min.toString).
      set("maxScore", max.toString)
//    new RedisZSetRDD(this, zsetConf, ("String", 0.1))
    new RedisZSetRDD(this, zsetConf, if (withScore) ("String", 0.1) else "String")
  }
}


class RedisZSetConf() extends Serializable {

  private val settings = new ConcurrentHashMap[String, String]()

  def set(key: String, value: String): RedisZSetConf = {
    if (key == null) {
      throw new NullPointerException("null key")
    }
    if (value == null) {
      throw new NullPointerException("null value for " + key)
    }
    settings.put(key, value)
    this
  }

  def remove(key: String): RedisZSetConf = {
    settings.remove(key)
    this
  }

  def contains(key: String): Boolean = {
    settings.containsKey(key)
  }

  def get(key: String): String = {
    Option(settings.get(key)).getOrElse(throw new NoSuchElementException(key))
  }

  def get(key: String, defaultValue: String): String = {
    Option(settings.get(key)).getOrElse(defaultValue)
  }

  def getInt(key: String): Int = {
    get(key).toInt
  }

  def getInt(key: String, defaultValue: Int): Int = {
    get(key, defaultValue.toString).toInt
  }

  def getLong(key: String): Long = {
    get(key).toLong
  }

  def getLong(key: String, defaultValue: Long): Long = {
    get(key, defaultValue.toString).toLong
  }

  def getDouble(key: String): Double = {
    get(key).toDouble
  }

  def getDouble(key: String, defaultValue: Double): Double = {
    get(key, defaultValue.toString).toDouble
  }

  def getBoolean(key: String): Boolean = {
    get(key).toBoolean
  }

  def getBoolean(key: String, defaultValue: Boolean): Boolean = {
    get(key, defaultValue.toString).toBoolean
  }

  def getAll: Array[(String, String)] = {
    settings.entrySet().asScala.map(x => (x.getKey, x.getValue)).toArray
  }

  def getType: String = get("type")

  def getWithScore = getBoolean("withScore")

  def getStartPos: Long = getLong("startPos", 0)

  def getEndPos: Long = getLong("endPos", -1)

  def getMinScore: Double = getDouble("minScore")

  def getMaxScore: Double = getDouble("maxScore")
}



trait Keys {
  /**
    * @param key
    * @return true if the key is a RedisRegex
    */
  private def isRedisRegex(key: String) = {
    def judge(key: String, escape: Boolean): Boolean = {
      if (key.length == 0) {
        false
      } else {
        escape match {
          case true => judge(key.substring(1), false)
          case false => key.charAt(0) match {
            case '*'  => true
            case '?'  => true
            case '['  => true
            case '\\' => judge(key.substring(1), true)
            case _    => judge(key.substring(1), false)
          }
        }
      }
    }
    judge(key, false)
  }

  /**
    * @param jedis
    * @param params
    * @return keys of params pattern in jedis
    */
  private def scanKeys(jedis: Jedis, params: ScanParams): util.HashSet[String] = {
    val keys = new util.HashSet[String]
    var cursor = "0"
    do {
      val scan = jedis.scan(cursor, params)
      keys.addAll(scan.getResult)
      cursor = scan.getStringCursor
    } while (cursor != "0")
    keys
  }

  /**
    * @param nodes list of RedisNode
    * @param sPos start position of slots
    * @param ePos end position of slots
    * @param keyPattern
    * return keys whose slot is in [sPos, ePos]
    */
  def getKeys(nodes: Array[RedisNode],
              sPos: Int,
              ePos: Int,
              keyPattern: String): util.HashSet[String] = {
    val keys = new util.HashSet[String]()
    if (isRedisRegex(keyPattern)) {
      nodes.foreach(node => {
        val conn = node.endpoint.connect()
        val params = new ScanParams().`match`(keyPattern)
        val res = keys.addAll(scanKeys(conn, params).filter(key => {
          val slot = JedisClusterCRC16.getSlot(key)
          slot >= sPos && slot <= ePos
        }))
        conn.close
        res
      })
    } else {
      val slot = JedisClusterCRC16.getSlot(keyPattern)
      if (slot >= sPos && slot <= ePos) keys.add(keyPattern)
    }
    keys
  }

  /**
    * @param nodes list of RedisNode
    * @param keys list of keys
    * return (node: (key1, key2, ...), node2: (key3, key4,...), ...)
    */
  def groupKeysByNode(nodes: Array[RedisNode], keys: Iterator[String]):
  Array[(RedisNode, Array[String])] = {
    def getNode(key: String): RedisNode = {
      val slot = JedisClusterCRC16.getSlot(key)
      /* Master only */
      nodes.filter(node => { node.startSlot <= slot && node.endSlot >= slot }).filter(_.idx == 0)(0)
    }
    keys.map(key => (getNode(key), key)).toArray.groupBy(_._1).
      map(x => (x._1, x._2.map(_._2))).toArray
  }

  /**
    * @param conn
    * @param keys
    * keys are guaranteed that they belongs with the server jedis connected to.
    * return keys of "t" type
    */
  def filterKeysByType(conn: Jedis, keys:Array[String], t:String): Array[String] = {
    val pipeline = conn.pipelined
    keys.foreach(pipeline.`type`)
    val types = pipeline.syncAndReturnAll
    (keys).zip(types).filter(x => (x._2 == t)).map(x => x._1)
  }
}
