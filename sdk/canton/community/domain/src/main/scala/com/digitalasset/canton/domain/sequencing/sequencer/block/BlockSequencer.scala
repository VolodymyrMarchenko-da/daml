// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.domain.sequencing.sequencer.block

import cats.data.EitherT
import cats.syntax.either.*
import com.digitalasset.canton.SequencerCounter
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.config.RequireTypes.{NonNegativeLong, PositiveInt}
import com.digitalasset.canton.crypto.{DomainSyncCryptoClient, HashPurpose, Signature}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.domain.api.v30.TrafficControlErrorReason
import com.digitalasset.canton.domain.block.BlockSequencerStateManagerBase
import com.digitalasset.canton.domain.block.data.SequencerBlockStore
import com.digitalasset.canton.domain.block.update.{BlockUpdateGeneratorImpl, LocalBlockUpdate}
import com.digitalasset.canton.domain.metrics.SequencerMetrics
import com.digitalasset.canton.domain.sequencing.admin.data.SequencerHealthStatus
import com.digitalasset.canton.domain.sequencing.sequencer.PruningError.UnsafePruningPoint
import com.digitalasset.canton.domain.sequencing.sequencer.Sequencer.SignedOrderingRequest
import com.digitalasset.canton.domain.sequencing.sequencer.*
import com.digitalasset.canton.domain.sequencing.sequencer.block.BlockSequencerFactory.OrderingTimeFixMode
import com.digitalasset.canton.domain.sequencing.sequencer.errors.SequencerError
import com.digitalasset.canton.domain.sequencing.sequencer.traffic.TimestampSelector.*
import com.digitalasset.canton.domain.sequencing.sequencer.traffic.{
  SequencerRateLimitError,
  SequencerRateLimitManager,
  SequencerTrafficStatus,
}
import com.digitalasset.canton.domain.sequencing.traffic.store.TrafficPurchasedStore
import com.digitalasset.canton.lifecycle.*
import com.digitalasset.canton.logging.pretty.CantonPrettyPrinter
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.sequencing.client.SequencerClientSend
import com.digitalasset.canton.sequencing.protocol.*
import com.digitalasset.canton.sequencing.traffic.TrafficControlErrors.TrafficControlError
import com.digitalasset.canton.sequencing.traffic.{
  TrafficControlErrors,
  TrafficPurchasedSubmissionHandler,
}
import com.digitalasset.canton.serialization.HasCryptographicEvidence
import com.digitalasset.canton.time.{Clock, DomainTimeTracker}
import com.digitalasset.canton.topology.*
import com.digitalasset.canton.tracing.{TraceContext, Traced}
import com.digitalasset.canton.util.EitherTUtil.condUnitET
import com.digitalasset.canton.util.{EitherTUtil, PekkoUtil, SimpleExecutionQueue}
import com.digitalasset.canton.version.ProtocolVersion
import io.grpc.ServerServiceDefinition
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.*
import org.apache.pekko.stream.scaladsl.{Keep, Merge, Sink, Source}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.chaining.*
import scala.util.{Failure, Success}

class BlockSequencer(
    blockOrderer: BlockOrderer,
    name: String,
    domainId: DomainId,
    cryptoApi: DomainSyncCryptoClient,
    sequencerId: SequencerId,
    stateManager: BlockSequencerStateManagerBase,
    store: SequencerBlockStore,
    trafficPurchasedStore: TrafficPurchasedStore,
    storage: Storage,
    futureSupervisor: FutureSupervisor,
    health: Option[SequencerHealthConfig],
    clock: Clock,
    protocolVersion: ProtocolVersion,
    blockRateLimitManager: SequencerRateLimitManager,
    orderingTimeFixMode: OrderingTimeFixMode,
    processingTimeouts: ProcessingTimeout,
    logEventDetails: Boolean,
    prettyPrinter: CantonPrettyPrinter,
    metrics: SequencerMetrics,
    loggerFactory: NamedLoggerFactory,
    exitOnFatalFailures: Boolean,
    runtimeReady: FutureUnlessShutdown[Unit],
)(implicit executionContext: ExecutionContext, materializer: Materializer, tracer: Tracer)
    extends DatabaseSequencer(
      SequencerWriterStoreFactory.singleInstance,
      // TODO(#18407): Allow partial configuration of DBS as a part of unified sequencer
      DatabaseSequencerConfig.ForBlockSequencer(),
      None,
      TotalNodeCountValues.SingleSequencerTotalNodeCount,
      new LocalSequencerStateEventSignaller(
        processingTimeouts,
        loggerFactory,
      ),
      None,
      // TODO(#18407): Dummy config which will be ignored anyway as `config.highAvailabilityEnabled` is false
      OnlineSequencerCheckConfig(),
      processingTimeouts,
      storage,
      None,
      health,
      clock,
      domainId,
      sequencerId,
      Some(blockRateLimitManager.trafficConsumedStore),
      protocolVersion,
      cryptoApi,
      metrics,
      loggerFactory,
      blockSequencerMode = true,
      runtimeReady,
    )
    with DatabaseSequencerIntegration
    with NamedLogging
    with FlagCloseableAsync {

  private[sequencer] val pruningQueue = new SimpleExecutionQueue(
    "block-sequencer-pruning-queue",
    futureSupervisor,
    timeouts,
    loggerFactory,
    crashOnFailure = exitOnFatalFailures,
  )

  override lazy val rateLimitManager: Option[SequencerRateLimitManager] = Some(
    blockRateLimitManager
  )

  private val trafficPurchasedSubmissionHandler =
    new TrafficPurchasedSubmissionHandler(clock, loggerFactory)
  override private[sequencing] def firstSequencerCounterServeableForSequencer: SequencerCounter =
    stateManager.firstSequencerCounterServableForSequencer

  override protected def resetWatermarkTo: SequencerWriter.ResetWatermark =
    SequencerWriter.ResetWatermarkToTimestamp(stateManager.getHeadState.block.lastTs)

  private val (killSwitchF, localEventsQueue, done) = {
    val headState = stateManager.getHeadState
    noTracingLogger.info(s"Subscribing to block source from ${headState.block.height}")

    val updateGenerator = new BlockUpdateGeneratorImpl(
      domainId,
      protocolVersion,
      cryptoApi,
      sequencerId,
      stateManager.maybeLowerTopologyTimestampBound,
      blockRateLimitManager,
      orderingTimeFixMode,
      metrics,
      loggerFactory,
      memberValidator = memberValidator,
    )(CloseContext(cryptoApi))

    val driverSource = Source
      .futureSource(runtimeReady.unwrap.map {
        case UnlessShutdown.AbortedDueToShutdown =>
          logger.debug("Not initiating subscription to block source due to shutdown")(
            TraceContext.empty
          )
          Source.empty.viaMat(KillSwitches.single)(Keep.right)
        case UnlessShutdown.Outcome(_) =>
          logger.debug("Subscribing to block source")(TraceContext.empty)
          blockOrderer.subscribe()(TraceContext.empty)
      })
      // Explicit async to make sure that the block processing runs in parallel with the block retrieval
      .async
      .map(updateGenerator.extractBlockEvents)
      .via(stateManager.processBlock(updateGenerator))

    val localSource = Source
      .queue[Traced[BlockSequencer.LocalEvent]](bufferSize = 1000, OverflowStrategy.backpressure)
      .map(_.map(event => LocalBlockUpdate(event)))
    val combinedSource = Source.combineMat(driverSource, localSource)(Merge(_))(Keep.both)
    val combinedSourceWithBlockHandling = combinedSource.async
      .via(stateManager.applyBlockUpdate(this))
      .wireTap { lastTs =>
        metrics.block.delay.updateValue((clock.now - lastTs.value).toMillis)
      }
    val ((killSwitchF, localEventsQueue), done) = PekkoUtil.runSupervised(
      ex => logger.error("Fatally failed to handle state changes", ex)(TraceContext.empty),
      combinedSourceWithBlockHandling.toMat(Sink.ignore)(Keep.both),
    )
    (killSwitchF, localEventsQueue, done)
  }

  done onComplete {
    case Success(_) => noTracingLogger.debug("Sequencer flow has shutdown")
    case Failure(ex) => noTracingLogger.error("Sequencer flow has failed", ex)
  }

  private def validateMaxSequencingTime(
      submission: SubmissionRequest
  )(implicit traceContext: TraceContext): EitherT[Future, SendAsyncError, Unit] = {
    val estimatedSequencingTimestamp = clock.now
    submission.aggregationRule match {
      case Some(_) =>
        for {
          _ <- EitherTUtil.condUnitET[Future](
            submission.maxSequencingTime > estimatedSequencingTimestamp,
            SendAsyncError.RequestInvalid(
              s"The sequencer clock timestamp $estimatedSequencingTimestamp is already past the max sequencing time ${submission.maxSequencingTime} for submission with id ${submission.messageId}"
            ),
          )
          // We can't easily use snapshot(topologyTimestamp), because the effective last snapshot transaction
          // visible in the BlockSequencer can be behind the topologyTimestamp and tracking that there's an
          // intermediate topology change is impossible here (will need to communicate with the BlockUpdateGenerator).
          // If topologyTimestamp happens to be ahead of current topology's timestamp we grab the latter
          // to prevent a deadlock.
          topologyTimestamp = cryptoApi.approximateTimestamp.min(
            submission.topologyTimestamp.getOrElse(CantonTimestamp.MaxValue)
          )
          snapshot <- EitherT.right(cryptoApi.snapshot(topologyTimestamp))
          domainParameters <- EitherT(
            snapshot.ipsSnapshot.findDynamicDomainParameters()
          )
            .leftMap(error =>
              SendAsyncError.Internal(s"Could not fetch dynamic domain parameters: $error")
            )
          maxSequencingTimeUpperBound = estimatedSequencingTimestamp.add(
            domainParameters.parameters.sequencerAggregateSubmissionTimeout.duration
          )
          _ <- EitherTUtil.condUnitET[Future](
            submission.maxSequencingTime < maxSequencingTimeUpperBound,
            SendAsyncError.RequestInvalid(
              s"Max sequencing time ${submission.maxSequencingTime} for submission with id ${submission.messageId} is too far in the future, currently bounded at $maxSequencingTimeUpperBound"
            ): SendAsyncError,
          )
        } yield ()
      case None => EitherT.right[SendAsyncError](Future.unit)
    }
  }

  override protected def sendAsyncInternal(
      submission: SubmissionRequest
  )(implicit traceContext: TraceContext): EitherT[FutureUnlessShutdown, SendAsyncError, Unit] = {
    val signedContent = SignedContent(submission, Signature.noSignature, None, protocolVersion)
    sendAsyncSignedInternal(signedContent)
  }

  override def adminServices: Seq[ServerServiceDefinition] = blockOrderer.adminServices

  private def signOrderingRequest[A <: HasCryptographicEvidence](
      content: SignedContent[SubmissionRequest]
  )(implicit
      tc: TraceContext
  ): EitherT[FutureUnlessShutdown, SendAsyncError.Internal, SignedOrderingRequest] = {
    val privateCrypto = cryptoApi.currentSnapshotApproximation
    for {
      signed <- SignedContent
        .create(
          cryptoApi.pureCrypto,
          privateCrypto,
          OrderingRequest.create(sequencerId, content, protocolVersion),
          Some(privateCrypto.ipsSnapshot.timestamp),
          HashPurpose.OrderingRequestSignature,
          protocolVersion,
        )
        .leftMap(error => SendAsyncError.Internal(s"Could not sign ordering request: $error"))
    } yield signed
  }

  private def enforceRateLimiting(
      request: SignedContent[SubmissionRequest]
  )(implicit tc: TraceContext): EitherT[FutureUnlessShutdown, SendAsyncError, Unit] =
    blockRateLimitManager
      .validateRequestAtSubmissionTime(
        request.content,
        request.timestampOfSigningKey,
        stateManager.getHeadState.block.lastTs,
        stateManager.getHeadState.block.latestSequencerEventTimestamp,
      )
      .leftMap {
        // If the cost is outdated, we bounce the request with a specific SendAsyncError so the
        // sender has the required information to retry the request with the correct cost
        case notEnoughTraffic: SequencerRateLimitError.AboveTrafficLimit =>
          logger.debug(
            s"Rejecting submission request because not enough traffic is available: $notEnoughTraffic"
          )
          SendAsyncError.TrafficControlError(
            TrafficControlErrorReason.Error(
              TrafficControlErrorReason.Error.Reason.InsufficientTraffic(
                s"Submission was rejected because not traffic is available: $notEnoughTraffic"
              )
            )
          ): SendAsyncError
        // If the cost is outdated, we bounce the request with a specific SendAsyncError so the
        // sender has the required information to retry the request with the correct cost
        case outdated: SequencerRateLimitError.OutdatedEventCost =>
          logger.debug(
            s"Rejecting submission request because the cost was computed using an outdated topology: $outdated"
          )
          SendAsyncError.TrafficControlError(
            TrafficControlErrorReason.Error(
              TrafficControlErrorReason.Error.Reason.InsufficientTraffic(
                s"Submission was refused because traffic cost was outdated. Re-submit after the having observed the validation timestamp and processed its topology state: $outdated"
              )
            )
          ): SendAsyncError
        case error =>
          SendAsyncError.RequestRefused(
            s"Submission was refused because traffic control validation failed: $error"
          ): SendAsyncError
      }

  override protected def sendAsyncSignedInternal(
      signedSubmission: SignedContent[SubmissionRequest]
  )(implicit traceContext: TraceContext): EitherT[FutureUnlessShutdown, SendAsyncError, Unit] = {
    val submission = signedSubmission.content
    val SubmissionRequest(
      sender,
      _,
      batch,
      maxSequencingTime,
      _,
      _aggregationRule,
      _submissionCost,
    ) = submission
    logger.debug(
      s"Request to send submission with id ${submission.messageId} with max sequencing time $maxSequencingTime from $sender to ${batch.allRecipients}"
    )

    for {
      // TODO(i17584): revisit the consequences of no longer enforcing that
      //  aggregated submissions with signed envelopes define a topology snapshot
      _ <- validateMaxSequencingTime(submission).mapK(FutureUnlessShutdown.outcomeK)
      memberCheck <- EitherT
        .right[SendAsyncError](
          // Using currentSnapshotApproximation due to members registration date
          // expected to be before submission sequencing time
          cryptoApi.currentSnapshotApproximation.ipsSnapshot
            .allMembers()
            .map(allMembers => (member: Member) => allMembers.contains(member))
        )
        .mapK(FutureUnlessShutdown.outcomeK)
      // TODO(#19476): Why we don't check group recipients here?
      _ <- SequencerValidations
        .checkSenderAndRecipientsAreRegistered(
          submission,
          memberCheck,
        )
        .toEitherT[FutureUnlessShutdown]
      _ = if (logEventDetails)
        logger.debug(
          s"Invoking send operation on the ledger with the following protobuf message serialized to bytes ${prettyPrinter
              .printAdHoc(submission.toProtoVersioned)}"
        )
      signedOrderingRequest <- signOrderingRequest(signedSubmission)
      _ <- enforceRateLimiting(signedSubmission)
      _ <-
        EitherT(
          futureSupervisor
            .supervised(
              s"Sending submission request with id ${submission.messageId} from $sender to ${batch.allRecipients}"
            )(
              blockOrderer.send(signedOrderingRequest).value
            )
        ).mapK(FutureUnlessShutdown.outcomeK)
    } yield ()
  }

  override protected def localSequencerMember: Member = sequencerId

  override protected def acknowledgeSignedInternal(
      signedAcknowledgeRequest: SignedContent[AcknowledgeRequest]
  )(implicit traceContext: TraceContext): Future[Unit] = {
    val req = signedAcknowledgeRequest.content
    logger.debug(s"Request for member ${req.member} to acknowledge timestamp ${req.timestamp}")
    val waitForAcknowledgementF =
      stateManager.waitForAcknowledgementToComplete(req.member, req.timestamp)
    for {
      _ <- blockOrderer.acknowledge(signedAcknowledgeRequest)
      _ <- waitForAcknowledgementF
    } yield ()
  }

  override def snapshot(
      timestamp: CantonTimestamp
  )(implicit traceContext: TraceContext): EitherT[Future, SequencerError, SequencerSnapshot] =
    // TODO(#12676) Make sure that we don't request a snapshot for a state that was already pruned

    for {
      additionalInfo <- blockOrderer.sequencerSnapshotAdditionalInfo(timestamp)
      implementationSpecificInfo = additionalInfo.map(info =>
        SequencerSnapshot.ImplementationSpecificInfo(
          implementationName = "BlockSequencer",
          info.toByteString,
        )
      )
      bsSnapshot <- store
        .readStateForBlockContainingTimestamp(timestamp)
        .flatMap { blockEphemeralState =>
          for {
            // Look up traffic info at the latest timestamp from the block,
            // because that's where the onboarded sequencer will start reading
            trafficPurchased <- EitherT.right[SequencerError](
              trafficPurchasedStore
                .lookupLatestBeforeInclusive(blockEphemeralState.latestBlock.lastTs)
            )
            trafficConsumed <- EitherT.right[SequencerError](
              blockRateLimitManager.trafficConsumedStore
                .lookupLatestBeforeInclusive(blockEphemeralState.latestBlock.lastTs)
            )
          } yield blockEphemeralState
            .toSequencerSnapshot(
              protocolVersion,
              trafficPurchased,
              trafficConsumed,
              implementationSpecificInfo,
            )
            .tap(_ =>
              if (logger.underlying.isDebugEnabled()) {
                logger.trace(
                  s"Snapshot for timestamp $timestamp generated from ephemeral state:\n$blockEphemeralState"
                )
              }
            )
        }
      finalSnapshot <- {
        super.snapshot(bsSnapshot.lastTs).map { dbsSnapshot =>
          dbsSnapshot.copy(
            latestBlockHeight = bsSnapshot.latestBlockHeight,
            inFlightAggregations = bsSnapshot.inFlightAggregations,
            additional = bsSnapshot.additional,
            trafficPurchased = bsSnapshot.trafficPurchased,
            trafficConsumed = bsSnapshot.trafficConsumed,
          )(dbsSnapshot.representativeProtocolVersion)
        }
      }
    } yield {
      logger.trace(
        s"Resulting snapshot for timestamp $timestamp:\n$finalSnapshot"
      )
      finalSnapshot
    }

  /** Important: currently both the disable member and the prune functionality on the block sequencer are
    * purely local operations that do not affect other block sequencers that share the same source of
    * events.
    */
  override def prune(requestedTimestamp: CantonTimestamp)(implicit
      traceContext: TraceContext
  ): EitherT[Future, PruningError, String] = {

    val (isNewRequest, pruningF) = stateManager.waitForPruningToComplete(requestedTimestamp)
    val supervisedPruningF = futureSupervisor.supervised(
      s"Waiting for local pruning operation at $requestedTimestamp to complete"
    )(pruningF)

    if (isNewRequest)
      for {
        status <- EitherT.right[PruningError](this.pruningStatus)
        _ <- condUnitET[Future](
          requestedTimestamp <= status.safePruningTimestamp,
          UnsafePruningPoint(requestedTimestamp, status.safePruningTimestamp): PruningError,
        )
        msg <- EitherT(
          pruningQueue
            .execute(
              for {
                eventsMsg <- store.prune(requestedTimestamp)
                trafficMsg <- blockRateLimitManager.prune(requestedTimestamp)
                msgEither <-
                  super[DatabaseSequencer]
                    .prune(requestedTimestamp)
                    .map(dbsMsg =>
                      s"${eventsMsg.replace("0 events and ", "")}\n$dbsMsg\n$trafficMsg"
                    )
                    .value
              } yield msgEither,
              s"pruning sequencer at $requestedTimestamp",
            )
            .unwrap
            .map(
              _.onShutdown(
                Right(s"pruning at $requestedTimestamp canceled because we're shutting down")
              )
            )
        )
        _ <- EitherT.right(
          placeLocalEvent(BlockSequencer.UpdateInitialMemberCounters(requestedTimestamp))
        )
        _ <- EitherT.right(supervisedPruningF)
      } yield msg
    else
      EitherT.right(
        supervisedPruningF.map(_ =>
          s"Pruning at $requestedTimestamp is already happening due to an earlier request"
        )
      )
  }

  private def placeLocalEvent(event: BlockSequencer.LocalEvent)(implicit
      traceContext: TraceContext
  ): Future[Unit] = localEventsQueue.offer(Traced(event)).flatMap {
    case QueueOfferResult.Enqueued => Future.unit
    case QueueOfferResult.Dropped => // this should never happen
      Future.failed[Unit](new RuntimeException(s"Request queue is full. cannot take local $event"))
    case QueueOfferResult.Failure(cause) => Future.failed(cause)
    case QueueOfferResult.QueueClosed =>
      logger.info(s"Tried to place a local $event request after the sequencer has been shut down.")
      Future.unit
  }

  override def locatePruningTimestamp(index: PositiveInt)(implicit
      traceContext: TraceContext
  ): EitherT[Future, PruningSupportError, Option[CantonTimestamp]] =
    EitherT.leftT[Future, Option[CantonTimestamp]](PruningError.NotSupported)

  override def reportMaxEventAgeMetric(
      oldestEventTimestamp: Option[CantonTimestamp]
  ): Either[PruningSupportError, Unit] = Either.left(PruningError.NotSupported)

  override protected def healthInternal(implicit
      traceContext: TraceContext
  ): Future[SequencerHealthStatus] =
    for {
      ledgerStatus <- blockOrderer.health
      isStorageActive = storage.isActive
      _ = logger.trace(s"Storage active: ${storage.isActive}")
    } yield {
      if (!ledgerStatus.isActive) SequencerHealthStatus(isActive = false, ledgerStatus.description)
      else
        SequencerHealthStatus(
          isStorageActive,
          if (isStorageActive) None else Some("Can't connect to database"),
        )
    }

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = {
    import TraceContext.Implicits.Empty.*
    logger.debug(s"$name sequencer shutting down")
    Seq[AsyncOrSyncCloseable](
      SyncCloseable("pruningQueue", pruningQueue.close()),
      SyncCloseable("stateManager.close()", stateManager.close()),
      SyncCloseable("localEventsQueue.complete", localEventsQueue.complete()),
      AsyncCloseable(
        "localEventsQueue.watchCompletion",
        localEventsQueue.watchCompletion(),
        timeouts.shutdownProcessing,
      ),
      // The kill switch ensures that we don't process the remaining contents of the queue buffer
      AsyncCloseable(
        "killSwitchF(_.shutdown())",
        killSwitchF.map(_.shutdown()),
        timeouts.shutdownProcessing,
      ),
      SyncCloseable(
        "DatabaseSequencer.onClose()",
        super[DatabaseSequencer].onClosed(),
      ),
      AsyncCloseable("done", done, timeouts.shutdownProcessing),
      SyncCloseable("blockOrderer.close()", blockOrderer.close()),
    )
  }

  /** Compute traffic states for the specified members at the provided timestamp.
    * @param requestedMembers members for which to compute traffic states
    * @param selector timestamp selector determining at what time the traffic states will be computed
    */
  private def trafficStatesForMembers(
      requestedMembers: Set[Member],
      selector: TimestampSelector,
  )(implicit
      traceContext: TraceContext
  ): FutureUnlessShutdown[Map[Member, Either[String, TrafficState]]] =
    if (requestedMembers.isEmpty) {
      // getStates interprets an empty list of members as "return all members"
      // so we handle it here.
      FutureUnlessShutdown.pure(Map.empty)
    } else {
      val timestamp = selector match {
        case ExactTimestamp(timestamp) => Some(timestamp)
        case LastUpdatePerMember => None
        // For the latest safe timestamp, we use the last timestamp of the latest processed block.
        // Even though it may be more recent than the TrafficConsumed timestamp of individual members,
        // we are sure that nothing has been consumed since then, because by the time we update getHeadState.block.lastTs
        // all traffic has been consumed for that block. This means we can use this timestamp to compute an updated
        // base traffic that will be correct.
        case LatestSafe => Some(stateManager.getHeadState.block.lastTs)
        case LatestApproximate => Some(clock.now.max(stateManager.getHeadState.block.lastTs))
      }

      blockRateLimitManager.getStates(
        requestedMembers,
        timestamp,
        stateManager.getHeadState.block.latestSequencerEventTimestamp,
        // Warn on approximate topology or traffic purchased when getting exact traffic states only (so when selector is not LatestApproximate)
        warnIfApproximate = selector != LatestApproximate &&
          // Also don't warn until the sequencer has at least received one event
          stateManager.getHeadState.chunk.ephemeral
            .headCounter(sequencerId)
            .exists(_ > SequencerCounter.Genesis),
      )
    }

  override def setTrafficPurchased(
      member: Member,
      serial: PositiveInt,
      totalTrafficPurchased: NonNegativeLong,
      sequencerClient: SequencerClientSend,
      domainTimeTracker: DomainTimeTracker,
  )(implicit
      traceContext: TraceContext
  ): EitherT[FutureUnlessShutdown, TrafficControlError, Unit] =
    for {
      latestBalanceO <- EitherT.right(blockRateLimitManager.lastKnownBalanceFor(member))
      maxSerialO = latestBalanceO.map(_.serial)
      _ <- EitherTUtil.condUnitET[FutureUnlessShutdown](
        maxSerialO.forall(_ < serial),
        TrafficControlErrors.TrafficControlSerialTooLow.Error(
          s"The provided serial value $serial is too low. Latest serial used by this member is $maxSerialO"
        ),
      )
      _ <- trafficPurchasedSubmissionHandler.sendTrafficPurchasedRequest(
        member,
        domainId,
        protocolVersion,
        serial,
        totalTrafficPurchased,
        sequencerClient,
        domainTimeTracker,
        cryptoApi,
      )
    } yield ()

  override def trafficStatus(requestedMembers: Seq[Member], selector: TimestampSelector)(implicit
      traceContext: TraceContext
  ): FutureUnlessShutdown[SequencerTrafficStatus] =
    for {
      members <-
        if (requestedMembers.isEmpty) {
          // If requestedMembers is not set get the traffic states of all known members
          FutureUnlessShutdown.outcomeF(
            cryptoApi.currentSnapshotApproximation.ipsSnapshot.allMembers()
          )
        } else {
          FutureUnlessShutdown.outcomeF(
            cryptoApi.currentSnapshotApproximation.ipsSnapshot
              .allMembers()
              .map { registered =>
                requestedMembers.toSet.intersect(registered)
              }
          )
        }
      trafficState <- trafficStatesForMembers(
        members,
        selector,
      )
    } yield SequencerTrafficStatus(trafficState)

  override def getTrafficStateAt(member: Member, timestamp: CantonTimestamp)(implicit
      traceContext: TraceContext
  ): EitherT[FutureUnlessShutdown, SequencerRateLimitError.TrafficNotFound, Option[
    TrafficState
  ]] =
    blockRateLimitManager.getTrafficStateForMemberAt(
      member,
      timestamp,
      stateManager.getHeadState.block.latestSequencerEventTimestamp,
    )
}

object BlockSequencer {
  sealed trait LocalEvent extends Product with Serializable
  final case class DisableMember(member: Member) extends LocalEvent
  final case class UpdateInitialMemberCounters(timestamp: CantonTimestamp) extends LocalEvent
}
