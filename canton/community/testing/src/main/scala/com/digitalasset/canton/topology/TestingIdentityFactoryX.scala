// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.topology

import cats.data.EitherT
import cats.syntax.functor.*
import com.daml.lf.data.Ref.PackageId
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.BaseTest.testedReleaseProtocolVersion
import com.digitalasset.canton.config.DefaultProcessingTimeouts
import com.digitalasset.canton.config.RequireTypes.{NonNegativeInt, PositiveInt}
import com.digitalasset.canton.crypto.*
import com.digitalasset.canton.crypto.provider.symbolic.{SymbolicCrypto, SymbolicPureCrypto}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.protocol.{
  DomainParameters,
  DynamicDomainParameters,
  TestDomainParameters,
}
import com.digitalasset.canton.time.NonNegativeFiniteDuration
import com.digitalasset.canton.topology.DefaultTestIdentities.*
import com.digitalasset.canton.topology.client.*
import com.digitalasset.canton.topology.processing.{EffectiveTime, SequencedTime}
import com.digitalasset.canton.topology.store.memory.InMemoryTopologyStoreX
import com.digitalasset.canton.topology.store.{TopologyStoreId, ValidatedTopologyTransactionX}
import com.digitalasset.canton.topology.transaction.*
import com.digitalasset.canton.tracing.{NoTracing, TraceContext}
import com.digitalasset.canton.util.MapsUtil
import com.digitalasset.canton.{BaseTest, LfPackageId, LfPartyId}

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}

/** Utility functions to setup identity & crypto apis for testing purposes
  *
  * You are trying to figure out how to setup identity topologies and crypto apis to drive your unit tests?
  * Then YOU FOUND IT! The present file contains everything that you should need.
  *
  * First, let's re-call that we are abstracting the identity and crypto aspects from the transaction protocol.
  *
  * Therefore, all the key crypto operations hide behind the so-called crypto-api which splits into the
  * pure part [[CryptoPureApi]] and the more complicated part, the [[SyncCryptoApi]] such that from the transaction
  * protocol perspective, we can conveniently use methods like [[SyncCryptoApi.sign]] or [[SyncCryptoApi.encryptFor]]
  *
  * The abstraction creates the following hierarchy of classes to resolve the state for a given [[KeyOwner]]
  * on a per (domainId, timestamp)
  *
  * SyncCryptoApiProvider - root object that makes the synchronisation topology state known to a node accessible
  *   .forDomain          - method to get the specific view on a per domain basis
  * = DomainSyncCryptoApi
  *   .snapshot(timestamp) | recentState - method to get the view for a specific time
  * = DomainSnapshotSyncCryptoApi (extends SyncCryptoApi)
  *
  * All these object carry the necessary objects ([[CryptoPureApi]], [[TopologySnapshot]], [[KeyVaultApi]])
  * as arguments with them.
  *
  * Now, in order to conveniently create a static topology for testing, we provide a
  * <ul>
  *   <li>[[TestingTopologyX]] which allows us to define a certain static topology</li>
  *   <li>[[TestingIdentityFactory]] which consumes a static topology and delivers all necessary components and
  *       objects that a unit test might need.</li>
  *   <li>[[DefaultTestIdentities]] which provides a predefined set of identities that can be used for unit tests.</li>
  * </ul>
  *
  * Common usage patterns are:
  * <ul>
  *   <li>Get a [[DomainSyncCryptoClient]] with an empty topology: `TestingIdentityFactory().forOwnerAndDomain(participant1)`</li>
  *   <li>To get a [[DomainSnapshotSyncCryptoApi]]: same as above, just add `.recentState`.</li>
  *   <li>Define a specific topology and get the [[SyncCryptoApiProvider]]: `TestingTopologyX().withTopology(Map(party1 -> participant1)).build()`.</li>
  * </ul>
  *
  * @param domains Set of domains for which the topology is valid
  * @param topology Static association of parties to participants in the most complete way it can be defined in this testing class.
  * @param participants participants for which keys should be added.
  *                     A participant mentioned in `topology` will be included automatically in the topology state,
  *                     so such a participant does not need to be declared again.
  *                     If a participant occurs both in `topology` and `participants`, the attributes of `participants` have higher precedence.
  * @param keyPurposes The purposes of the keys that will be generated.
  */
final case class TestingTopologyX(
    domains: Set[DomainId] = Set(DefaultTestIdentities.domainId),
    topology: Map[LfPartyId, Map[ParticipantId, ParticipantPermission]] = Map.empty,
    mediatorGroups: Set[MediatorGroup] = Set(
      MediatorGroup(
        NonNegativeInt.zero,
        Seq(DefaultTestIdentities.mediatorIdX),
        Seq(),
        PositiveInt.one,
      )
    ),
    sequencerGroup: SequencerGroup = SequencerGroup(
      active = Seq(DefaultTestIdentities.sequencerIdX),
      passive = Seq.empty,
      threshold = PositiveInt.one,
    ),
    participants: Map[ParticipantId, ParticipantAttributes] = Map.empty,
    packages: Seq[LfPackageId] = Seq.empty,
    keyPurposes: Set[KeyPurpose] = KeyPurpose.all,
    domainParameters: List[DomainParameters.WithValidity[DynamicDomainParameters]] = List(
      DomainParameters.WithValidity(
        validFrom = CantonTimestamp.Epoch,
        validUntil = None,
        parameter = DefaultTestIdentities.defaultDynamicDomainParameters,
      )
    ),
) {
  def mediators: Seq[MediatorId] = mediatorGroups.toSeq.flatMap(_.all)

  /** Define for which domains the topology should apply.
    *
    * All domains will have exactly the same topology.
    */
  def withDomains(domains: DomainId*): TestingTopologyX = this.copy(domains = domains.toSet)

  /** Overwrites the `sequencerGroup` field.
    */
  def withSequencerGroup(
      sequencerGroup: SequencerGroup
  ): TestingTopologyX =
    this.copy(sequencerGroup = sequencerGroup)

  /** Overwrites the `participants` parameter while setting attributes to Submission / Ordinary.
    */
  def withSimpleParticipants(
      participants: ParticipantId*
  ): TestingTopologyX =
    this.copy(participants =
      participants
        .map(_ -> ParticipantAttributes(ParticipantPermission.Submission, TrustLevel.Ordinary))
        .toMap
    )

  /** Overwrites the `participants` parameter.
    */
  def withParticipants(
      participants: (ParticipantId, ParticipantAttributes)*
  ): TestingTopologyX =
    this.copy(participants = participants.toMap)

  def allParticipants(): Set[ParticipantId] = {
    (topology.values
      .flatMap(x => x.keys) ++ participants.keys).toSet
  }

  def withKeyPurposes(keyPurposes: Set[KeyPurpose]): TestingTopologyX =
    this.copy(keyPurposes = keyPurposes)

  /** Define the topology as a simple map of party to participant */
  def withTopology(
      parties: Map[LfPartyId, ParticipantId],
      permission: ParticipantPermission = ParticipantPermission.Submission,
  ): TestingTopologyX = {
    val tmp: Map[LfPartyId, Map[ParticipantId, ParticipantPermission]] = parties.toSeq
      .map { case (party, participant) =>
        (party, (participant, permission))
      }
      .groupBy(_._1)
      .fmap(res => res.map(_._2).toMap)
    this.copy(topology = tmp)
  }

  /** Define the topology as a map of participant to map of parties */
  def withReversedTopology(
      parties: Map[ParticipantId, Map[LfPartyId, ParticipantPermission]]
  ): TestingTopologyX = {
    val converted = parties
      .flatMap { case (participantId, partyToPermission) =>
        partyToPermission.toSeq.map { case (party, permission) =>
          (party, participantId, permission)
        }
      }
      .groupBy(_._1)
      .fmap(_.map { case (_, pid, permission) =>
        (pid, permission)
      }.toMap)
    copy(topology = converted)
  }

  def withPackages(packages: Seq[LfPackageId]): TestingTopologyX = this.copy(packages = packages)

  def build(
      loggerFactory: NamedLoggerFactory = NamedLoggerFactory("test-area", "crypto")
  ): TestingIdentityFactoryX =
    new TestingIdentityFactoryX(this, loggerFactory, domainParameters)
}

class TestingIdentityFactoryX(
    topology: TestingTopologyX,
    override protected val loggerFactory: NamedLoggerFactory,
    dynamicDomainParameters: List[DomainParameters.WithValidity[DynamicDomainParameters]],
) extends TestingIdentityFactoryBase
    with NamedLogging {

  private val defaultProtocolVersion = BaseTest.testedProtocolVersion

  override protected def domains: Set[DomainId] = topology.domains

  def topologySnapshot(
      domainId: DomainId = DefaultTestIdentities.domainId,
      packageDependencies: PackageId => EitherT[Future, PackageId, Set[PackageId]] =
        StoreBasedDomainTopologyClient.NoPackageDependencies,
      timestampForDomainParameters: CantonTimestamp = CantonTimestamp.Epoch,
  ): TopologySnapshot = {

    val store = new InMemoryTopologyStoreX(
      TopologyStoreId.AuthorizedStore,
      loggerFactory,
    )

    // Compute default participant permissions to be the highest granted to an individual party
    val defaultPermissionByParticipant: Map[ParticipantId, ParticipantPermission] =
      topology.topology.foldLeft(Map.empty[ParticipantId, ParticipantPermission]) {
        case (acc, (_, permissionByParticipant)) =>
          MapsUtil.extendedMapWith(acc, permissionByParticipant)(ParticipantPermission.higherOf)
      }

    val participantTxs = participantsTxs(defaultPermissionByParticipant, topology.packages)

    val domainMembers =
      (Seq[Member](
        SequencerId(domainId)
      ) ++ topology.mediators.toSeq)
        .flatMap(m => genKeyCollection(m))

    val mediatorOnboarding = topology.mediatorGroups.map(group =>
      mkAdd(
        MediatorDomainStateX
          .create(
            domainId,
            group = group.index,
            threshold = group.threshold,
            active = group.active,
            observers = group.passive,
          )
          .getOrElse(sys.error("creating MediatorDomainStateX should not have failed"))
      )
    )

    val sequencerOnboarding =
      mkAdd(
        SequencerDomainStateX
          .create(
            domainId,
            threshold = topology.sequencerGroup.threshold,
            active = topology.sequencerGroup.active,
            observers = topology.sequencerGroup.passive,
          )
          .getOrElse(sys.error("creating SequencerDomainStateX should not have failed"))
      )

    val partyDataTx = partyToParticipantTxs()

    val domainGovernanceTxs = List(
      mkAdd(
        DomainParametersStateX(domainId, domainParametersChangeTx(timestampForDomainParameters))
      )
    )

    val updateF = store.update(
      SequencedTime(CantonTimestamp.Epoch.immediatePredecessor),
      EffectiveTime(CantonTimestamp.Epoch.immediatePredecessor),
      removeMapping = Set.empty,
      removeTxs = Set.empty,
      additions = (participantTxs ++
        domainMembers ++
        mediatorOnboarding ++
        Seq(sequencerOnboarding) ++
        partyDataTx ++
        domainGovernanceTxs)
        .map(ValidatedTopologyTransactionX(_, rejectionReason = None)),
    )(TraceContext.empty)
    Await.result(
      updateF,
      1.seconds,
    ) // The in-memory topology store should complete the state update immediately

    new StoreBasedTopologySnapshotX(
      CantonTimestamp.Epoch,
      store,
      packageDependencies,
      loggerFactory,
    )
  }

  private def domainParametersChangeTx(ts: CantonTimestamp): DynamicDomainParameters =
    dynamicDomainParameters.collect { case dp if dp.isValidAt(ts) => dp.parameter } match {
      case dp :: Nil => dp
      case Nil =>
        DynamicDomainParameters.initialValues(
          NonNegativeFiniteDuration.Zero,
          BaseTest.testedProtocolVersion,
        )
      case _ => throw new IllegalStateException(s"Multiple domain parameters are valid at $ts")
    }

  private val signedTxProtocolRepresentative =
    SignedTopologyTransactionX.protocolVersionRepresentativeFor(defaultProtocolVersion)

  private def mkAdd(
      mapping: TopologyMappingX,
      serial: PositiveInt = PositiveInt.one,
      isProposal: Boolean = false,
  ): SignedTopologyTransactionX[TopologyChangeOpX.Replace, TopologyMappingX] =
    SignedTopologyTransactionX(
      TopologyTransactionX(
        TopologyChangeOpX.Replace,
        serial,
        mapping,
        defaultProtocolVersion,
      ),
      Signature.noSignatures,
      isProposal,
    )(signedTxProtocolRepresentative)

  private def genKeyCollection(
      owner: Member
  ): Seq[SignedTopologyTransactionX[TopologyChangeOpX.Replace, TopologyMappingX]] = {
    val keyPurposes = topology.keyPurposes

    val sigKey =
      if (keyPurposes.contains(KeyPurpose.Signing))
        Seq(SymbolicCrypto.signingPublicKey(s"sigK-${keyFingerprintForOwner(owner).unwrap}"))
      else Seq()

    val encKey =
      if (keyPurposes.contains(KeyPurpose.Encryption))
        Seq(SymbolicCrypto.encryptionPublicKey(s"encK-${keyFingerprintForOwner(owner).unwrap}"))
      else Seq()

    NonEmpty
      .from(sigKey ++ encKey)
      .map { keys =>
        mkAdd(OwnerToKeyMappingX(owner, None, keys))
      }
      .toList
  }

  private def partyToParticipantTxs()
      : Iterable[SignedTopologyTransactionX[TopologyChangeOpX.Replace, TopologyMappingX]] =
    topology.topology
      .map { case (lfParty, participants) =>
        val partyId = PartyId.tryFromLfParty(lfParty)
        val participantsForParty = participants.iterator.filter(_._1.uid != partyId.uid)
        mkAdd(
          PartyToParticipantX(
            partyId,
            None,
            threshold = PositiveInt.one,
            participantsForParty.map { case (id, permission) =>
              HostingParticipant(id, permission.tryToX)
            }.toSeq,
            groupAddressing = false,
          )
        )
      }

  private def participantsTxs(
      defaultPermissionByParticipant: Map[ParticipantId, ParticipantPermission],
      packages: Seq[PackageId],
  ): Seq[SignedTopologyTransactionX[TopologyChangeOpX.Replace, TopologyMappingX]] = topology
    .allParticipants()
    .toSeq
    .flatMap { participantId =>
      val defaultPermission = defaultPermissionByParticipant
        .getOrElse(
          participantId,
          ParticipantPermission.Submission,
        )
      val attributes = topology.participants.getOrElse(
        participantId,
        ParticipantAttributes(defaultPermission, TrustLevel.Ordinary),
      )
      val pkgs =
        if (packages.nonEmpty)
          Seq(mkAdd(VettedPackagesX(participantId, None, packages)))
        else Seq()
      pkgs ++ genKeyCollection(participantId) :+ mkAdd(
        DomainTrustCertificateX(
          participantId,
          domainId,
          transferOnlyToGivenTargetDomains = false,
          targetDomains = Seq.empty,
        )
      ) :+ mkAdd(
        ParticipantDomainPermissionX(
          domainId,
          participantId,
          attributes.permission.tryToX,
          attributes.trustLevel.toX,
          limits = None,
          loginAfter = None,
        )
      )
    }

  private def keyFingerprintForOwner(owner: KeyOwner): Fingerprint =
    // We are converting an Identity (limit of 185 characters) to a Fingerprint (limit of 68 characters) - this would be
    // problematic if this function wasn't only used for testing
    Fingerprint.tryCreate(owner.uid.id.toLengthLimitedString.unwrap)

  def newCrypto(
      owner: KeyOwner,
      signingFingerprints: Seq[Fingerprint] = Seq(),
      fingerprintSuffixes: Seq[String] = Seq(),
  ): Crypto = {
    val signingFingerprintsOrOwner =
      if (signingFingerprints.isEmpty)
        Seq(keyFingerprintForOwner(owner))
      else
        signingFingerprints

    val fingerprintSuffixesOrOwner =
      if (fingerprintSuffixes.isEmpty)
        Seq(keyFingerprintForOwner(owner).unwrap)
      else
        fingerprintSuffixes

    SymbolicCrypto.tryCreate(
      signingFingerprintsOrOwner,
      fingerprintSuffixesOrOwner,
      testedReleaseProtocolVersion,
      DefaultProcessingTimeouts.testing,
      loggerFactory,
    )
  }

  def newSigningPublicKey(owner: KeyOwner): SigningPublicKey = {
    SymbolicCrypto.signingPublicKey(keyFingerprintForOwner(owner))
  }

}

/** something used often: somebody with keys and ability to created signed transactions */
class TestingOwnerWithKeysX(
    val keyOwner: KeyOwner,
    loggerFactory: NamedLoggerFactory,
    initEc: ExecutionContext,
) extends NoTracing {

  val cryptoApi = TestingIdentityFactory(loggerFactory).forOwnerAndDomain(keyOwner)

  object SigningKeys {

    implicit val ec = initEc

    val key1 = genSignKey("key1")
    val key2 = genSignKey("key2")
    val key3 = genSignKey("key3")
    val key4 = genSignKey("key4")
    val key5 = genSignKey("key5")
    val key6 = genSignKey("key6")
    val key7 = genSignKey("key7")
    val key8 = genSignKey("key8")
    val key9 = genSignKey("key9")

  }

  object EncryptionKeys {
    private implicit val ec = initEc
    val key1 = genEncKey("enc-key1")
  }

  object TestingTransactions {
    import SigningKeys.*
    val namespaceKey = key1
    val uid2 = uid.copy(id = Identifier.tryCreate("second"))
    val ts = CantonTimestamp.Epoch
    val ts1 = ts.plusSeconds(1)
    val ns1k1 = mkAdd(
      NamespaceDelegationX
        .create(
          Namespace(namespaceKey.fingerprint),
          namespaceKey,
          isRootDelegation = true,
        )
        .getOrElse(sys.error("creating NamespaceDelegationX should not have failed"))
    )
    val ns1k2 = mkAdd(
      NamespaceDelegationX
        .create(Namespace(namespaceKey.fingerprint), key2, isRootDelegation = false)
        .getOrElse(sys.error("creating NamespaceDelegationX should not have failed"))
    )
    val id1k1 = mkAdd(IdentifierDelegationX(uid, key1))
    val id2k2 = mkAdd(IdentifierDelegationX(uid2, key2))
    val okm1 = mkAdd(OwnerToKeyMappingX(domainManager, None, NonEmpty(Seq, namespaceKey)))
    val okm2 = mkAdd(OwnerToKeyMappingX(sequencerIdX, None, NonEmpty(Seq, key2)))
    val dtc1m =
      DomainTrustCertificateX(
        participant1,
        domainId,
        transferOnlyToGivenTargetDomains = false,
        targetDomains = Seq.empty,
      )
    val ps1 = mkAdd(dtc1m)

    private val defaultDomainParameters = TestDomainParameters.defaultDynamic

    val dpc1 = mkAdd(
      DomainParametersStateX(
        DomainId(uid),
        defaultDomainParameters
          .tryUpdate(participantResponseTimeout = NonNegativeFiniteDuration.tryOfSeconds(1)),
      ),
      namespaceKey,
    )
    val dpc1Updated = mkAdd(
      DomainParametersStateX(
        DomainId(uid),
        defaultDomainParameters
          .tryUpdate(
            participantResponseTimeout = NonNegativeFiniteDuration.tryOfSeconds(2),
            topologyChangeDelay = NonNegativeFiniteDuration.tryOfMillis(100),
          ),
      ),
      namespaceKey,
    )

    val dpc2 =
      mkAdd(DomainParametersStateX(DomainId(uid2), defaultDomainParameters), key2)
  }

  def mkTrans[Op <: TopologyChangeOpX, M <: TopologyMappingX](
      trans: TopologyTransactionX[Op, M],
      signingKeys: NonEmpty[Set[SigningPublicKey]] = NonEmpty(Set, SigningKeys.key1),
      isProposal: Boolean = false,
  )(implicit
      ec: ExecutionContext
  ): SignedTopologyTransactionX[Op, M] =
    Await
      .result(
        SignedTopologyTransactionX
          .create(
            trans,
            signingKeys.map(_.id),
            isProposal,
            cryptoApi.crypto.pureCrypto,
            cryptoApi.crypto.privateCrypto,
            BaseTest.testedProtocolVersion,
          )
          .value,
        10.seconds,
      )
      .getOrElse(sys.error("failed to create signed topology transaction"))

  def mkAdd[M <: TopologyMappingX](
      mapping: M,
      signingKey: SigningPublicKey = SigningKeys.key1,
      serial: PositiveInt = PositiveInt.one,
      isProposal: Boolean = false,
  )(implicit
      ec: ExecutionContext
  ): SignedTopologyTransactionX[TopologyChangeOpX.Replace, M] =
    mkAddMultiKey(mapping, NonEmpty(Set, signingKey), serial, isProposal)

  def mkAddMultiKey[M <: TopologyMappingX](
      mapping: M,
      signingKeys: NonEmpty[Set[SigningPublicKey]] = NonEmpty(Set, SigningKeys.key1),
      serial: PositiveInt = PositiveInt.one,
      isProposal: Boolean = false,
  )(implicit
      ec: ExecutionContext
  ): SignedTopologyTransactionX[TopologyChangeOpX.Replace, M] =
    mkTrans(
      TopologyTransactionX(
        TopologyChangeOpX.Replace,
        serial,
        mapping,
        BaseTest.testedProtocolVersion,
      ),
      signingKeys,
      isProposal,
    )
  private def genSignKey(name: String): SigningPublicKey =
    Await
      .result(
        cryptoApi.crypto
          .generateSigningKey(name = Some(KeyName.tryCreate(name)))
          .value,
        30.seconds,
      )
      .getOrElse(sys.error("key should be there"))

  def genEncKey(name: String): EncryptionPublicKey =
    Await
      .result(
        cryptoApi.crypto
          .generateEncryptionKey(name = Some(KeyName.tryCreate(name)))
          .value,
        30.seconds,
      )
      .getOrElse(sys.error("key should be there"))

}

object TestingIdentityFactoryX {

  def apply(
      loggerFactory: NamedLoggerFactory,
      topology: Map[LfPartyId, Map[ParticipantId, ParticipantPermission]] = Map.empty,
  ): TestingIdentityFactoryX =
    TestingIdentityFactoryX(
      TestingTopologyX(topology = topology),
      loggerFactory,
      TestDomainParameters.defaultDynamic,
    )

  def apply(
      topology: TestingTopologyX,
      loggerFactory: NamedLoggerFactory,
      dynamicDomainParameters: DynamicDomainParameters,
  ) = new TestingIdentityFactoryX(
    topology,
    loggerFactory,
    dynamicDomainParameters = List(
      DomainParameters.WithValidity(
        validFrom = CantonTimestamp.Epoch,
        validUntil = None,
        parameter = dynamicDomainParameters,
      )
    ),
  )

  def pureCrypto(): CryptoPureApi = new SymbolicPureCrypto

}