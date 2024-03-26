// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.sequencing.protocol

import com.daml.nonempty.NonEmptyUtil
import com.digitalasset.canton.config.CantonRequireTypes.String73
import com.digitalasset.canton.data.{CantonTimestamp, GeneratorsDataTime}
import com.digitalasset.canton.protocol.TargetDomainId
import com.digitalasset.canton.protocol.messages.{GeneratorsMessages, ProtocolMessage}
import com.digitalasset.canton.time.TimeProofTestUtil
import com.digitalasset.canton.topology.{DomainId, Member}
import com.digitalasset.canton.version.ProtocolVersion
import com.digitalasset.canton.{Generators, SequencerCounter}
import com.google.protobuf.ByteString
import magnolify.scalacheck.auto.*
import org.scalacheck.{Arbitrary, Gen}

final class GeneratorsProtocol(
    protocolVersion: ProtocolVersion,
    generatorsDataTime: GeneratorsDataTime,
    generatorsMessages: GeneratorsMessages,
) {
  import com.digitalasset.canton.Generators.*
  import com.digitalasset.canton.config.GeneratorsConfig.*
  import com.digitalasset.canton.topology.GeneratorsTopology.*
  import generatorsDataTime.*

  implicit val acknowledgeRequestArb: Arbitrary[AcknowledgeRequest] = Arbitrary(for {
    ts <- Arbitrary.arbitrary[CantonTimestamp]
    member <- Arbitrary.arbitrary[Member]
  } yield AcknowledgeRequest(member, ts, protocolVersion))

  implicit val recipientArb: Arbitrary[Recipient] = genArbitrary

  private def recipientsTreeGen(
      recipientArb: Arbitrary[Recipient]
  )(depth: Int): Gen[RecipientsTree] = {
    val maxBreadth = 5
    val recipientGroupGen = nonEmptySetGen(recipientArb)

    if (depth == 0) {
      recipientGroupGen.map(RecipientsTree(_, Nil))
    } else {
      for {
        children <- Gen.listOfN(maxBreadth, recipientsTreeGen(recipientArb)(depth - 1))
        recipientGroup <- recipientGroupGen
      } yield RecipientsTree(recipientGroup, children)
    }
  }

  implicit val recipientsArb: Arbitrary[Recipients] = {
    Arbitrary(for {
      depths <- nonEmptyListGen(Arbitrary(Gen.choose(0, 3)))
      trees <- Gen.sequence[List[RecipientsTree], RecipientsTree](
        depths.forgetNE.map(recipientsTreeGen(implicitly[Arbitrary[Recipient]])(_))
      )
    } yield Recipients(NonEmptyUtil.fromUnsafe(trees)))
  }

  implicit val closedEnvelopeArb: Arbitrary[ClosedEnvelope] = Arbitrary(
    for {
      bytes <- Arbitrary.arbitrary[ByteString]
      recipients <- recipientsArb.arbitrary
    } yield ClosedEnvelope(bytes, recipients, protocolVersion)
  )

  implicit val messageIdArb: Arbitrary[MessageId] = Arbitrary(
    Generators.lengthLimitedStringGen(String73).map(s => MessageId.tryCreate(s.str))
  )

  implicit val openEnvelopArb: Arbitrary[OpenEnvelope[ProtocolMessage]] = Arbitrary(
    for {
      protocolMessage <- generatorsMessages.protocolMessageGen
      recipients <- recipientsArb.arbitrary
    } yield OpenEnvelope(protocolMessage, recipients)(protocolVersion)
  )

  implicit val envelopeArb: Arbitrary[Envelope[?]] =
    Arbitrary(Gen.oneOf[Envelope[?]](closedEnvelopeArb.arbitrary, openEnvelopArb.arbitrary))

  def deliverGen[Env <: Envelope[?]](
      domainId: DomainId,
      batch: Batch[Env],
  ): Gen[Deliver[Env]] = for {
    timestamp <- Arbitrary.arbitrary[CantonTimestamp]
    counter <- Arbitrary.arbitrary[SequencerCounter]
    messageIdO <- Gen.option(Arbitrary.arbitrary[MessageId])
  } yield Deliver.create(
    counter,
    timestamp,
    domainId,
    messageIdO,
    batch,
    protocolVersion,
  )

  implicit val batchArb: Arbitrary[Batch[Envelope[?]]] =
    Arbitrary(for {
      envelopes <- Generators.nonEmptyListGen[Envelope[?]](envelopeArb)
    } yield Batch(envelopes.map(_.closeEnvelope), protocolVersion))

  implicit val submissionRequestArb: Arbitrary[SubmissionRequest] =
    Arbitrary(
      for {
        sender <- Arbitrary.arbitrary[Member]
        messageId <- Arbitrary.arbitrary[MessageId]
        isRequest <- Arbitrary.arbitrary[Boolean]
        envelopes <- Generators.nonEmptyListGen[ClosedEnvelope](closedEnvelopeArb)
        batch = Batch(envelopes.map(_.closeEnvelope), protocolVersion)
        maxSequencingTime <- Arbitrary.arbitrary[CantonTimestamp]
        timestampOfSigningKey = None
      } yield SubmissionRequest.tryCreate(
        sender,
        messageId,
        isRequest,
        batch,
        maxSequencingTime,
        timestampOfSigningKey,
        SubmissionRequest.protocolVersionRepresentativeFor(protocolVersion).representative,
      )
    )

  implicit val timeProofArb: Arbitrary[TimeProof] = Arbitrary(for {
    timestamp <- Arbitrary.arbitrary[CantonTimestamp]
    counter <- nonNegativeLongArb.arbitrary.map(_.unwrap)
    targetDomain <- Arbitrary.arbitrary[TargetDomainId]
  } yield TimeProofTestUtil.mkTimeProof(timestamp, counter, targetDomain, protocolVersion))
}