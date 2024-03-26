// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.data

import cats.syntax.either.*
import cats.syntax.traverse.*
import com.digitalasset.canton.LfPartyId
import com.digitalasset.canton.ProtoDeserializationError.InvariantViolation
import com.digitalasset.canton.config.RequireTypes.{NonNegativeInt, PositiveInt}
import com.digitalasset.canton.crypto.*
import com.digitalasset.canton.data.ViewConfirmationParameters.InvalidViewConfirmationParameters
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.protocol.{ConfirmationPolicy, v0, v1, v2}
import com.digitalasset.canton.serialization.ProtoConverter.ParsingResult
import com.digitalasset.canton.serialization.{ProtoConverter, ProtocolVersionedMemoizedEvidence}
import com.digitalasset.canton.util.NoCopy
import com.digitalasset.canton.version.*
import com.google.common.annotations.VisibleForTesting
import com.google.protobuf.ByteString

/** Information concerning every '''member''' involved in processing the underlying view.
  */
// This class is a reference example of serialization best practices, demonstrating:
// - memoized serialization, which is required if we need to compute a signature or cryptographic hash of a class
// - use of an UntypedVersionedMessage wrapper when serializing to an anonymous binary format
// Please consult the team if you intend to change the design of serialization.
//
// The constructor and `fromProto...` methods are private to ensure that clients cannot create instances with an incorrect `deserializedFrom` field.
//
// Optional parameters are strongly discouraged, as each parameter needs to be consciously set in a production context.
final case class ViewCommonData private (
    viewConfirmationParameters: ViewConfirmationParameters,
    salt: Salt,
)(
    hashOps: HashOps,
    override val representativeProtocolVersion: RepresentativeProtocolVersion[ViewCommonData.type],
    override val deserializedFrom: Option[ByteString],
) extends MerkleTreeLeaf[ViewCommonData](hashOps)
    // The class needs to implement ProtocolVersionedMemoizedEvidence, because we want that serialize always yields the same ByteString.
    // This is to ensure that different participants compute the same hash after receiving a ViewCommonData over the network.
    // (Recall that serialization is in general not guaranteed to be deterministic.)
    with ProtocolVersionedMemoizedEvidence
    // The class implements `HasProtocolVersionedWrapper` because we serialize it to an anonymous binary format and need to encode
    // the version of the serialized Protobuf message
    with HasProtocolVersionedWrapper[ViewCommonData] {

  // The toProto... methods are deliberately protected, as they could otherwise be abused to bypass memoization.
  //
  // If another serializable class contains a ViewCommonData, it has to include it as a ByteString
  // (and not as "message ViewCommonData") in its ProtoBuf representation.

  @transient override protected lazy val companionObj: ViewCommonData.type = ViewCommonData

  // Ensures the invariants related to default values hold
  validateInstance().valueOr(err => throw new IllegalArgumentException(err))

  private def extractInformeesFromQuorum(quorum: Quorum): Seq[Informee] =
    viewConfirmationParameters.informees.toSeq.map { partyId =>
      quorum.confirmers.get(partyId) match {
        case Some(w) =>
          ConfirmingParty(partyId, PositiveInt.tryCreate(w.weight.unwrap), w.requiredTrustLevel)
        case None => PlainInformee(partyId)
      }
    }

  // We use named parameters, because then the code remains correct even when the ProtoBuf code generator
  // changes the order of parameters.
  protected def toProtoV0: v0.ViewCommonData = {
    // for v0 there is only one quorum
    val quorum = viewConfirmationParameters.quorums(0)
    v0.ViewCommonData(
      informees = extractInformeesFromQuorum(quorum).map(_.toProtoV0),
      threshold = quorum.threshold.unwrap,
      salt = Some(salt.toProtoV0),
    )
  }

  protected def toProtoV1: v1.ViewCommonData = {
    // for v1 there is only one quorum
    val quorum = viewConfirmationParameters.quorums(0)
    v1.ViewCommonData(
      informees = extractInformeesFromQuorum(quorum).map(_.toProtoV1),
      threshold = quorum.threshold.unwrap,
      salt = Some(salt.toProtoV0),
    )
  }

  protected def toProtoV2: v2.ViewCommonData = {
    val informees = viewConfirmationParameters.informees.toSeq
    v2.ViewCommonData(
      informees = informees,
      quorums = viewConfirmationParameters.quorums.map(
        _.tryToProtoV0(informees)
      ),
      salt = Some(salt.toProtoV0),
    )
  }

  // When serializing the class to an anonymous binary format, we serialize it to an UntypedVersionedMessage version of the
  // corresponding Protobuf message
  override protected[this] def toByteStringUnmemoized: ByteString = toByteString

  override val hashPurpose: HashPurpose = HashPurpose.ViewCommonData

  override def pretty: Pretty[ViewCommonData] = prettyOfClass(
    param("view confirmation parameters", _.viewConfirmationParameters),
    param("salt", _.salt),
  )

  @VisibleForTesting
  def copy(
      viewConfirmationParameters: ViewConfirmationParameters = this.viewConfirmationParameters,
      salt: Salt = this.salt,
  ): ViewCommonData =
    ViewCommonData(viewConfirmationParameters, salt)(
      hashOps,
      representativeProtocolVersion,
      None,
    )
}

object ViewCommonData
    extends HasMemoizedProtocolVersionedWithContextCompanion[
      ViewCommonData,
      (HashOps, ConfirmationPolicy),
    ] {
  override val name: String = "ViewCommonData"

  // up until [[ProtocolVersion.v6]] there is only one quorum
  override lazy val invariants = Seq(
    OneElementSeqExactlyUntilExclusive(
      _.viewConfirmationParameters.quorums,
      "viewConfirmationParameters.quorums",
      protocolVersionRepresentativeFor(ProtocolVersion.v6),
    )
  )

  val supportedProtoVersions: SupportedProtoVersions =
    SupportedProtoVersions(
      ProtoVersion(0) -> VersionedProtoConverter(ProtocolVersion.v3)(v0.ViewCommonData)(
        supportedProtoVersionMemoized(_)(fromProtoV0),
        _.toProtoV0.toByteString,
      ),
      ProtoVersion(1) -> VersionedProtoConverter(ProtocolVersion.v5)(v1.ViewCommonData)(
        supportedProtoVersionMemoized(_)(fromProtoV1),
        _.toProtoV1.toByteString,
      ),
      ProtoVersion(2) -> VersionedProtoConverter(ProtocolVersion.v6)(v2.ViewCommonData)(
        supportedProtoVersionMemoized(_)(fromProtoV2),
        _.toProtoV2.toByteString,
      ),
    )

  /** Creates a fresh [[ViewCommonData]]. */
  // The "create" method has the following advantages over the auto-generated "apply" method:
  // - The parameter lists have been flipped to facilitate curried usages.
  // - The deserializedFrom field cannot be set; so it cannot be set incorrectly.
  //
  // The method is called "create" instead of "apply"
  // to not confuse the Idea compiler by overloading "apply".
  // (This is not a problem with this particular class, but it has been a problem with other classes.)
  def create(hashOps: HashOps)(
      viewConfirmationParameters: ViewConfirmationParameters,
      salt: Salt,
      protocolVersion: ProtocolVersion,
  ): Either[InvalidViewConfirmationParameters, ViewCommonData] =
    Either
      .catchOnly[IllegalArgumentException] {
        // The deserializedFrom field is set to "None" as this is for creating "fresh" instances.
        new ViewCommonData(viewConfirmationParameters, salt)(
          hashOps,
          protocolVersionRepresentativeFor(protocolVersion),
          None,
        )
      }
      .leftMap(e => InvalidViewConfirmationParameters(e.getMessage))

  def tryCreate(hashOps: HashOps)(
      viewConfirmationParameters: ViewConfirmationParameters,
      salt: Salt,
      protocolVersion: ProtocolVersion,
  ): ViewCommonData =
    create(hashOps)(viewConfirmationParameters, salt, protocolVersion)
      .valueOr(err => throw new IllegalArgumentException(err))

  private def fromProtoV0(
      context: (HashOps, ConfirmationPolicy),
      viewCommonDataP: v0.ViewCommonData,
  )(bytes: ByteString): ParsingResult[ViewCommonData] = {
    val (hashOps, confirmationPolicy) = context
    for {
      informees <- viewCommonDataP.informees
        .traverse(Informee.fromProtoV0(confirmationPolicy))
      salt <- ProtoConverter
        .parseRequired(Salt.fromProtoV0, "salt", viewCommonDataP.salt)
        .leftMap(_.inField("salt"))
      threshold <- NonNegativeInt
        .create(viewCommonDataP.threshold)
        .leftMap(InvariantViolation.toProtoDeserializationError)
        .leftMap(_.inField("threshold"))
      rpv <- protocolVersionRepresentativeFor(ProtoVersion(0))
    } yield new ViewCommonData(
      ViewConfirmationParameters.create(informees.toSet, threshold),
      salt,
    )(
      hashOps,
      rpv,
      Some(bytes),
    )
  }

  private def fromProtoV1(
      context: (HashOps, ConfirmationPolicy),
      viewCommonDataP: v1.ViewCommonData,
  )(bytes: ByteString): ParsingResult[ViewCommonData] = {
    val (hashOps, _) = context
    for {
      informees <- viewCommonDataP.informees.traverse(Informee.fromProtoV1)
      salt <- ProtoConverter
        .parseRequired(Salt.fromProtoV0, "salt", viewCommonDataP.salt)
        .leftMap(_.inField("salt"))
      threshold <- NonNegativeInt
        .create(viewCommonDataP.threshold)
        .leftMap(InvariantViolation.toProtoDeserializationError)
        .leftMap(_.inField("threshold"))
      rpv <- protocolVersionRepresentativeFor(ProtoVersion(1))
    } yield {
      new ViewCommonData(
        ViewConfirmationParameters.create(informees.toSet, threshold),
        salt,
      )(
        hashOps,
        rpv,
        Some(bytes),
      )
    }
  }

  def fromProtoV2(
      context: (HashOps, ConfirmationPolicy),
      viewCommonDataP: v2.ViewCommonData,
  )(bytes: ByteString): ParsingResult[ViewCommonData] = {
    val (hashOps, _) = context
    for {
      informees <- viewCommonDataP.informees.traverse(informee =>
        ProtoConverter.parseLfPartyId(informee)
      )
      salt <- ProtoConverter
        .parseRequired(Salt.fromProtoV0, "salt", viewCommonDataP.salt)
        .leftMap(_.inField("salt"))
      quorums <- viewCommonDataP.quorums.traverse(Quorum.fromProtoV0(_, informees))
      rpv <- protocolVersionRepresentativeFor(ProtoVersion(2))
      viewConfirmationParameters <- ViewConfirmationParameters.create(informees.toSet, quorums)
    } yield new ViewCommonData(
      viewConfirmationParameters,
      salt,
    )(
      hashOps,
      rpv,
      Some(bytes),
    )
  }
}

/** Stores the necessary information necessary to confirm a view.
  *
  * @param informees list of all members ids that must be informed of this view.
  * @param quorums multiple lists of confirmers => threshold (i.e., a quorum) that needs
  *               to be met for the view to be approved. We make sure that the parties listed
  *               in the quorums are informees of the view during
  *               deserialization.
  */
final case class ViewConfirmationParameters private (
    informees: Set[LfPartyId],
    quorums: Seq[Quorum],
) extends PrettyPrinting
    with NoCopy {

  override def pretty: Pretty[ViewConfirmationParameters] = prettyOfClass(
    param("informees", _.informees.toSet),
    param("quorums", _.quorums),
  )

  def confirmers: Set[ConfirmingParty] = quorums.flatMap(_.getConfirmingParties).toSet
}

object ViewConfirmationParameters {

  /** Indicates an attempt to create an invalid [[ViewConfirmationParameters]]. */
  final case class InvalidViewConfirmationParameters(message: String)
      extends RuntimeException(message)

  /** Until protocol version [[com.digitalasset.canton.version.ProtocolVersion.v5]]
    * there is ONLY ONE QUORUM containing all confirming parties from the list of informees and a threshold.
    */
  def create(
      informees: Set[Informee],
      threshold: NonNegativeInt,
  ): ViewConfirmationParameters =
    ViewConfirmationParameters(
      informees.map(_.party),
      Seq(
        Quorum(
          informees.collect { case c: ConfirmingParty =>
            c.party -> WeightAndTrustLevel(
              PositiveInt.tryCreate(c.weight.unwrap),
              c.requiredTrustLevel,
            )
          }.toMap,
          threshold,
        )
      ),
    )

  /** Starting from protocol version [[com.digitalasset.canton.version.ProtocolVersion.v6]]
    * there can be multiple quorums/threshold. Therefore, we need to make sure those quorums confirmers
    * are present in the list of informees.
    */
  def create(
      informees: Set[LfPartyId],
      quorums: Seq[Quorum],
  ): Either[InvariantViolation, ViewConfirmationParameters] = {
    val allConfirmers = quorums.flatMap(_.confirmers.keys).toSeq
    val notAnInformee = allConfirmers.filterNot(informees.contains)
    Either.cond(
      notAnInformee.isEmpty,
      ViewConfirmationParameters(informees, quorums),
      InvariantViolation(s"confirming parties $notAnInformee are not in the list of informees"),
    )
  }

  def tryCreate(
      informees: Set[LfPartyId],
      quorums: Seq[Quorum],
  ): ViewConfirmationParameters =
    create(informees, quorums).valueOr(err => throw InvalidViewConfirmationParameters(err.toString))

}