// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.data

import cats.syntax.either.*
import cats.syntax.traverse.*
import com.digitalasset.canton.ProtoDeserializationError.OtherError
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.crypto.*
import com.digitalasset.canton.data.MerkleTree.RevealSubtree
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.protocol.messages.{
  AssignmentMediatorMessage,
  DeliveredUnassignmentResult,
}
import com.digitalasset.canton.protocol.{v30, *}
import com.digitalasset.canton.sequencing.protocol.{
  MediatorGroupRecipient,
  NoOpeningErrors,
  SequencedEvent,
  SignedContent,
}
import com.digitalasset.canton.serialization.ProtoConverter.ParsingResult
import com.digitalasset.canton.serialization.{ProtoConverter, ProtocolVersionedMemoizedEvidence}
import com.digitalasset.canton.topology.{DomainId, ParticipantId}
import com.digitalasset.canton.util.EitherUtil
import com.digitalasset.canton.version.Reassignment.{SourceProtocolVersion, TargetProtocolVersion}
import com.digitalasset.canton.version.*
import com.digitalasset.canton.{LfPartyId, LfWorkflowId, ReassignmentCounter}
import com.google.protobuf.ByteString

import java.util.UUID

/** an assignment request embedded in a Merkle tree. The view may or may not be blinded. */
final case class AssignmentViewTree(
    commonData: MerkleTreeLeaf[AssignmentCommonData],
    view: MerkleTree[AssignmentView],
)(
    override val representativeProtocolVersion: RepresentativeProtocolVersion[
      AssignmentViewTree.type
    ],
    hashOps: HashOps,
) extends GenReassignmentViewTree[
      AssignmentCommonData,
      AssignmentView,
      AssignmentViewTree,
      AssignmentMediatorMessage,
    ](commonData, view)(hashOps)
    with HasProtocolVersionedWrapper[AssignmentViewTree] {

  def submittingParticipant: ParticipantId =
    commonData.tryUnwrap.submitterMetadata.submittingParticipant

  override private[data] def withBlindedSubtrees(
      optimizedBlindingPolicy: PartialFunction[RootHash, MerkleTree.BlindingCommand]
  ): MerkleTree[AssignmentViewTree] = {

    if (
      optimizedBlindingPolicy.applyOrElse(
        commonData.rootHash,
        (_: RootHash) => RevealSubtree,
      ) != RevealSubtree
    )
      throw new IllegalArgumentException("Blinding of common data is not supported.")

    AssignmentViewTree(
      commonData,
      view.doBlind(optimizedBlindingPolicy),
    )(representativeProtocolVersion, hashOps)
  }

  protected[this] override def createMediatorMessage(
      blindedTree: AssignmentViewTree,
      submittingParticipantSignature: Signature,
  ): AssignmentMediatorMessage =
    AssignmentMediatorMessage(blindedTree, submittingParticipantSignature)

  override def pretty: Pretty[AssignmentViewTree] = prettyOfClass(
    param("common data", _.commonData),
    param("view", _.view),
  )

  @transient override protected lazy val companionObj: AssignmentViewTree.type =
    AssignmentViewTree
}

object AssignmentViewTree
    extends HasProtocolVersionedWithContextAndValidationWithTargetProtocolVersionCompanion[
      AssignmentViewTree,
      HashOps,
    ] {

  override val name: String = "AssignmentViewTree"

  val supportedProtoVersions = SupportedProtoVersions(
    ProtoVersion(30) -> VersionedProtoConverter(ProtocolVersion.v32)(v30.ReassignmentViewTree)(
      supportedProtoVersion(_)((context, proto) => fromProtoV30(context)(proto)),
      _.toProtoV30.toByteString,
    )
  )

  def apply(
      commonData: MerkleTreeLeaf[AssignmentCommonData],
      view: MerkleTree[AssignmentView],
      targetProtocolVersion: TargetProtocolVersion,
      hashOps: HashOps,
  ): AssignmentViewTree =
    AssignmentViewTree(commonData, view)(
      AssignmentViewTree.protocolVersionRepresentativeFor(targetProtocolVersion.v),
      hashOps,
    )

  def fromProtoV30(context: (HashOps, TargetProtocolVersion))(
      assignmentViewTreeP: v30.ReassignmentViewTree
  ): ParsingResult[AssignmentViewTree] = {
    val (hashOps, expectedProtocolVersion) = context
    for {
      rpv <- protocolVersionRepresentativeFor(ProtoVersion(30))
      res <- GenReassignmentViewTree.fromProtoV30(
        AssignmentCommonData.fromByteString(expectedProtocolVersion.v)(
          (hashOps, expectedProtocolVersion)
        ),
        AssignmentView.fromByteString(expectedProtocolVersion.v)(hashOps),
      )((commonData, view) =>
        AssignmentViewTree(commonData, view)(
          rpv,
          hashOps,
        )
      )(assignmentViewTreeP)
    } yield res
  }
}

/** Aggregates the data of an assignment request that is sent to the mediator and the involved participants.
  *
  * @param salt Salt for blinding the Merkle hash
  * @param targetDomain The domain on which the contract is assigned
  * @param targetMediatorGroup The mediator that coordinates the assignment request on the target domain
  * @param stakeholders The stakeholders of the reassigned contract
  * @param uuid The uuid of the assignment request
  * @param submitterMetadata information about the submission
  */
final case class AssignmentCommonData private (
    override val salt: Salt,
    targetDomain: TargetDomainId,
    targetMediatorGroup: MediatorGroupRecipient,
    stakeholders: Set[LfPartyId],
    uuid: UUID,
    submitterMetadata: ReassignmentSubmitterMetadata,
)(
    hashOps: HashOps,
    val targetProtocolVersion: TargetProtocolVersion,
    override val deserializedFrom: Option[ByteString],
) extends MerkleTreeLeaf[AssignmentCommonData](hashOps)
    with HasProtocolVersionedWrapper[AssignmentCommonData]
    with ProtocolVersionedMemoizedEvidence {

  @transient override protected lazy val companionObj: AssignmentCommonData.type =
    AssignmentCommonData

  override val representativeProtocolVersion
      : RepresentativeProtocolVersion[AssignmentCommonData.type] =
    AssignmentCommonData.protocolVersionRepresentativeFor(targetProtocolVersion.v)

  protected def toProtoV30: v30.AssignmentCommonData =
    v30.AssignmentCommonData(
      salt = Some(salt.toProtoV30),
      targetDomain = targetDomain.toProtoPrimitive,
      targetMediatorGroup = targetMediatorGroup.group.value,
      stakeholders = stakeholders.toSeq,
      uuid = ProtoConverter.UuidConverter.toProtoPrimitive(uuid),
      submitterMetadata = Some(submitterMetadata.toProtoV30),
    )

  override protected[this] def toByteStringUnmemoized: ByteString =
    super[HasProtocolVersionedWrapper].toByteString

  override def hashPurpose: HashPurpose = HashPurpose.AssignmentCommonData

  def confirmingParties: Map[LfPartyId, PositiveInt] =
    stakeholders.map(_ -> PositiveInt.one).toMap

  override def pretty: Pretty[AssignmentCommonData] = prettyOfClass(
    param("submitter metadata", _.submitterMetadata),
    param("target domain", _.targetDomain),
    param("target mediator group", _.targetMediatorGroup),
    param("stakeholders", _.stakeholders),
    param("uuid", _.uuid),
    param("salt", _.salt),
  )
}

object AssignmentCommonData
    extends HasMemoizedProtocolVersionedWithContextCompanion[
      AssignmentCommonData,
      (HashOps, TargetProtocolVersion),
    ] {
  override val name: String = "AssignmentCommonData"

  val supportedProtoVersions = SupportedProtoVersions(
    ProtoVersion(30) -> VersionedProtoConverter(ProtocolVersion.v32)(v30.AssignmentCommonData)(
      supportedProtoVersionMemoized(_)(fromProtoV30),
      _.toProtoV30.toByteString,
    )
  )

  def create(hashOps: HashOps)(
      salt: Salt,
      targetDomain: TargetDomainId,
      targetMediator: MediatorGroupRecipient,
      stakeholders: Set[LfPartyId],
      uuid: UUID,
      submitterMetadata: ReassignmentSubmitterMetadata,
      targetProtocolVersion: TargetProtocolVersion,
  ): AssignmentCommonData = AssignmentCommonData(
    salt,
    targetDomain,
    targetMediator,
    stakeholders,
    uuid,
    submitterMetadata,
  )(hashOps, targetProtocolVersion, None)

  private[this] def fromProtoV30(
      context: (HashOps, TargetProtocolVersion),
      assignmentCommonDataP: v30.AssignmentCommonData,
  )(
      bytes: ByteString
  ): ParsingResult[AssignmentCommonData] = {
    val (hashOps, targetProtocolVersion) = context
    val v30.AssignmentCommonData(
      saltP,
      targetDomainP,
      stakeholdersP,
      uuidP,
      targetMediatorGroupP,
      submitterMetadataPO,
    ) = assignmentCommonDataP

    for {
      salt <- ProtoConverter.parseRequired(Salt.fromProtoV30, "salt", saltP)
      targetDomain <- TargetDomainId.fromProtoPrimitive(targetDomainP, "target_domain")
      targetMediatorGroup <- ProtoConverter.parseNonNegativeInt(
        "target_mediator_group",
        targetMediatorGroupP,
      )
      stakeholders <- stakeholdersP.traverse(ProtoConverter.parseLfPartyId)
      uuid <- ProtoConverter.UuidConverter.fromProtoPrimitive(uuidP)
      submitterMetadata <- ProtoConverter
        .required("submitter_metadata", submitterMetadataPO)
        .flatMap(ReassignmentSubmitterMetadata.fromProtoV30)

    } yield AssignmentCommonData(
      salt,
      targetDomain,
      MediatorGroupRecipient(targetMediatorGroup),
      stakeholders.toSet,
      uuid,
      submitterMetadata,
    )(hashOps, targetProtocolVersion, Some(bytes))
  }
}

/** Aggregates the data of an assignment request that is only sent to the involved participants
  *
  * @param salt                    The salt to blind the Merkle hash
  * @param contract                The contract to be reassigned including the instance
  * @param creatingTransactionId   The id of the transaction that created the contract
  * @param unassignmentResultEvent The signed deliver event of the unassignment result message
  * @param sourceProtocolVersion   Protocol version of the source domain.
  * @param reassignmentCounter     The [[com.digitalasset.canton.ReassignmentCounter]] of the contract.
  */
final case class AssignmentView private (
    override val salt: Salt,
    contract: SerializableContract,
    creatingTransactionId: TransactionId,
    unassignmentResultEvent: DeliveredUnassignmentResult,
    sourceProtocolVersion: SourceProtocolVersion,
    reassignmentCounter: ReassignmentCounter,
)(
    hashOps: HashOps,
    override val representativeProtocolVersion: RepresentativeProtocolVersion[AssignmentView.type],
    override val deserializedFrom: Option[ByteString],
) extends MerkleTreeLeaf[AssignmentView](hashOps)
    with HasProtocolVersionedWrapper[AssignmentView]
    with ProtocolVersionedMemoizedEvidence {

  @transient override protected lazy val companionObj: AssignmentView.type = AssignmentView

  override protected[this] def toByteStringUnmemoized: ByteString =
    super[HasProtocolVersionedWrapper].toByteString

  def hashPurpose: HashPurpose = HashPurpose.AssignmentView

  protected def toProtoV30: v30.AssignmentView =
    v30.AssignmentView(
      salt = Some(salt.toProtoV30),
      contract = Some(contract.toProtoV30),
      creatingTransactionId = creatingTransactionId.toProtoPrimitive,
      unassignmentResultEvent = unassignmentResultEvent.result.toByteString,
      sourceProtocolVersion = sourceProtocolVersion.v.toProtoPrimitive,
      reassignmentCounter = reassignmentCounter.toProtoPrimitive,
    )

  override def pretty: Pretty[AssignmentView] = prettyOfClass(
    param("creating transaction id", _.creatingTransactionId),
    param("unassignment result event", _.unassignmentResultEvent),
    param("source protocol version", _.sourceProtocolVersion.v),
    param("reassignment counter", _.reassignmentCounter),
    param(
      "contract id",
      _.contract.contractId,
    ), // do not log contract details because it contains confidential data
    param("salt", _.salt),
  )
}

object AssignmentView
    extends HasMemoizedProtocolVersionedWithContextCompanion[AssignmentView, HashOps] {
  override val name: String = "AssignmentView"

  val supportedProtoVersions = SupportedProtoVersions(
    ProtoVersion(30) -> VersionedProtoConverter(ProtocolVersion.v32)(v30.AssignmentView)(
      supportedProtoVersionMemoized(_)(fromProtoV30),
      _.toProtoV30.toByteString,
    )
  )

  def create(hashOps: HashOps)(
      salt: Salt,
      contract: SerializableContract,
      creatingTransactionId: TransactionId,
      unassignmentResultEvent: DeliveredUnassignmentResult,
      sourceProtocolVersion: SourceProtocolVersion,
      targetProtocolVersion: TargetProtocolVersion,
      reassignmentCounter: ReassignmentCounter,
  ): Either[String, AssignmentView] = Either
    .catchOnly[IllegalArgumentException](
      AssignmentView(
        salt,
        contract,
        creatingTransactionId,
        unassignmentResultEvent,
        sourceProtocolVersion,
        reassignmentCounter,
      )(hashOps, protocolVersionRepresentativeFor(targetProtocolVersion.v), None)
    )
    .leftMap(_.getMessage)

  private[this] def fromProtoV30(hashOps: HashOps, assignmentViewP: v30.AssignmentView)(
      bytes: ByteString
  ): ParsingResult[AssignmentView] = {
    val v30.AssignmentView(
      saltP,
      contractP,
      unassignmentResultEventP,
      creatingTransactionIdP,
      sourceProtocolVersionP,
      reassignmentCounterP,
    ) =
      assignmentViewP
    for {
      protocolVersion <- ProtocolVersion.fromProtoPrimitive(sourceProtocolVersionP)
      sourceProtocolVersion = SourceProtocolVersion(protocolVersion)
      commonData <- CommonData.fromProto(
        hashOps,
        saltP,
        unassignmentResultEventP,
        creatingTransactionIdP,
        sourceProtocolVersion,
      )
      contract <- ProtoConverter
        .required("contract", contractP)
        .flatMap(SerializableContract.fromProtoV30)
      rpv <- protocolVersionRepresentativeFor(ProtoVersion(30))
    } yield AssignmentView(
      commonData.salt,
      contract,
      commonData.creatingTransactionId,
      commonData.unassignmentResultEvent,
      commonData.sourceProtocolVersion,
      ReassignmentCounter(reassignmentCounterP),
    )(hashOps, rpv, Some(bytes))
  }

  private[AssignmentView] final case class CommonData(
      salt: Salt,
      creatingTransactionId: TransactionId,
      unassignmentResultEvent: DeliveredUnassignmentResult,
      sourceProtocolVersion: SourceProtocolVersion,
  )

  private[AssignmentView] object CommonData {
    def fromProto(
        hashOps: HashOps,
        saltP: Option[com.digitalasset.canton.crypto.v30.Salt],
        unassignmentResultEventP: ByteString,
        creatingTransactionIdP: ByteString,
        sourceProtocolVersion: SourceProtocolVersion,
    ): ParsingResult[CommonData] =
      for {
        salt <- ProtoConverter.parseRequired(Salt.fromProtoV30, "salt", saltP)
        // UnassignmentResultEvent deserialization
        unassignmentResultEventMC <- SignedContent
          .fromByteString(sourceProtocolVersion.v)(unassignmentResultEventP)
          .flatMap(
            _.deserializeContent(
              SequencedEvent.fromByteStringOpen(hashOps, sourceProtocolVersion.v)
            )
          )
        unassignmentResultEvent <- DeliveredUnassignmentResult
          .create(NoOpeningErrors(unassignmentResultEventMC))
          .leftMap(err => OtherError(err.toString))
        creatingTransactionId <- TransactionId.fromProtoPrimitive(creatingTransactionIdP)
      } yield CommonData(
        salt,
        creatingTransactionId,
        unassignmentResultEvent,
        sourceProtocolVersion,
      )
  }
}

/** A fully unblinded [[AssignmentViewTree]]
  *
  * @throws java.lang.IllegalArgumentException if the [[tree]] is not fully unblinded
  */
final case class FullAssignmentTree(tree: AssignmentViewTree)
    extends ReassignmentViewTree
    with HasToByteString
    with PrettyPrinting {
  require(tree.isFullyUnblinded, "an assignment request must be fully unblinded")

  private[this] val commonData = tree.commonData.tryUnwrap
  private[this] val view = tree.view.tryUnwrap

  def submitterMetadata: ReassignmentSubmitterMetadata = commonData.submitterMetadata

  def submitter: LfPartyId = submitterMetadata.submitter

  def workflowId: Option[LfWorkflowId] = submitterMetadata.workflowId

  def stakeholders: Set[LfPartyId] = commonData.stakeholders

  def contract: SerializableContract = view.contract

  def reassignmentCounter: ReassignmentCounter = view.reassignmentCounter

  def creatingTransactionId: TransactionId = view.creatingTransactionId

  def unassignmentResultEvent: DeliveredUnassignmentResult = view.unassignmentResultEvent

  def mediatorMessage(
      submittingParticipantSignature: Signature
  ): AssignmentMediatorMessage = tree.mediatorMessage(submittingParticipantSignature)

  def targetDomain: TargetDomainId = commonData.targetDomain

  override def domainId: DomainId = commonData.targetDomain.unwrap

  override def mediator: MediatorGroupRecipient = commonData.targetMediatorGroup

  override def informees: Set[LfPartyId] = commonData.confirmingParties.keySet

  override def toBeSigned: Option[RootHash] = Some(tree.rootHash)

  override def viewHash: ViewHash = tree.viewHash

  override def rootHash: RootHash = tree.rootHash

  override def isReassigningParticipant(participantId: ParticipantId): Boolean =
    unassignmentResultEvent.unwrap.informees.contains(participantId.adminParty.toLf)

  override def pretty: Pretty[FullAssignmentTree] = prettyOfClass(unnamedParam(_.tree))

  override def toByteString: ByteString = tree.toByteString
}

object FullAssignmentTree {
  def fromByteString(
      crypto: CryptoPureApi,
      targetProtocolVersion: TargetProtocolVersion,
  )(bytes: ByteString): ParsingResult[FullAssignmentTree] =
    for {
      tree <- AssignmentViewTree.fromByteString(crypto, targetProtocolVersion)(bytes)
      _ <- EitherUtil.condUnitE(
        tree.isFullyUnblinded,
        OtherError(s"Assignment request ${tree.rootHash} is not fully unblinded"),
      )
    } yield FullAssignmentTree(tree)
}
