// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant

import cats.syntax.either.*
import com.digitalasset.canton.RequestCounter
import com.digitalasset.canton.config.RequireTypes.NegativeLong
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.store.db.DbSerializationException
import slick.jdbc.{GetResult, SetParameter}

/** LocalOffset are represented by a tuple (effectiveTime, tieBreaker)
  *
  * The effectiveTime is:
  *   - recordTime for [[RequestOffset]]
  *   - topology effectiveTime for [[TopologyOffset]]
  *
  * The tie breaker is:
  *   - The non-negative request counter for [[RequestOffset]]
  *   - A negative value for [[TopologyOffset]]`. This allows to split one topology transaction into several events.
  *
  * The ordering is such that for the same effective time, a request offset is smaller than a topology offset.
  * The rationale is that topology transactions have exclusive valid from.
  *
  * The discriminator is only use to define a lexicographic ordering on the [[LocalOffset]].
  */
sealed trait LocalOffset
    extends PrettyPrinting
    with Ordered[LocalOffset]
    with Product
    with Serializable {
  override def pretty: Pretty[LocalOffset.this.type] =
    prettyOfClass(param("effectiveTime", _.effectiveTime), param("tieBreaker", _.tieBreaker))

  def effectiveTime: CantonTimestamp

  def tieBreaker: Long

  def raw: (CantonTimestamp, Long) = (effectiveTime, tieBreaker)

  /** The discriminator allows to distinguish between [[RequestOffset]] and [[TopologyOffset]].
    * Moreover, the ordering for [[LocalOffset]] is the lexicographic ordering on the tuple
    * (effectiveTime, discriminator, tieBreaker)
    *
    * In particular, for equal [[effectiveTime]], a [[TopologyOffset]] is bigger than a [[RequestOffset]] independent of the [[tieBreaker]].
    */
  final def discriminator: Int =
    if (tieBreaker >= 0) LocalOffset.RequestOffsetDiscriminator
    else LocalOffset.TopologyOffsetDiscriminator

  /** The equality methods automatically generated by the case classes that inherit LocalOffset are
    *  compatible with this compare method: a [[RequestOffset]] is never equal to a [[TopologyOffset]]
    */
  override def compare(that: LocalOffset): Int = LocalOffset.orderingLocalOffset.compare(this, that)

  override def equals(other: Any): Boolean = other match {
    case offset: LocalOffset => offset.compare(this) == 0
    case _ => false
  }
}

final case class RequestOffset(effectiveTime: CantonTimestamp, requestCounter: RequestCounter)
    extends LocalOffset {
  override def tieBreaker: Long = requestCounter.unwrap
}

object RequestOffset {
  implicit val getResultRequestOffset: GetResult[RequestOffset] = GetResult { r =>
    val recordTime = r.<<[CantonTimestamp]
    val discriminator = r.<<[Int]
    val tieBreaker = r.<<[Long]

    if (tieBreaker >= 0 && discriminator == LocalOffset.RequestOffsetDiscriminator)
      RequestOffset(recordTime, RequestCounter(tieBreaker))
    else
      throw new DbSerializationException(
        s"Incompatible tieBreaker=$tieBreaker and discriminator=$discriminator"
      )
  }
}

final case class TopologyOffset private (
    effectiveTime: CantonTimestamp,
    topologyTieBreaker: NegativeLong,
) extends LocalOffset {
  require(topologyTieBreaker != NegativeLong.MinValue, "topology tie breaker cannot be MinValue")

  override def tieBreaker: Long = topologyTieBreaker.unwrap
}

object TopologyOffset {
  def tryCreate(effectiveTime: CantonTimestamp, topologyTieBreaker: NegativeLong): TopologyOffset =
    TopologyOffset(effectiveTime, topologyTieBreaker)

  def create(
      effectiveTime: CantonTimestamp,
      topologyTieBreaker: NegativeLong,
  ): Either[String, TopologyOffset] = Either
    .catchOnly[IllegalArgumentException](tryCreate(effectiveTime, topologyTieBreaker))
    .leftMap(_.getMessage)
}

object LocalOffset {
  val MaxValue: LocalOffset =
    TopologyOffset.tryCreate(CantonTimestamp.MaxValue, NegativeLong.tryCreate(Long.MinValue + 1))

  // Do not change these constant, as they are used in the DBs
  val RequestOffsetDiscriminator: Int = 0
  val TopologyOffsetDiscriminator: Int = 1

  implicit val orderingLocalOffset: Ordering[LocalOffset] = Ordering.by { offset =>
    /*
      NonNegative tieBreakers have orderingDiscriminator=0
      Negative tieBreakers have orderingDiscriminator=1
     */
    (offset.effectiveTime, offset.discriminator, offset.tieBreaker)
  }

  implicit val setParameterLocalOffset: SetParameter[LocalOffset] = (v, p) => {
    p >> v.effectiveTime
    p >> v.discriminator
    p >> v.tieBreaker
  }

  implicit val getResultLocalOffset: GetResult[LocalOffset] = GetResult { r =>
    val recordTime = r.<<[CantonTimestamp]
    val discriminator = r.<<[Int]
    val tieBreaker = r.<<[Long]

    if (tieBreaker >= 0 && discriminator == LocalOffset.RequestOffsetDiscriminator)
      RequestOffset(recordTime, RequestCounter(tieBreaker))
    else if (tieBreaker < 0 && discriminator == LocalOffset.TopologyOffsetDiscriminator)
      TopologyOffset.tryCreate(recordTime, NegativeLong.tryCreate(tieBreaker))
    else
      throw new DbSerializationException(
        s"Incompatible tieBreaker=$tieBreaker and discriminator=$discriminator"
      )
  }
}