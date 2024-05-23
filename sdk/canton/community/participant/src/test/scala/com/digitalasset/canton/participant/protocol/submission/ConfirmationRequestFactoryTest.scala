// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.protocol.submission

import cats.data.EitherT
import cats.syntax.either.*
import cats.syntax.functor.*
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.*
import com.digitalasset.canton.config.{CachingConfigs, LoggingConfig}
import com.digitalasset.canton.crypto.SyncCryptoError.KeyNotAvailable
import com.digitalasset.canton.crypto.*
import com.digitalasset.canton.crypto.provider.symbolic.{SymbolicCrypto, SymbolicPureCrypto}
import com.digitalasset.canton.data.ViewType.TransactionViewType
import com.digitalasset.canton.data.*
import com.digitalasset.canton.ledger.participant.state.v2.SubmitterInfo
import com.digitalasset.canton.participant.DefaultParticipantStateValues
import com.digitalasset.canton.participant.protocol.submission.ConfirmationRequestFactory.*
import com.digitalasset.canton.participant.protocol.submission.EncryptedViewMessageFactory.{
  FailedToSignViewMessage,
  UnableToDetermineParticipant,
}
import com.digitalasset.canton.participant.protocol.submission.TransactionTreeFactory.{
  ContractLookupError,
  SerializableContractOfId,
  TransactionTreeConversionError,
}
import com.digitalasset.canton.protocol.ExampleTransactionFactory.*
import com.digitalasset.canton.protocol.WellFormedTransaction.{WithSuffixes, WithoutSuffixes}
import com.digitalasset.canton.protocol.*
import com.digitalasset.canton.protocol.messages.EncryptedViewMessageV1.RecipientsInfo
import com.digitalasset.canton.protocol.messages.*
import com.digitalasset.canton.sequencing.protocol.{OpenEnvelope, Recipient}
import com.digitalasset.canton.store.SessionKeyStore.RecipientGroup
import com.digitalasset.canton.store.SessionKeyStoreWithInMemoryCache
import com.digitalasset.canton.topology.*
import com.digitalasset.canton.topology.client.TopologySnapshot
import com.digitalasset.canton.topology.transaction.ParticipantPermission
import com.digitalasset.canton.topology.transaction.ParticipantPermission.*
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.OptionUtil
import com.digitalasset.canton.version.ProtocolVersion
import monocle.macros.syntax.lens.*
import org.scalatest.wordspec.AsyncWordSpec

import java.util.UUID
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}

class ConfirmationRequestFactoryTest
    extends AsyncWordSpec
    with ProtocolVersionChecksAsyncWordSpec
    with BaseTest
    with HasExecutorService {

  // Parties
  val observerParticipant1: ParticipantId = ParticipantId("observerParticipant1")
  val observerParticipant2: ParticipantId = ParticipantId("observerParticipant2")
  val observerParticipant3: ParticipantId = ParticipantId("observerParticipant3")

  // General dummy parameters
  val domain: DomainId = DefaultTestIdentities.domainId
  val applicationId: ApplicationId = DefaultDamlValues.applicationId()
  val commandId: CommandId = DefaultDamlValues.commandId()
  val mediator: MediatorRef = MediatorRef(DefaultTestIdentities.mediator)
  val ledgerTime: CantonTimestamp = CantonTimestamp.Epoch
  val submissionTime: CantonTimestamp = ledgerTime.plusMillis(7)
  val workflowId: Option[WorkflowId] = Some(
    WorkflowId.assertFromString("workflowIdConfirmationRequestFactoryTest")
  )
  val ledgerConfiguration: LedgerConfiguration = DefaultDamlValues.ledgerConfiguration
  val submitterInfo: SubmitterInfo = DefaultParticipantStateValues.submitterInfo(submitters)
  val maxSequencingTime: CantonTimestamp = ledgerTime.plusSeconds(10)

  // Crypto snapshots
  def createCryptoSnapshot(
      partyToParticipant: Map[ParticipantId, Seq[LfPartyId]],
      permission: ParticipantPermission = Submission,
      keyPurposes: Set[KeyPurpose] = KeyPurpose.all,
      encKeyTag: Option[String] = None,
  ): DomainSnapshotSyncCryptoApi = {

    val map = partyToParticipant.fmap(parties => parties.map(_ -> permission).toMap)
    TestingTopology()
      .withReversedTopology(map)
      .withDomains(domain)
      .withKeyPurposes(keyPurposes)
      .withEncKeyTag(OptionUtil.noneAsEmptyString(encKeyTag))
      .build(loggerFactory)
      .forOwnerAndDomain(submitterParticipant, domain)
      .currentSnapshotApproximation
  }

  val defaultTopology: Map[ParticipantId, Seq[LfPartyId]] = Map(
    submitterParticipant -> Seq(submitter, signatory),
    observerParticipant1 -> Seq(observer),
    observerParticipant2 -> Seq(observer),
    observerParticipant3 -> Seq(extra),
  )

  // Collaborators

  // This is a def (and not a val), as the crypto api has the next symmetric key as internal state
  // Therefore, it would not make sense to reuse an instance. We also force the crypto api to not randomize
  // asymmetric encryption ciphertexts.
  def newCryptoSnapshot: DomainSnapshotSyncCryptoApi = {
    val cryptoSnapshot = createCryptoSnapshot(defaultTopology)
    cryptoSnapshot.pureCrypto match {
      case crypto: SymbolicPureCrypto => crypto.setRandomnessFlag(true)
      case _ => ()
    }
    cryptoSnapshot
  }

  val privateCryptoApi: DomainSnapshotSyncCryptoApi =
    TestingTopology()
      .withSimpleParticipants(submitterParticipant)
      .build()
      .forOwnerAndDomain(submitterParticipant, domain)
      .currentSnapshotApproximation
  val randomOps: RandomOps = new SymbolicPureCrypto()

  val transactionUuid: UUID = new UUID(10L, 20L)

  val seedGenerator: SeedGenerator =
    new SeedGenerator(randomOps) {
      override def generateUuid(): UUID = transactionUuid
    }

  // Device under test
  def confirmationRequestFactory(
      transactionTreeFactoryResult: Either[TransactionTreeConversionError, GenTransactionTree]
  ): ConfirmationRequestFactory = {

    val transactionTreeFactory: TransactionTreeFactory = new TransactionTreeFactory {
      override def createTransactionTree(
          transaction: WellFormedTransaction[WithoutSuffixes],
          submitterInfo: SubmitterInfo,
          _confirmationPolicy: ConfirmationPolicy,
          _workflowId: Option[WorkflowId],
          _mediator: MediatorRef,
          transactionSeed: SaltSeed,
          transactionUuid: UUID,
          _topologySnapshot: TopologySnapshot,
          _contractOfId: SerializableContractOfId,
          _keyResolver: LfKeyResolver,
          _maxSequencingTime: CantonTimestamp,
          validatePackageVettings: Boolean,
      )(implicit
          traceContext: TraceContext
      ): EitherT[Future, TransactionTreeConversionError, GenTransactionTree] = {
        val actAs = submitterInfo.actAs.toSet
        if (actAs != Set(ExampleTransactionFactory.submitter))
          fail(
            s"Wrong submitters ${actAs.mkString(", ")}. Expected ${ExampleTransactionFactory.submitter}"
          )
        if (transaction.metadata.ledgerTime != ConfirmationRequestFactoryTest.this.ledgerTime)
          fail(s"""Wrong ledger time ${transaction.metadata.ledgerTime}.
                  | Expected ${ConfirmationRequestFactoryTest.this.ledgerTime}""".stripMargin)
        if (transactionUuid != ConfirmationRequestFactoryTest.this.transactionUuid)
          fail(
            s"Wrong transaction UUID $transactionUuid. Expected ${ConfirmationRequestFactoryTest.this.transactionUuid}"
          )
        transactionTreeFactoryResult.toEitherT
      }

      override def tryReconstruct(
          subaction: WellFormedTransaction[WithoutSuffixes],
          rootPosition: ViewPosition,
          confirmationPolicy: ConfirmationPolicy,
          mediator: MediatorRef,
          submittingParticipantO: Option[ParticipantId],
          salts: Iterable[Salt],
          transactionUuid: UUID,
          topologySnapshot: TopologySnapshot,
          contractOfId: SerializableContractOfId,
          _rbContext: RollbackContext,
          _keyResolver: LfKeyResolver,
      )(implicit traceContext: TraceContext): EitherT[
        Future,
        TransactionTreeConversionError,
        (TransactionView, WellFormedTransaction[WithSuffixes]),
      ] = ???

      override def saltsFromView(view: TransactionView): Iterable[Salt] = ???
    }

    // we force view requests to be handled sequentially, which makes results deterministic and easier to compare
    // in the end.
    new ConfirmationRequestFactory(
      submitterParticipant,
      LoggingConfig(),
      loggerFactory,
      parallel = false,
    )(
      transactionTreeFactory,
      seedGenerator,
    )(executorService)
  }

  private val contractInstanceOfId: SerializableContractOfId = { (id: LfContractId) =>
    EitherT.leftT(ContractLookupError(id, "Error in test: argument should not be used"))
  }
  // This isn't used because the transaction tree factory is mocked

  // Input factory
  val transactionFactory: ExampleTransactionFactory =
    new ExampleTransactionFactory()(
      confirmationPolicy = ConfirmationPolicy.Signatory,
      ledgerTime = ledgerTime,
    )

  // Since the ConfirmationRequestFactory signs the envelopes in parallel,
  // we cannot predict the counter that SymbolicCrypto uses to randomize the signatures.
  // So we simply replace them with a fixed empty signature. When we are using
  // EncryptedViewMessageV2 we order the sequence of randomness encryption on both the actual
  // and expected messages so that they match.
  def stripSignatureAndOrderMap(request: ConfirmationRequest): ConfirmationRequest = {
    val requestNoSignature = request
      .focus(_.viewEnvelopes)
      .modify(_.map(_.focus(_.protocolMessage).modify {
        case v0: EncryptedViewMessageV0[_] =>
          v0.focus(_.submitterParticipantSignature)
            .modify(_.map(_ => SymbolicCrypto.emptySignature))
        case v1: EncryptedViewMessageV1[_] =>
          v1.focus(_.submitterParticipantSignature)
            .modify(_.map(_ => SymbolicCrypto.emptySignature))
        case v2: EncryptedViewMessageV2[_] =>
          v2.focus(_.submitterParticipantSignature)
            .modify(_.map(_ => SymbolicCrypto.emptySignature))
      }))

    val orderedTvm = requestNoSignature.viewEnvelopes.map(tvm =>
      tvm.protocolMessage match {
        case encViewMessage @ EncryptedViewMessageV2(_, _, _, _, _, _, _) =>
          val encryptedRandomnessOrdering: Ordering[AsymmetricEncrypted[SecureRandomness]] =
            Ordering.by(_.encryptedFor.unwrap)
          tvm.copy(protocolMessage =
            encViewMessage
              .copy(sessionKeyRandomness =
                encViewMessage.sessionKey
                  .sorted(encryptedRandomnessOrdering)
              )
          )
        case _ => tvm
      }
    )

    requestNoSignature
      .focus(_.viewEnvelopes)
      .replace(orderedTvm)
  }

  // Expected output factory
  def expectedConfirmationRequest(
      example: ExampleTransaction,
      cryptoSnapshot: DomainSnapshotSyncCryptoApi,
  ): ConfirmationRequest = {
    val cryptoPureApi = cryptoSnapshot.pureCrypto
    val viewEncryptionScheme = cryptoPureApi.defaultSymmetricKeyScheme

    val privateKeysetCache: TrieMap[NonEmpty[Set[Recipient]], SecureRandomness] =
      TrieMap.empty

    val expectedTransactionViewMessages = example.transactionViewTreesWithWitnesses.map {
      case (tree, witnesses) =>
        val signature =
          if (tree.isTopLevel) {
            Some(
              Await
                .result(cryptoSnapshot.sign(tree.transactionId.unwrap).value, 10.seconds)
                .valueOr(err => fail(err.toString))
            )
          } else None

        val keySeed = tree.viewPosition.position.foldRight(testKeySeed) { case (pos, seed) =>
          cryptoPureApi
            .computeHkdf(
              seed.unwrap,
              cryptoPureApi.defaultSymmetricKeyScheme.keySizeInBytes,
              HkdfInfo.subview(pos),
            )
            .valueOr(e => throw new IllegalStateException(s"Failed to derive key: $e"))
        }
        val symmetricKeyRandomness = cryptoPureApi
          .computeHkdf(
            keySeed.unwrap,
            viewEncryptionScheme.keySizeInBytes,
            HkdfInfo.ViewKey,
          )
          .valueOr(e => fail(s"Failed to derive key: $e"))

        val symmetricKey = cryptoPureApi
          .createSymmetricKey(symmetricKeyRandomness, viewEncryptionScheme)
          .valueOrFail("failed to create symmetric key from randomness")

        val participants = tree.informees
          .map(cryptoSnapshot.ipsSnapshot.activeParticipantsOf(_).futureValue)
          .flatMap(_.keySet)

        val encryptedView = EncryptedView
          .compressed(
            cryptoPureApi,
            symmetricKey,
            TransactionViewType,
            testedProtocolVersion,
          )(
            LightTransactionViewTree.fromTransactionViewTree(tree)
          )
          .valueOr(err => fail(s"Failed to encrypt view tree: $err"))

        val ec: ExecutionContext = executorService
        val recipients = witnesses
          .toRecipients(cryptoSnapshot.ipsSnapshot)(ec)
          .value
          .futureValue
          .value

        val encryptedViewMessage: EncryptedViewMessage[TransactionViewType] = {
          if (testedProtocolVersion >= ProtocolVersion.v6) {
            // simulates session key cache
            val keySeedSession = privateKeysetCache.getOrElseUpdate(
              recipients.leafRecipients,
              cryptoPureApi
                .computeHkdf(
                  cryptoPureApi.generateSecureRandomness(keySeed.unwrap.size()).unwrap,
                  viewEncryptionScheme.keySizeInBytes,
                  HkdfInfo.SessionKey,
                )
                .valueOrFail("error generating randomness for session key"),
            )
            val sessionKey = cryptoPureApi
              .createSymmetricKey(keySeedSession, viewEncryptionScheme)
              .valueOrFail("failed to create session key from randomness")
            val encryptedRandomness = cryptoPureApi
              .encryptWith(keySeed, sessionKey, testedProtocolVersion)
              .valueOrFail(
                "could not encrypt view randomness with session key"
              )

            val randomnessMapNE = NonEmpty
              .from(randomnessMap(keySeedSession, participants, cryptoPureApi).values.toSeq)
              .valueOrFail("session key randomness map is empty")

            EncryptedViewMessageV2(
              signature,
              tree.viewHash,
              encryptedRandomness,
              randomnessMapNE,
              encryptedView,
              transactionFactory.domainId,
              SymmetricKeyScheme.Aes128Gcm,
            )(Some(RecipientsInfo(participants)))
          } else
            EncryptedViewMessageV1(
              signature,
              tree.viewHash,
              randomnessMap(keySeed, participants, cryptoPureApi).values.toSeq,
              encryptedView,
              transactionFactory.domainId,
              SymmetricKeyScheme.Aes128Gcm,
            )(Some(RecipientsInfo(participants)))
        }

        OpenEnvelope(encryptedViewMessage, recipients)(testedProtocolVersion)
    }

    ConfirmationRequest(
      InformeeMessage(example.fullInformeeTree)(testedProtocolVersion),
      expectedTransactionViewMessages,
      testedProtocolVersion,
    )
  }

  val testKeySeed: SecureRandomness = randomOps.generateSecureRandomness(
    newCryptoSnapshot.crypto.pureCrypto.defaultSymmetricKeyScheme.keySizeInBytes
  )

  def randomnessMap(
      randomness: SecureRandomness,
      informeeParticipants: Set[ParticipantId],
      cryptoPureApi: CryptoPureApi,
  ): Map[ParticipantId, AsymmetricEncrypted[SecureRandomness]] = {

    val randomnessPairs = for {
      participant <- informeeParticipants
      publicKey = newCryptoSnapshot.ipsSnapshot
        .encryptionKey(participant)
        .futureValue
        .getOrElse(fail("The defaultIdentitySnapshot really should have at least one key."))
    } yield participant -> cryptoPureApi
      .encryptWith(randomness, publicKey, testedProtocolVersion)
      .valueOr(err => fail(err.toString))

    randomnessPairs.toMap
  }

  val singleFetch: transactionFactory.SingleFetch = transactionFactory.SingleFetch()

  "A ConfirmationRequestFactory" when {
    "everything is ok" can {

      forEvery(transactionFactory.standardHappyCases) { example =>
        s"create a confirmation request for: $example" in {

          val factory = confirmationRequestFactory(Right(example.transactionTree))

          factory
            .createConfirmationRequest(
              example.wellFormedUnsuffixedTransaction,
              ConfirmationPolicy.Vip,
              submitterInfo,
              workflowId,
              example.keyResolver,
              mediator,
              newCryptoSnapshot,
              new SessionKeyStoreWithInMemoryCache(CachingConfigs.defaultSessionKeyCacheConfig),
              contractInstanceOfId,
              Some(testKeySeed),
              maxSequencingTime,
              testedProtocolVersion,
            )
            .value
            .map { res =>
              val expected = expectedConfirmationRequest(example, newCryptoSnapshot)
              stripSignatureAndOrderMap(res.value) shouldBe stripSignatureAndOrderMap(expected)
            }
        }
      }

      s"use different session key after key is revoked between two requests" onlyRunWithOrGreaterThan ProtocolVersion.v6 in {
        val factory = confirmationRequestFactory(Right(singleFetch.transactionTree))
        // we use the same store for two requests to simulate what would happen in a real scenario
        val store =
          new SessionKeyStoreWithInMemoryCache(CachingConfigs.defaultSessionKeyCacheConfig)
        val recipientGroup = RecipientGroup(
          NonEmpty(Set, submitterParticipant, observerParticipant1, observerParticipant2),
          newCryptoSnapshot.pureCrypto.defaultSymmetricKeyScheme,
        )

        def getSessionKeyFromConfirmationRequest(cryptoSnapshot: DomainSnapshotSyncCryptoApi) =
          factory
            .createConfirmationRequest(
              singleFetch.wellFormedUnsuffixedTransaction,
              ConfirmationPolicy.Vip,
              submitterInfo,
              workflowId,
              singleFetch.keyResolver,
              mediator,
              cryptoSnapshot,
              store,
              contractInstanceOfId,
              Some(testKeySeed),
              maxSequencingTime,
              testedProtocolVersion,
            )
            .map(_ =>
              store
                .getSessionKeyInfoIfPresent(recipientGroup)
                .valueOrFail("session key not found")
            )

        for {
          firstSessionKeyInfo <- getSessionKeyFromConfirmationRequest(newCryptoSnapshot)
          secondSessionKeyInfo <- getSessionKeyFromConfirmationRequest(newCryptoSnapshot)
          // we add a tag that is to be appended to the encryption key id
          // (to enforce that the key is different from the previous one, i.e. simulate a key rotation/revocation)
          anotherCryptoSnapshot = createCryptoSnapshot(defaultTopology, encKeyTag = Some("-new"))
          thirdSessionKeyInfo <- getSessionKeyFromConfirmationRequest(anotherCryptoSnapshot)
        } yield {
          firstSessionKeyInfo shouldBe secondSessionKeyInfo
          secondSessionKeyInfo should not be thirdSessionKeyInfo
        }
      }
    }

    "submitter node does not represent submitter" must {

      val emptyCryptoSnapshot = createCryptoSnapshot(Map.empty)

      "be rejected" in {
        val factory = confirmationRequestFactory(Right(singleFetch.transactionTree))

        factory
          .createConfirmationRequest(
            singleFetch.wellFormedUnsuffixedTransaction,
            ConfirmationPolicy.Vip,
            submitterInfo,
            workflowId,
            singleFetch.keyResolver,
            mediator,
            emptyCryptoSnapshot,
            new SessionKeyStoreWithInMemoryCache(CachingConfigs.defaultSessionKeyCacheConfig),
            contractInstanceOfId,
            Some(testKeySeed),
            maxSequencingTime,
            testedProtocolVersion,
          )
          .value
          .map(
            _ should equal(
              Left(
                ParticipantAuthorizationError(
                  s"$submitterParticipant does not host $submitter or is not active."
                )
              )
            )
          )
      }
    }

    "submitter node is not allowed to submit transactions" must {

      val confirmationOnlyCryptoSnapshot =
        createCryptoSnapshot(defaultTopology, permission = Confirmation)

      "be rejected" in {
        val factory = confirmationRequestFactory(Right(singleFetch.transactionTree))

        factory
          .createConfirmationRequest(
            singleFetch.wellFormedUnsuffixedTransaction,
            ConfirmationPolicy.Vip,
            submitterInfo,
            workflowId,
            singleFetch.keyResolver,
            mediator,
            confirmationOnlyCryptoSnapshot,
            new SessionKeyStoreWithInMemoryCache(CachingConfigs.defaultSessionKeyCacheConfig),
            contractInstanceOfId,
            Some(testKeySeed),
            maxSequencingTime,
            testedProtocolVersion,
          )
          .value
          .map(
            _ should equal(
              Left(
                ParticipantAuthorizationError(
                  s"$submitterParticipant is not authorized to submit transactions for $submitter."
                )
              )
            )
          )
      }
    }

    "transactionTreeFactory fails" must {
      "be rejected" in {
        val error = ContractLookupError(ExampleTransactionFactory.suffixedId(-1, -1), "foo")
        val factory = confirmationRequestFactory(Left(error))

        factory
          .createConfirmationRequest(
            singleFetch.wellFormedUnsuffixedTransaction,
            ConfirmationPolicy.Vip,
            submitterInfo,
            workflowId,
            singleFetch.keyResolver,
            mediator,
            newCryptoSnapshot,
            new SessionKeyStoreWithInMemoryCache(CachingConfigs.defaultSessionKeyCacheConfig),
            contractInstanceOfId,
            Some(testKeySeed),
            maxSequencingTime,
            testedProtocolVersion,
          )
          .value
          .map(_ should equal(Left(TransactionTreeFactoryError(error))))
      }
    }

    // Note lack of test for ill-authorized transaction as authorization check is performed by LF upon ledger api submission as of Daml 1.6.0

    "informee participant cannot be found" must {

      val submitterOnlyCryptoSnapshot =
        createCryptoSnapshot(Map(submitterParticipant -> Seq(submitter)))

      "be rejected" in {
        val factory = confirmationRequestFactory(Right(singleFetch.transactionTree))

        factory
          .createConfirmationRequest(
            singleFetch.wellFormedUnsuffixedTransaction,
            ConfirmationPolicy.Vip,
            submitterInfo,
            workflowId,
            singleFetch.keyResolver,
            mediator,
            submitterOnlyCryptoSnapshot,
            new SessionKeyStoreWithInMemoryCache(CachingConfigs.defaultSessionKeyCacheConfig),
            contractInstanceOfId,
            Some(testKeySeed),
            maxSequencingTime,
            testedProtocolVersion,
          )
          .value
          .map(
            _ should equal(
              Left(
                EncryptedViewMessageCreationError(
                  UnableToDetermineParticipant(Set(observer), submitterOnlyCryptoSnapshot.domainId)
                )
              )
            )
          )
      }
    }

    "participants have no public keys" must {

      val noKeyCryptoSnapshot = createCryptoSnapshot(defaultTopology, keyPurposes = Set.empty)

      "be rejected" in {
        val factory = confirmationRequestFactory(Right(singleFetch.transactionTree))

        factory
          .createConfirmationRequest(
            singleFetch.wellFormedUnsuffixedTransaction,
            ConfirmationPolicy.Vip,
            submitterInfo,
            workflowId,
            singleFetch.keyResolver,
            mediator,
            noKeyCryptoSnapshot,
            new SessionKeyStoreWithInMemoryCache(CachingConfigs.defaultSessionKeyCacheConfig),
            contractInstanceOfId,
            Some(testKeySeed),
            maxSequencingTime,
            testedProtocolVersion,
          )
          .value
          .map {
            case Left(EncryptedViewMessageCreationError(viewErr)) =>
              viewErr should matchPattern {
                case FailedToSignViewMessage(KeyNotAvailable(_, _, _, _)) =>
              }
            case _ => fail("should have failed with a view message creation error")
          }
      }
    }
  }
}