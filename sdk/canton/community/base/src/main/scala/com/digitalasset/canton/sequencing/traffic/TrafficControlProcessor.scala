// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.sequencing.traffic

import cats.Monoid
import cats.data.EitherT
import cats.syntax.functorFilter.*
import cats.syntax.parallel.*
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.crypto.{DomainSnapshotSyncCryptoApi, DomainSyncCryptoClient}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.error.Alarm
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.protocol.messages.{
  DefaultOpenEnvelope,
  ProtocolMessage,
  SetTrafficPurchasedMessage,
  SignedProtocolMessage,
}
import com.digitalasset.canton.sequencing.protocol.{
  Deliver,
  DeliverError,
  OpenEnvelope,
  Recipient,
  SequencersOfDomain,
}
import com.digitalasset.canton.sequencing.traffic.TrafficControlErrors.{
  InvalidTrafficPurchasedMessage,
  TrafficControlError,
}
import com.digitalasset.canton.sequencing.traffic.TrafficControlProcessor.TrafficControlSubscriber
import com.digitalasset.canton.sequencing.{
  BoxedEnvelope,
  HandlerResult,
  SubscriptionStart,
  UnsignedEnvelopeBox,
  UnsignedProtocolEventHandler,
}
import com.digitalasset.canton.time.DomainTimeTracker
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.FutureInstances.*
import com.digitalasset.canton.util.MonadUtil

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}

class TrafficControlProcessor(
    cryptoApi: DomainSyncCryptoClient,
    domainId: DomainId,
    maxFromStoreO: => Option[CantonTimestamp],
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext
) extends UnsignedProtocolEventHandler
    with NamedLogging {

  override val name: String = s"traffic-control-processor-$domainId"

  private val listeners = new AtomicReference[List[TrafficControlSubscriber]](List.empty)

  def subscribe(subscriber: TrafficControlSubscriber): Unit =
    listeners.updateAndGet(subscriber :: _).discard

  override def subscriptionStartsAt(start: SubscriptionStart, domainTimeTracker: DomainTimeTracker)(
      implicit traceContext: TraceContext
  ): FutureUnlessShutdown[Unit] = {
    import SubscriptionStart.*

    val tsStart = start match {
      case FreshSubscription =>
        // Use the max timestamp from the store. If the store is empty, use a minimum value
        maxFromStoreO.getOrElse(CantonTimestamp.MinValue)

      case CleanHeadResubscriptionStart(cleanPrehead) =>
        // The first processed event will be at some ts after `cleanPrehead`
        cleanPrehead

      case ReplayResubscriptionStart(firstReplayed, _) =>
        // The first processed event will be at ts `firstReplayed`
        firstReplayed.immediatePredecessor
    }

    logger.debug(s"Initializing traffic controller handler at start=$start with ts=$tsStart")
    notifyListenersOfTimestamp(tsStart)

    FutureUnlessShutdown.unit
  }

  override def apply(
      tracedBatch: BoxedEnvelope[UnsignedEnvelopeBox, DefaultOpenEnvelope]
  ): HandlerResult =
    MonadUtil.sequentialTraverseMonoid(tracedBatch.value) { tracedEvent =>
      implicit val tracContext: TraceContext = tracedEvent.traceContext

      tracedEvent.value match {
        case Deliver(sc, ts, _, _, batch, topologyTimestampO, _) =>
          logger.debug(s"Processing sequenced event with counter $sc and timestamp $ts")

          val domainEnvelopes = ProtocolMessage.filterDomainsEnvelopes(
            batch,
            domainId,
            (wrongMessages: List[DefaultOpenEnvelope]) => {
              val wrongDomainIds = wrongMessages.map(_.protocolMessage.domainId)
              logger.error(
                s"Received traffic purchased entry messages with wrong domain ids: $wrongDomainIds"
              )
            },
          )

          HandlerResult.synchronous(
            processSetTrafficPurchasedEnvelopes(ts, topologyTimestampO, domainEnvelopes)
          )

        case DeliverError(_sc, ts, _domainId, _messageId, _status, _trafficReceipt) =>
          notifyListenersOfTimestamp(ts)
          HandlerResult.done
      }
    }

  private def notifyListenersOfTimestamp(ts: CantonTimestamp)(implicit
      traceContext: TraceContext
  ): Unit = {
    logger.debug(s"Notifying listeners that timestamp $ts was observed")
    listeners.get().foreach(_.observedTimestamp(ts))
  }

  private def notifyListenersOfBalanceUpdate(
      update: SetTrafficPurchasedMessage,
      sequencingTimestamp: CantonTimestamp,
  )(implicit
      traceContext: TraceContext
  ): Future[Unit] = {
    logger.debug(s"Notifying listeners that balance update $update was observed")
    listeners.get().parTraverse_(_.trafficPurchasedUpdate(update, sequencingTimestamp))
  }

  def processSetTrafficPurchasedEnvelopes(
      ts: CantonTimestamp,
      topologyTimestampO: Option[CantonTimestamp],
      envelopes: Seq[OpenEnvelope[ProtocolMessage]],
  )(implicit
      traceContext: TraceContext
  ): FutureUnlessShutdown[Unit] = {
    val trafficEnvelopes = envelopes
      .mapFilter(ProtocolMessage.select[SignedProtocolMessage[SetTrafficPurchasedMessage]])

    val listenersNotifiedF = NonEmpty.from(trafficEnvelopes) match {
      case Some(trafficEnvelopesNE) =>
        // To ensure the recipient resolution is correct, the `topologyTimestamp`, if provided,
        // must be equal to the sequencing time of the event.
        // TODO(i13883): refactor and centralize topology timestamp checks
        val topologyTimestampIsValid = topologyTimestampO.fold(true)(_ == ts)

        if (topologyTimestampIsValid) {
          // Used to combine the `FutureUnlessShutdown[Boolean]` below by disjunction
          implicit def disjunctionBoolean: Monoid[Boolean] =
            new Monoid[Boolean] {
              override def empty: Boolean = false
              override def combine(a: Boolean, b: Boolean): Boolean = a || b
            }

          for {
            // We use the topology snapshot at the time of event sequencing to check
            // the eligible members and signatures.
            snapshot <- cryptoApi.awaitSnapshotUS(ts)

            listenersNotified <- MonadUtil.sequentialTraverseMonoid(trafficEnvelopesNE) { env =>
              processSetTrafficPurchased(env, snapshot, ts)
            }
          } yield listenersNotified
        } else {
          InvalidTrafficPurchasedMessage
            .Error(
              s"Discarding traffic control event because the timestamp of the topology ($topologyTimestampO) " +
                s"is not set to the event timestamp ($ts)"
            )
            .report()

          FutureUnlessShutdown.pure(false)
        }

      case None =>
        FutureUnlessShutdown.pure(false)
    }

    for {
      listenersNotified <- listenersNotifiedF
      _ = if (!listenersNotified) notifyListenersOfTimestamp(ts)
    } yield ()
  }

  /** @return `true` if the message was a valid update and the listeners have been notified, `false` otherwise
    */
  private def processSetTrafficPurchased(
      envelope: OpenEnvelope[SignedProtocolMessage[SetTrafficPurchasedMessage]],
      snapshot: DomainSnapshotSyncCryptoApi,
      sequencingTimestamp: CantonTimestamp,
  )(implicit
      traceContext: TraceContext
  ): FutureUnlessShutdown[Boolean] = {
    val result = for {
      _ <- validateSetTrafficPurchased(envelope, snapshot)

      signedMessage = envelope.protocolMessage
      message = signedMessage.message
      _ <- EitherT
        .liftF[Future, TrafficControlError, Unit](
          notifyListenersOfBalanceUpdate(message, sequencingTimestamp)
        )
        .mapK(FutureUnlessShutdown.outcomeK)
    } yield true

    result.valueOr { err =>
      err match {
        case _: Alarm => // already logged
        case err => logger.warn(s"Error during processing of traffic purchased entry message: $err")
      }
      false
    }
  }

  private def validateSetTrafficPurchased(
      envelope: OpenEnvelope[SignedProtocolMessage[SetTrafficPurchasedMessage]],
      snapshot: DomainSnapshotSyncCryptoApi,
  )(implicit
      traceContext: TraceContext
  ): EitherT[FutureUnlessShutdown, TrafficControlError, Unit] = {
    val signedMessage = envelope.protocolMessage
    val signatures = signedMessage.signatures

    logger.debug(
      s"Validating message ${signedMessage.message} with ${signatures.size}" +
        s" signature${if (signatures.sizeIs > 1) "s" else ""}"
    )

    val expectedRecipients = Set(SequencersOfDomain: Recipient)
    val actualRecipients = envelope.recipients.allRecipients

    for {
      // Check that the recipients contain the domain sequencers
      _ <- EitherT.cond[FutureUnlessShutdown](
        expectedRecipients.subsetOf(actualRecipients),
        (),
        TrafficControlErrors.InvalidTrafficPurchasedMessage.Error(
          s"""A SetTrafficPurchased message should be addressed to all the sequencers of a domain.
           |Instead, it was addressed to: $actualRecipients. Skipping it.""".stripMargin
        ): TrafficControlError,
      )

      // Check that within `signatures` we have at least `threshold` valid signatures from `sequencers`
      _ <- signedMessage
        .verifySequencerSignatures(snapshot)
        .leftMap(err =>
          TrafficControlErrors.InvalidTrafficPurchasedMessage
            .Error(err.show): TrafficControlError
        )
    } yield ()
  }
}

object TrafficControlProcessor {
  trait TrafficControlSubscriber {
    def observedTimestamp(timestamp: CantonTimestamp)(implicit
        traceContext: TraceContext
    ): Unit

    def trafficPurchasedUpdate(
        update: SetTrafficPurchasedMessage,
        sequencingTimestamp: CantonTimestamp,
    )(implicit
        traceContext: TraceContext
    ): Future[Unit]
  }
}
