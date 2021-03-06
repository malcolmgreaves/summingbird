package com.twitter.summingbird.spark

import com.twitter.algebird.{ Interval, Semigroup }
import com.twitter.summingbird.batch.Timestamp
import com.twitter.summingbird.option.{ NonCommutative, Commutative, Commutativity }
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.rdd.RDD
import scala.reflect.ClassTag
import com.twitter.chill.Externalizer

// TODO: add the logic for seeing what timespans each source / store / service can cover

// A source promises to never return values outside of timeSpan
trait SparkSource[T] extends Serializable {
  def rdd(sc: SparkContext, timeSpan: Interval[Timestamp]): RDD[(Timestamp, T)]
}

trait SparkSink[T] extends Serializable {
  def write(sc: SparkContext, rdd: RDD[(Timestamp, T)], timeSpan: Interval[Timestamp]): Unit
}

case class MergeResult[K, V](
  sumBeforeMerge: RDD[(Timestamp, (K, (Option[V], V)))],
  writeClosure: () => Unit)

trait SparkStore[K, V] extends Serializable {

  def merge(sc: SparkContext,
    timeSpan: Interval[Timestamp],
    deltas: RDD[(Timestamp, (K, V))],
    commutativity: Commutativity,
    semigroup: Semigroup[V]): MergeResult[K, V]
}

abstract class SimpleSparkStore[K: ClassTag, V: ClassTag] extends SparkStore[K, V] {

  // contract is:
  // no duplicate keys
  // provides a view of the world as of exactly the last instant of timeSpan
  // TODO: replace with batched logic (combine snapshots with deltas etc)
  def snapshot(sc: SparkContext, timeSpan: Interval[Timestamp]): RDD[(K, V)]

  def write(sc: SparkContext, updatedSnapshot: RDD[(K, V)]): Unit

  override def merge(sc: SparkContext,
    timeSpan: Interval[Timestamp],
    deltas: RDD[(Timestamp, (K, V))],
    commutativity: Commutativity,
    @transient semigroup: Semigroup[V]): MergeResult[K, V] = {

    val snapshotRdd = snapshot(sc, timeSpan)
    val extSemigroup = Externalizer(semigroup)

    val summedDeltas: RDD[(K, (Timestamp, V))] = commutativity match {
      case Commutative => {
        val keyedDeltas = deltas.map { case (ts, (k, v)) => (k, (ts, v)) }

        keyedDeltas.reduceByKey {
          case ((highestTs, sumSoFar), (ts, v)) =>
            (Ordering[Timestamp].max(highestTs, ts), extSemigroup.get.plus(sumSoFar, v))
        }

      }

      case NonCommutative => {
        val keyedDeltas = deltas.map { case (ts, (k, v)) => (k, (ts, v)) }

        // TODO: how to get a sorted group w/o bringing the entire group into memory?
        //       *does* this even bring it into memory?
        val grouped = keyedDeltas.groupByKey()
        grouped.map {
          case (k, vals) => {
            val sortedVals = vals.sortBy(_._1) // sort by time
            val maxTs = sortedVals.last._1
            val projectedSortedVals = sortedVals.iterator.map(_._2) // just the values
            // projectedSortedVals should never be empty so .get is safe
            (k, (maxTs, extSemigroup.get.sumOption(projectedSortedVals).get))
          }
        }
      }
    }

    // TODO: these 'seqs that are actually options' are ugly
    val grouped = summedDeltas.cogroup(snapshotRdd).map {
      case (k, (summedDeltaSeq, storedValueSeq)) =>
        (k, storedValueSeq.headOption, summedDeltaSeq.headOption)
    }

    val sumBeforeMerge = grouped.flatMap {
      case (k, storedValue, summedDelta) =>
        summedDelta.map { case (ts, d) => (ts, (k, (storedValue, d))) }
    }

    val updatedSnapshot = grouped.map {
      case (k, storedValue, summedDelta) =>
        val v = (summedDelta.map(_._2), storedValue) match {
          case (Some(delta), Some(sv)) => extSemigroup.get.plus(sv, delta)
          case (Some(delta), None) => delta
          case (None, Some(sv)) => sv
          case _ => sys.error("This should never happen, both summedDelta and storedValue were None")
        }
        (k, v)
    }

    val writeClosure = () => write(sc, updatedSnapshot)

    MergeResult(sumBeforeMerge, writeClosure)
  }
}

// TODO: Need to implement the logic for time based lookups (finding what a value was for a given key at a given time)
trait SparkService[K, LV] extends Serializable {
  def lookup[V](sc: SparkContext,
    timeSpan: Interval[Timestamp],
    rdd: RDD[(Timestamp, (K, V))]): RDD[(Timestamp, (K, (V, Option[LV])))]
}